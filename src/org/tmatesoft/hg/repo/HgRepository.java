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
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.repo;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.Filter;
import org.tmatesoft.hg.internal.RequiresFile;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.Path;
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

	private HgChangelog changelog;
	private HgManifest manifest;
	private HgTags tags;
	// XXX perhaps, shall enable caching explicitly
	private final HashMap<Path, SoftReference<RevlogStream>> streamsCache = new HashMap<Path, SoftReference<RevlogStream>>();
	
	private final org.tmatesoft.hg.internal.Internals impl = new org.tmatesoft.hg.internal.Internals();
	private HgIgnore ignore;
	private ConfigFile configFile;

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
	
	public HgChangelog getChangelog() {
		if (this.changelog == null) {
			String storagePath = repoPathHelper.rewrite("00changelog.i");
			RevlogStream content = resolve(Path.create(storagePath));
			this.changelog = new HgChangelog(this, content);
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
			try {
				tags.readGlobal(new File(repoDir.getParentFile(), ".hgtags"));
				tags.readLocal(new File(repoDir, "localtags"));
			} catch (IOException ex) {
				ex.printStackTrace(); // FIXME log or othewise report
			}
		}
		return tags;
	}
	
	public HgDataFile getFileNode(String path) {
		String nPath = normalizePath.rewrite(path);
		String storagePath = dataPathHelper.rewrite(nPath);
		RevlogStream content = resolve(Path.create(storagePath));
		Path p = Path.create(nPath);
		if (content == null) {
			return new HgDataFile(this, p);
		}
		return new HgDataFile(this, p, content);
	}

	public HgDataFile getFileNode(Path path) {
		String storagePath = dataPathHelper.rewrite(path.toString());
		RevlogStream content = resolve(Path.create(storagePath));
		// XXX no content when no file? or HgDataFile.exists() to detect that?
		if (content == null) {
			return new HgDataFile(this, path);
		}
		return new HgDataFile(this, path, content);
	}

	public PathRewrite getPathHelper() { // Really need to be public?
		return normalizePath;
	}

	// local to hide use of io.File. 
	/*package-local*/ File getRepositoryRoot() {
		return repoDir;
	}

	// XXX package-local, unless there are cases when required from outside (guess, working dir/revision walkers may hide dirstate access and no public visibility needed)
	/*package-local*/ final HgDirstate loadDirstate() {
		return new HgDirstate(getDataAccess(), new File(repoDir, "dirstate"));
	}

	// package-local, see comment for loadDirstate
	/*package-local*/ final HgIgnore getIgnore() {
		// TODO read config for additional locations
		if (ignore == null) {
			ignore = new HgIgnore();
			try {
				File ignoreFile = new File(repoDir.getParentFile(), ".hgignore");
				ignore.read(ignoreFile);
			} catch (IOException ex) {
				ex.printStackTrace(); // log warn
			}
		}
		return ignore;
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
	
	// can't expose internal class, otherwise seems reasonable to have it in API
	/*package-local*/ ConfigFile getConfigFile() {
		if (configFile == null) {
			configFile = impl.newConfigFile();
			configFile.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
			// last one, overrides anything else
			// <repo>/.hg/hgrc
			configFile.addLocation(new File(getRepositoryRoot(), "hgrc"));
		}
		return configFile;
	}
	
	/*package-local*/ List<Filter> getFiltersFromRepoToWorkingDir(Path p) {
		return instantiateFilters(p, new Filter.Options(Filter.Direction.FromRepo));
	}

	/*package-local*/ List<Filter> getFiltersFromWorkingDirToRepo(Path p) {
		return instantiateFilters(p, new Filter.Options(Filter.Direction.ToRepo));
	}

	private List<Filter> instantiateFilters(Path p, Filter.Options opts) {
		List<Filter.Factory> factories = impl.getFilters(this, getConfigFile());
		if (factories.isEmpty()) {
			return Collections.emptyList();
		}
		ArrayList<Filter> rv = new ArrayList<Filter>(factories.size());
		for (Filter.Factory ff : factories) {
			Filter f = ff.create(p, opts);
			if (f != null) {
				rv.add(f);
			}
		}
		return rv;
	}

	private void parseRequires() {
		new RequiresFile().parse(impl, new File(repoDir, "requires"));
	}

}
