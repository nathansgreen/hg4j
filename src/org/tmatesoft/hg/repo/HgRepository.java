/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepositoryFiles.LastMessage;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.Filter;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PropertyMarshal;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.internal.SubrepoManager;
import org.tmatesoft.hg.repo.ext.HgExtensionsManager;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;
import org.tmatesoft.hg.util.ProgressSupport;



/**
 * Shall be as state-less as possible, all the caching happens outside the repo, in commands/walkers
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgRepository implements SessionContext.Source {

	// IMPORTANT: if new constants added, consider fixing HgInternals#wrongRevisionIndex and HgInvalidRevisionException#getMessage

	/**
	 * Revision index constant to indicate most recent revision
	 */
	public static final int TIP = -3; // XXX TIP_REVISION?

	/**
	 * Revision index constant to indicate invalid revision index value. 
	 * Primary use is default/uninitialized values where user input is expected and as return value where 
	 * an exception (e.g. {@link HgInvalidRevisionException}) is not desired
	 */
	public static final int BAD_REVISION = Integer.MIN_VALUE; // XXX INVALID_REVISION?

	/**
	 * Revision index constant to indicate working copy
	 */
	public static final int WORKING_COPY = -2; // XXX WORKING_COPY_REVISION?
	
	/**
	 * Constant ({@value #NO_REVISION}) to indicate revision absence or a fictitious revision of an empty repository.
	 * 
	 * <p>Revision absence is vital e.g. for missing parent from {@link HgChangelog#parents(int, int[], byte[], byte[])} call and
	 * to report cases when changeset records no corresponding manifest 
	 * revision {@link HgManifest#walk(int, int, org.tmatesoft.hg.repo.HgManifest.Inspector)}.
	 * 
	 * <p> Use as imaginary revision/empty repository is handy as an argument (contrary to {@link #BAD_REVISION})
	 * e.g in a status operation to visit changes from the very beginning of a repository.
	 */
	public static final int NO_REVISION = -1;
	
	/**
	 * Name of the primary branch, "default".
	 */
	public static final String DEFAULT_BRANCH_NAME = "default";

	private final File workingDir; // .hg/../
	private final String repoLocation;
	/*
	 * normalized slashes but otherwise regular file names
	 * the only front-end path rewrite, kept here as rest of the library shall
	 * not bother with names normalization.
	 */
	private final PathRewrite normalizePath;
	private final SessionContext sessionContext;

	private HgChangelog changelog;
	private HgManifest manifest;
	private HgTags tags;
	private HgBranches branches;
	private HgMergeState mergeState;
	private SubrepoManager subRepos;
	private HgBookmarks bookmarks;
	private HgExtensionsManager extManager;
	private HgIgnore ignore;
	private HgRepoConfig repoConfig;
	
	private HgRepositoryLock wdLock, storeLock;

	private final org.tmatesoft.hg.internal.Internals impl;
	
	/*
	 * TODO [post-1.0] move to a better place, e.g. WorkingCopy container that tracks both dirstate and branches 
	 * (and, perhaps, undo, lastcommit and other similar information), and is change listener so that we don't need to
	 * worry about this cached value become stale
	 */
	private String wcBranch;

	
	HgRepository(String repositoryPath) {
		workingDir = null;
		repoLocation = repositoryPath;
		normalizePath = null;
		sessionContext = null;
		impl = null;
	}
	
	/**
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	HgRepository(SessionContext ctx, String repositoryPath, File repositoryRoot) throws HgRuntimeException {
		assert ".hg".equals(repositoryRoot.getName()) && repositoryRoot.isDirectory();
		assert repositoryPath != null; 
		assert repositoryRoot != null;
		assert ctx != null;
		workingDir = repositoryRoot.getParentFile();
		if (workingDir == null) {
			throw new IllegalArgumentException(repositoryRoot.toString());
		}
		repoLocation = repositoryPath;
		sessionContext = ctx;
		impl = new Internals(this, repositoryRoot, new Internals.ImplAccess() {
			
			public RevlogStream getStream(HgDataFile df) {
				return df.content;
			}
			public RevlogStream getManifestStream() {
				return HgRepository.this.getManifest().content;
			}
			public RevlogStream getChangelogStream() {
				return HgRepository.this.getChangelog().content;
			}
		});
		normalizePath = impl.buildNormalizePathRewrite();
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + getLocation() + (isInvalid() ? "(BAD)" : "") + "]";
	}                         
	
	/**
	 * Path to repository which has been used to initialize this instance. The value is always present, even 
	 * if no repository has been found at that location ({@link #isInvalid()} is <code>true</code>) and serves 
	 * as an extra description of the failure.
	 * 
	 * <p> It's important to understand this is purely descriptive attribute, it's kept as close as possible to 
	 * original value users supply to {@link HgLookup}. To get actual repository location, use methods that 
	 * provide {@link File}, e.g. {@link #getWorkingDir()}
	 * 
	 * @return repository location information, never <code>null</code>
	 */
	public String getLocation() {
		return repoLocation; // XXX field to keep this is bit too much 
	}

	public boolean isInvalid() {
		return impl == null || impl.isInvalid();
	}
	
	public HgChangelog getChangelog() {
		if (changelog == null) {
			RevlogStream content = impl.createChangelogStream();
			changelog = new HgChangelog(this, content);
		}
		return changelog;
	}
	
	public HgManifest getManifest() {
		if (manifest == null) {
			RevlogStream content = impl.createManifestStream();
			manifest = new HgManifest(this, content, impl.buildFileNameEncodingHelper());
		}
		return manifest;
	}
	
	/**
	 * Access snapshot of repository tags.
	 * 
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgTags getTags() throws HgInvalidControlFileException {
		if (tags == null) {
			tags = new HgTags(impl);
			tags.read();
		} else {
			tags.reloadIfChanged();
		}
		return tags;
	}
	
	/**
	 * Access branch information. Returns a snapshot of branch information as it's available at the time of the call.
	 * If repository get changed, use this method to obtain an up-to-date state. 
	 * 
	 * @return branch manager instance, never <code>null</code>
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgBranches getBranches() throws HgInvalidControlFileException {
		final ProgressSupport ps = ProgressSupport.Factory.get(null);
		if (branches == null) {
			branches = new HgBranches(impl);
			branches.collect(ps);
		} else {
			branches.reloadIfChanged(ps);
		}
		return branches;
	}

	/**
	 * Access state of the recent merge
	 * @return merge state facility, never <code>null</code> 
	 */
	public HgMergeState getMergeState() {
		if (mergeState == null) {
			mergeState = new HgMergeState(impl);
		}
		return mergeState;
	}
	
	public HgDataFile getFileNode(String path) {
		CharSequence nPath = normalizePath.rewrite(path);
		Path p = sessionContext.getPathFactory().path(nPath);
		return getFileNode(p);
	}

	public HgDataFile getFileNode(Path path) {
		RevlogStream content = impl.resolveStoreFile(path);
		if (content == null) {
			return new HgDataFile(this, path);
		}
		return new HgDataFile(this, path, content);
	}

	/* clients need to rewrite path from their FS to a repository-friendly paths, and, perhaps, vice versa*/
	public PathRewrite getToRepoPathHelper() {
		return normalizePath;
	}

	/**
	 * @return pair of values, {@link Pair#first()} and {@link Pair#second()} are respective parents, never <code>null</code>.
	 * @throws HgInvalidControlFileException if attempt to read information about working copy parents from dirstate failed 
	 */
	public Pair<Nodeid,Nodeid> getWorkingCopyParents() throws HgInvalidControlFileException {
		return DirstateReader.readParents(impl);
	}
	
	/**
	 * @return name of the branch associated with working directory, never <code>null</code>.
	 * @throws HgInvalidControlFileException if attempt to read branch name failed.
	 */
	public String getWorkingCopyBranchName() throws HgInvalidControlFileException {
		if (wcBranch == null) {
			wcBranch = DirstateReader.readBranch(impl);
		}
		return wcBranch;
	}

	/**
	 * @return location where user files (shall) reside
	 */
	public File getWorkingDir() {
		return workingDir;
	}
	
	/**
	 * Provides access to sub-repositories defined in this repository. Enumerated  sub-repositories are those directly
	 * known, not recursive collection of all nested sub-repositories.
	 * @return list of all known sub-repositories in this repository, or empty list if none found.
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public List<HgSubrepoLocation> getSubrepositories() throws HgInvalidControlFileException {
		if (subRepos == null) {
			subRepos = new SubrepoManager(this);
			subRepos.read();
		}
		return subRepos.all();
	}

	
	/**
	 * Repository-specific configuration.
	 * @return access to configuration options, never <code>null</code>
	 */
	public HgRepoConfig getConfiguration() /* XXX throws HgInvalidControlFileException? Description of the exception suggests it is only for files under ./hg/*/ {
		if (repoConfig == null) {
			try {
				ConfigFile configFile = impl.readConfiguration();
				repoConfig = new HgRepoConfig(configFile);
			} catch (IOException ex) {
				String m = "Errors while reading user configuration file";
				getSessionContext().getLog().dump(getClass(), Warn, ex, m);
				return new HgRepoConfig(new ConfigFile(getSessionContext())); // empty config, do not cache, allow to try once again
				//throw new HgInvalidControlFileException(m, ex, null);
			}
		}
		return repoConfig;
	}

	// There seem to be no cases when access to HgDirstate is required from outside 
	// (guess, working dir/revision walkers may hide dirstate access and no public visibility needed)
	/*package-local*/ final HgDirstate loadDirstate(Path.Source pathFactory) throws HgInvalidControlFileException {
		PathRewrite canonicalPath = null;
		if (!impl.isCaseSensitiveFileSystem()) {
			canonicalPath = new PathRewrite() {

				public CharSequence rewrite(CharSequence path) {
					return path.toString().toLowerCase();
				}
			};
		}
		HgDirstate ds = new HgDirstate(impl, pathFactory, canonicalPath);
		ds.read();
		return ds;
	}

	/**
	 * Access to configured set of ignored files.
	 * @see HgIgnore#isIgnored(Path)
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgIgnore getIgnore() throws HgInvalidControlFileException {
		// TODO read config for additional locations
		if (ignore == null) {
			ignore = new HgIgnore(getToRepoPathHelper());
			ignore.read(impl);
		} else {
			ignore.reloadIfChanged(impl);
		}
		return ignore;
	}

	/**
	 * Mercurial saves message user has supplied for a commit to facilitate message re-use in case commit fails.
	 * This method provides this saved message.
	 *  
	 * @return message used for last commit attempt, or <code>null</code> if none
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public String getCommitLastMessage() throws HgInvalidControlFileException {
		File lastMessage = impl.getFileFromRepoDir(LastMessage.getPath());
		if (!lastMessage.canRead()) {
			return null;
		}
		FileReader fr = null;
		try {
			fr = new FileReader(lastMessage);
			CharBuffer cb = CharBuffer.allocate(Internals.ltoi(lastMessage.length()));
			fr.read(cb);
			return cb.flip().toString();
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Can't retrieve message of last commit attempt", ex, lastMessage);
		} finally {
			if (fr != null) {
				try {
					fr.close();
				} catch (IOException ex) {
					getSessionContext().getLog().dump(getClass(), Warn, "Failed to close %s after read", lastMessage);
				}
			}
		}
	}

	/**
	 * Access repository lock that covers non-store parts of the repository (dirstate, branches, etc - 
	 * everything that has to do with working directory state).
	 * 
	 * Note, the lock object returned merely gives access to lock mechanism. NO ACTUAL LOCKING IS DONE.
	 * Use {@link HgRepositoryLock#acquire()} to actually lock the repository.  
	 *   
	 * @return lock object, never <code>null</code>
	 */
	public HgRepositoryLock getWorkingDirLock() {
		if (wdLock == null) {
			int timeout = getLockTimeout();
			File lf = impl.getFileFromRepoDir("wlock");
			synchronized (this) {
				if (wdLock == null) {
					wdLock = new HgRepositoryLock(lf, timeout);
				}
			}
		}
		return wdLock;
	}

	/**
	 * Access repository lock that covers repository intrinsic files, unrelated to 
	 * the state of working directory
	 * @return lock object, never <code>null</code>
	 */
	public HgRepositoryLock getStoreLock() {
		if (storeLock == null) {
			int timeout = getLockTimeout();
			File fl = impl.getFileFromStoreDir("lock");
			synchronized (this) {
				if (storeLock == null) {
					storeLock = new HgRepositoryLock(fl, timeout);
				}
			}
		}
		return storeLock;
	}

	/**
	 * Access bookmarks-related functionality
	 * @return facility to manage bookmarks, never <code>null</code>
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgBookmarks getBookmarks() throws HgInvalidControlFileException {
		if (bookmarks == null) {
			bookmarks = new HgBookmarks(impl);
			bookmarks.read();
		} else {
			bookmarks.reloadIfChanged();
		}
		return bookmarks;
	}
	
	public HgExtensionsManager getExtensions() {
		if (extManager == null) {
			class EM extends HgExtensionsManager {
				EM() {
					super(HgRepository.this.getImplHelper());
				}
			}
			extManager = new EM();
		}
		return extManager;
	}

	/**
	 * @return session environment of the repository
	 */
	public SessionContext getSessionContext() {
		return sessionContext;
	}
	
	/*package-local*/ List<Filter> getFiltersFromRepoToWorkingDir(Path p) {
		return instantiateFilters(p, new Filter.Options(Filter.Direction.FromRepo));
	}

	/*package-local*/ List<Filter> getFiltersFromWorkingDirToRepo(Path p) {
		return instantiateFilters(p, new Filter.Options(Filter.Direction.ToRepo));
	}
	
	/*package-local*/ File getFile(HgDataFile dataFile) {
		return new File(getWorkingDir(), dataFile.getPath().toString());
	}
	
	/*package-local*/ Internals getImplHelper() {
		return impl;
	}

	private List<Filter> instantiateFilters(Path p, Filter.Options opts) {
		List<Filter.Factory> factories = impl.getFilters();
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

	private int getLockTimeout() {
		int cfgValue = getConfiguration().getIntegerValue("ui", "timeout", 600);
		if (getSessionContext().getConfigurationProperty(Internals.CFG_PROPERTY_FS_LOCK_TIMEOUT, null) != null) {
			return new PropertyMarshal(sessionContext).getInt(Internals.CFG_PROPERTY_FS_LOCK_TIMEOUT, cfgValue);
		}
		return cfgValue;
	}
}
