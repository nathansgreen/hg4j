/*
 * Copyright (c) 2010-2011 TMate Software Ltd
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.repo;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.HashMap;

import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.RequiresFile;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.PathRewrite;



/**
 * Shall be as state-less as possible, all the caching happens outside the repo, in commands/walkers
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgRepository {

	public static final int TIP = -1;
	public static final int BAD_REVISION = Integer.MIN_VALUE;
	public static final int WORKING_COPY = -2;

	// temp aux marker method
	public static IllegalStateException notImplemented() {
		return new IllegalStateException("Not implemented");
	}

	private final File repoDir; // .hg folder
	private final String repoLocation;
	private final DataAccessProvider dataAccess;
	private final PathRewrite normalizePath = new PathRewrite() {
		
		public String rewrite(String path) {
			// TODO handle . and .. (although unlikely to face them from GUI client)
			path = path.replace('\\', '/').replace("//", "/");
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			return path;
		}
	};
	private final PathRewrite dataPathHelper;
	private final PathRewrite repoPathHelper;

	private Changelog changelog;
	private HgManifest manifest;
	private HgTags tags;
	// XXX perhaps, shall enable caching explicitly
	private final HashMap<Path, SoftReference<RevlogStream>> streamsCache = new HashMap<Path, SoftReference<RevlogStream>>();
	
	private final org.tmatesoft.hg.internal.Internals impl = new org.tmatesoft.hg.internal.Internals();

	HgRepository(String repositoryPath) {
		repoDir = null;
		repoLocation = repositoryPath;
		dataAccess = null;
		dataPathHelper = repoPathHelper = null;
	}
	
	HgRepository(File repositoryRoot) throws IOException {
		assert ".hg".equals(repositoryRoot.getName()) && repositoryRoot.isDirectory();
		repoDir = repositoryRoot;
		repoLocation = repositoryRoot.getParentFile().getCanonicalPath();
		dataAccess = new DataAccessProvider();
		parseRequires();
		dataPathHelper = impl.buildDataFilesHelper();
		repoPathHelper = impl.buildRepositoryFilesHelper();
	}

	
	public String getLocation() {
		return repoLocation;
	}

	public boolean isInvalid() {
		return repoDir == null || !repoDir.exists() || !repoDir.isDirectory();
	}
	
	public Changelog getChangelog() {
		if (this.changelog == null) {
			String storagePath = repoPathHelper.rewrite("00changelog.i");
			RevlogStream content = resolve(Path.create(storagePath));
			this.changelog = new Changelog(this, content);
		}
		return this.changelog;
	}
	
	public HgManifest getManifest() {
		if (this.manifest == null) {
			RevlogStream content = resolve(Path.create(repoPathHelper.rewrite("00manifest.i")));
			this.manifest = new HgManifest(this, content);
		}
		return this.manifest;
	}
	
	public final HgTags getTags() {
		if (tags == null) {
			tags = new HgTags();
		}
		return tags;
	}
	
	public HgDataFile getFileNode(String path) {
		String nPath = normalizePath.rewrite(path);
		String storagePath = dataPathHelper.rewrite(nPath);
		return getFileNode(Path.create(storagePath));
	}

	public HgDataFile getFileNode(Path path) {
		RevlogStream content = resolve(path);
		// XXX no content when no file? or HgDataFile.exists() to detect that? How about files that were removed in previous releases?
		return new HgDataFile(this, path, content);
	}

	public PathRewrite getPathHelper() { // Really need to be public?
		return normalizePath;
	}

	/*package-local*/ File getRepositoryRoot() {
		return repoDir;
	}

	// XXX package-local, unless there are cases when required from outside (guess, working dir/revision walkers may hide dirstate access and no public visibility needed)
	/*package-local*/ final HgDirstate loadDirstate() {
		return new HgDirstate(getDataAccess(), new File(repoDir, "dirstate"));
	}

	// package-local, see comment for loadDirstate
	/*package-local*/ final HgIgnore loadIgnore() {
		return new HgIgnore(this);
	}

	/*package-local*/ DataAccessProvider getDataAccess() {
		return dataAccess;
	}

	// FIXME not sure repository shall create walkers
	/*package-local*/ FileWalker createWorkingDirWalker() {
		return new FileWalker(repoDir.getParentFile());
	}

	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 * path - repository storage path (i.e. one usually with .i or .d)
	 */
	/*package-local*/ RevlogStream resolve(Path path) {
		final SoftReference<RevlogStream> ref = streamsCache.get(path);
		RevlogStream cached = ref == null ? null : ref.get();
		if (cached != null) {
			return cached;
		}
		File f = new File(repoDir, path.toString());
		if (f.exists()) {
			RevlogStream s = new RevlogStream(dataAccess, f);
			streamsCache.put(path, new SoftReference<RevlogStream>(s));
			return s;
		}
		return null; // XXX empty stream instead?
	}

	private void parseRequires() {
		new RequiresFile().parse(impl, new File(repoDir, "requires"));
	}
}
