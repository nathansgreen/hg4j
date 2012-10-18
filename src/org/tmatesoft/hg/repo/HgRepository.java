/*
 * Copyright (c) 2010-2012 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepositoryFiles.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.SoftReference;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Filter;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.internal.SubrepoManager;
import org.tmatesoft.hg.util.CancelledException;
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

	// temp aux marker method
	public static IllegalStateException notImplemented() {
		return new IllegalStateException("Not implemented");
	}
	
	private final File repoDir; // .hg folder
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

	// XXX perhaps, shall enable caching explicitly
	private final HashMap<Path, SoftReference<RevlogStream>> streamsCache = new HashMap<Path, SoftReference<RevlogStream>>();
	
	private final org.tmatesoft.hg.internal.Internals impl;
	private HgIgnore ignore;
	private HgRepoConfig repoConfig;
	
	/*
	 * TODO [post-1.0] move to a better place, e.g. WorkingCopy container that tracks both dirstate and branches 
	 * (and, perhaps, undo, lastcommit and other similar information), and is change listener so that we don't need to
	 * worry about this cached value become stale
	 */
	private String wcBranch;

	
	HgRepository(String repositoryPath) {
		repoDir = null;
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
		repoDir = repositoryRoot;
		workingDir = repoDir.getParentFile();
		if (workingDir == null) {
			throw new IllegalArgumentException(repoDir.toString());
		}
		repoLocation = repositoryPath;
		sessionContext = ctx;
		impl = new org.tmatesoft.hg.internal.Internals(this, repositoryRoot);
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
		return repoLocation;
	}

	public boolean isInvalid() {
		return impl == null || impl.isInvalid();
	}
	
	public HgChangelog getChangelog() {
		if (changelog == null) {
			File chlogFile = impl.getFileFromStoreDir("00changelog.i");
			if (!chlogFile.exists()) {
				// fake its existence
				chlogFile = fakeNonExistentFile(chlogFile);
			}
			RevlogStream content = new RevlogStream(impl.getDataAccess(), chlogFile);
			changelog = new HgChangelog(this, content);
		}
		return changelog;
	}
	
	public HgManifest getManifest() {
		if (manifest == null) {
			File manifestFile = impl.getFileFromStoreDir("00manifest.i");
			if (!manifestFile.exists()) {
				manifestFile = fakeNonExistentFile(manifestFile);
			}
			RevlogStream content = new RevlogStream(impl.getDataAccess(), manifestFile);
			manifest = new HgManifest(this, content, impl.buildFileNameEncodingHelper());
		}
		return manifest;
	}
	
	/**
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgTags getTags() throws HgInvalidControlFileException {
		if (tags == null) {
			tags = new HgTags(this);
			HgDataFile hgTags = getFileNode(HgTags.getPath());
			if (hgTags.exists()) {
				for (int i = 0; i <= hgTags.getLastRevision(); i++) { // TODO post-1.0 in fact, would be handy to have walk(start,end) 
					// method for data files as well, though it looks odd.
					try {
						ByteArrayChannel sink = new ByteArrayChannel();
						hgTags.content(i, sink);
						final String content = new String(sink.toArray(), "UTF8");
						tags.readGlobal(new StringReader(content));
					} catch (CancelledException ex) {
						 // IGNORE, can't happen, we did not configure cancellation
						getSessionContext().getLog().dump(getClass(), Debug, ex, null);
					} catch (IOException ex) {
						// UnsupportedEncodingException can't happen (UTF8)
						// only from readGlobal. Need to reconsider exceptions thrown from there:
						// BufferedReader wraps String and unlikely to throw IOException, perhaps, log is enough?
						getSessionContext().getLog().dump(getClass(), Error, ex, null);
						// XXX need to decide what to do this. failure to read single revision shall not break complete cycle
					}
				}
			}
			File file2read = null;
			try {
				file2read = new File(getWorkingDir(), HgTags.getPath());
				tags.readGlobal(file2read); // XXX replace with HgDataFile.workingCopy
				file2read = impl.getFileFromRepoDir(HgLocalTags.getName()); // XXX pass internalrepo to readLocal, keep filename there
				tags.readLocal(file2read);
			} catch (IOException ex) {
				getSessionContext().getLog().dump(getClass(), Error, ex, null);
				throw new HgInvalidControlFileException("Failed to read tags", ex, file2read);
			}
		}
		return tags;
	}
	
	/**
	 * Access branch information
	 * @return branch manager instance, never <code>null</code>
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgBranches getBranches() throws HgInvalidControlFileException {
		if (branches == null) {
			branches = new HgBranches(impl);
			branches.collect(ProgressSupport.Factory.get(null));
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
		Path p = Path.create(nPath);
		return getFileNode(p);
	}

	public HgDataFile getFileNode(Path path) {
		RevlogStream content = resolveStoreFile(path);
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
		return HgDirstate.readParents(impl);
	}
	
	/**
	 * @return name of the branch associated with working directory, never <code>null</code>.
	 * @throws HgInvalidControlFileException if attempt to read branch name failed.
	 */
	public String getWorkingCopyBranchName() throws HgInvalidControlFileException {
		if (wcBranch == null) {
			wcBranch = HgDirstate.readBranch(impl);
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

	// XXX package-local, unless there are cases when required from outside (guess, working dir/revision walkers may hide dirstate access and no public visibility needed)
	// XXX consider passing Path pool or factory to produce (shared) Path instead of Strings
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
			File ignoreFile = new File(getWorkingDir(), HgIgnore.getPath());
			try {
				final List<String> errors = ignore.read(ignoreFile);
				if (errors != null) {
					getSessionContext().getLog().dump(getClass(), Warn, "Syntax errors parsing %s:\n%s", ignoreFile.getName(), Internals.join(errors, ",\n"));
				}
			} catch (IOException ex) {
				final String m = String.format("Error reading %s file", ignoreFile);
				throw new HgInvalidControlFileException(m, ex, ignoreFile);
			}
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

	private HgRepositoryLock wdLock, storeLock;

	/**
	 * PROVISIONAL CODE, DO NOT USE
	 * 
	 * Access repository lock that covers non-store parts of the repository (dirstate, branches, etc - 
	 * everything that has to do with working directory state).
	 * 
	 * Note, the lock object returned merely gives access to lock mechanism. NO ACTUAL LOCKING IS DONE.
	 * Use {@link HgRepositoryLock#acquire()} to actually lock the repository.  
	 *   
	 * @return lock object, never <code>null</code>
	 */
	@Experimental(reason="WORK IN PROGRESS")
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

	@Experimental(reason="WORK IN PROGRESS")
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
		}
		return bookmarks;
	}

	/**
	 * @return session environment of the repository
	 */
	public SessionContext getSessionContext() {
		return sessionContext;
	}

	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 * @param path - normalized file name
	 * @return <code>null</code> if path doesn't resolve to a existing file
	 */
	/*package-local*/ RevlogStream resolveStoreFile(Path path) {
		final SoftReference<RevlogStream> ref = streamsCache.get(path);
		RevlogStream cached = ref == null ? null : ref.get();
		if (cached != null) {
			return cached;
		}
		File f = impl.getFileFromDataDir(path);
		if (f.exists()) {
			RevlogStream s = new RevlogStream(impl.getDataAccess(), f);
			if (impl.shallCacheRevlogs()) {
				streamsCache.put(path, new SoftReference<RevlogStream>(s));
			}
			return s;
		}
		return null;
	}
	
	private File fakeNonExistentFile(File expected) throws HgInvalidFileException {
		try {
			File fake = File.createTempFile(expected.getName(), null);
			fake.deleteOnExit();
			return fake;
		} catch (IOException ex) {
			getSessionContext().getLog().dump(getClass(), Info, ex, null);
			throw new HgInvalidFileException(String.format("Failed to fake existence of file %s", expected), ex);
		}
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
		return getConfiguration().getIntegerValue("ui", "timeout", 600);
	}
}
