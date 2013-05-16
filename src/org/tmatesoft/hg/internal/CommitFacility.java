/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.repo.HgRepository.DEFAULT_BRANCH_NAME;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepositoryFiles.Branch;
import static org.tmatesoft.hg.repo.HgRepositoryFiles.UndoBranch;
import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.HgRepositoryLockException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataSerializer.DataSource;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 * Name: CommitObject, FutureCommit or PendingCommit
 * The only public API now: {@link HgCommitCommand}.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class CommitFacility {
	private final Internals repo;
	private final int p1Commit, p2Commit;
	private Map<Path, Pair<HgDataFile, DataSource>> files = new LinkedHashMap<Path, Pair<HgDataFile, DataSource>>();
	private Set<Path> removals = new TreeSet<Path>();
	private String branch, user;

	public CommitFacility(Internals hgRepo, int parentCommit) {
		this(hgRepo, parentCommit, NO_REVISION);
	}
	
	public CommitFacility(Internals hgRepo, int parent1Commit, int parent2Commit) {
		repo = hgRepo;
		p1Commit = parent1Commit;
		p2Commit = parent2Commit;
		if (parent1Commit != NO_REVISION && parent1Commit == parent2Commit) {
			throw new IllegalArgumentException("Merging same revision is dubious");
		}
	}

	public boolean isMerge() {
		return p1Commit != NO_REVISION && p2Commit != NO_REVISION;
	}

	public void add(HgDataFile dataFile, DataSource content) {
		if (content == null) {
			throw new IllegalArgumentException();
		}
		removals.remove(dataFile.getPath());
		files.put(dataFile.getPath(), new Pair<HgDataFile, DataSource>(dataFile, content));
	}

	public void forget(HgDataFile dataFile) {
		files.remove(dataFile.getPath());
		removals.add(dataFile.getPath());
	}
	
	public void branch(String branchName) {
		branch = branchName;
	}
	
	public void user(String userName) {
		user = userName;
	}
	
	// this method doesn't roll transaction back in case of failure, caller's responsibility
	// this method expects repository to be locked, if needed
	public Nodeid commit(String message, Transaction transaction) throws HgIOException, HgRepositoryLockException {
		final HgChangelog clog = repo.getRepo().getChangelog();
		final int clogRevisionIndex = clog.getRevisionCount();
		ManifestRevision c1Manifest = new ManifestRevision(null, null);
		ManifestRevision c2Manifest = new ManifestRevision(null, null);
		final Nodeid p1Cset = p1Commit == NO_REVISION ? null : clog.getRevision(p1Commit);
		final Nodeid p2Cset = p2Commit == NO_REVISION ? null : clog.getRevision(p2Commit);
		if (p1Commit != NO_REVISION) {
			repo.getRepo().getManifest().walk(p1Commit, p1Commit, c1Manifest);
		}
		if (p2Commit != NO_REVISION) {
			repo.getRepo().getManifest().walk(p2Commit, p2Commit, c2Manifest);
		}
//		Pair<Integer, Integer> manifestParents = getManifestParents();
		Pair<Integer, Integer> manifestParents = new Pair<Integer, Integer>(c1Manifest.revisionIndex(), c2Manifest.revisionIndex());
		TreeMap<Path, Nodeid> newManifestRevision = new TreeMap<Path, Nodeid>();
		HashMap<Path, Pair<Integer, Integer>> fileParents = new HashMap<Path, Pair<Integer,Integer>>();
		for (Path f : c1Manifest.files()) {
			HgDataFile df = repo.getRepo().getFileNode(f);
			Nodeid fileKnownRev1 = c1Manifest.nodeid(f), fileKnownRev2;
			final int fileRevIndex1 = df.getRevisionIndex(fileKnownRev1);
			final int fileRevIndex2;
			if ((fileKnownRev2 = c2Manifest.nodeid(f)) != null) {
				// merged files
				fileRevIndex2 = df.getRevisionIndex(fileKnownRev2);
			} else {
				fileRevIndex2 = NO_REVISION;
			}
				
			fileParents.put(f, new Pair<Integer, Integer>(fileRevIndex1, fileRevIndex2));
			newManifestRevision.put(f, fileKnownRev1);
		}
		//
		// Forget removed
		for (Path p : removals) {
			newManifestRevision.remove(p);
		}
		//
		// Register new/changed
		LinkedHashMap<Path, RevlogStream> newlyAddedFiles = new LinkedHashMap<Path, RevlogStream>();
		ArrayList<Path> touchInDirstate = new ArrayList<Path>();
		for (Pair<HgDataFile, DataSource> e : files.values()) {
			HgDataFile df = e.first();
			DataSource bds = e.second();
			Pair<Integer, Integer> fp = fileParents.get(df.getPath());
			if (fp == null) {
				// NEW FILE
				fp = new Pair<Integer, Integer>(NO_REVISION, NO_REVISION);
			}
			RevlogStream contentStream;
			if (df.exists()) {
				contentStream = repo.getImplAccess().getStream(df);
			} else {
				contentStream = repo.createStoreFile(df.getPath());
				newlyAddedFiles.put(df.getPath(), contentStream);
				// FIXME df doesn't get df.content updated, and clients
				// that would attempt to access newly added file after commit would fail
				// (despite the fact the file is in there)
			}
			RevlogStreamWriter fileWriter = new RevlogStreamWriter(repo, contentStream, transaction);
			Nodeid fileRev = fileWriter.addRevision(bds, clogRevisionIndex, fp.first(), fp.second());
			newManifestRevision.put(df.getPath(), fileRev);
			touchInDirstate.add(df.getPath());
		}
		//
		// Manifest
		final ManifestEntryBuilder manifestBuilder = new ManifestEntryBuilder(repo.buildFileNameEncodingHelper());
		for (Map.Entry<Path, Nodeid> me : newManifestRevision.entrySet()) {
			manifestBuilder.add(me.getKey().toString(), me.getValue());
		}
		RevlogStreamWriter manifestWriter = new RevlogStreamWriter(repo, repo.getImplAccess().getManifestStream(), transaction);
		Nodeid manifestRev = manifestWriter.addRevision(manifestBuilder, clogRevisionIndex, manifestParents.first(), manifestParents.second());
		//
		// Changelog
		final ChangelogEntryBuilder changelogBuilder = new ChangelogEntryBuilder();
		changelogBuilder.setModified(files.keySet());
		changelogBuilder.branch(branch == null ? DEFAULT_BRANCH_NAME : branch);
		changelogBuilder.user(String.valueOf(user));
		changelogBuilder.manifest(manifestRev).comment(message);
		RevlogStreamWriter changelogWriter = new RevlogStreamWriter(repo, repo.getImplAccess().getChangelogStream(), transaction);
		Nodeid changesetRev = changelogWriter.addRevision(changelogBuilder, clogRevisionIndex, p1Commit, p2Commit);
		// TODO move fncache update to an external facility, along with dirstate and bookmark update
		if (!newlyAddedFiles.isEmpty() && repo.fncacheInUse()) {
			FNCacheFile fncache = new FNCacheFile(repo);
			for (Path p : newlyAddedFiles.keySet()) {
				fncache.addIndex(p);
				if (!newlyAddedFiles.get(p).isInlineData()) {
					fncache.addData(p);
				}
			}
			try {
				fncache.write();
			} catch (IOException ex) {
				// see comment above for fnchache.read()
				repo.getLog().dump(getClass(), Error, ex, "Failed to write fncache, error ignored");
			}
		}
		String oldBranchValue = DirstateReader.readBranch(repo);
		String newBranchValue = branch == null ? DEFAULT_BRANCH_NAME : branch;
		// TODO undo.dirstate and undo.branch as described in http://mercurial.selenic.com/wiki/FileFormats#undo..2A
		if (!oldBranchValue.equals(newBranchValue)) {
			File branchFile = transaction.prepare(repo.getRepositoryFile(Branch), repo.getRepositoryFile(UndoBranch));
			FileOutputStream fos = null;
			try {
				fos = new FileOutputStream(branchFile);
				fos.write(newBranchValue.getBytes(EncodingHelper.getUTF8()));
				fos.flush();
				fos.close();
				fos = null;
				transaction.done(branchFile);
			} catch (IOException ex) {
				transaction.failure(branchFile, ex);
				repo.getLog().dump(getClass(), Error, ex, "Failed to write branch information, error ignored");
			} finally {
				try {
					if (fos != null) {
						fos.close();
					}
				} catch (IOException ex) {
					repo.getLog().dump(getClass(), Error, ex, null);
				}
			}
		}
		// bring dirstate up to commit state, TODO share this code with HgAddRemoveCommand
		final DirstateBuilder dirstateBuilder = new DirstateBuilder(repo);
		dirstateBuilder.fillFrom(new DirstateReader(repo, new Path.SimpleSource()));
		for (Path p : removals) {
			dirstateBuilder.recordRemoved(p);
		}
		for (Path p : touchInDirstate) {
			dirstateBuilder.recordUncertain(p);
		}
		dirstateBuilder.parents(changesetRev, Nodeid.NULL);
		dirstateBuilder.serialize(transaction);
		// update bookmarks
		if (p1Commit != NO_REVISION || p2Commit != NO_REVISION) {
			repo.getRepo().getBookmarks().updateActive(p1Cset, p2Cset, changesetRev);
		}
		// TODO Revisit: might be reasonable to send out a "Repo changed" notification, to clear
		// e.g. cached branch, tags and so on, not to rely on file change detection methods?
		// The same notification might come useful once Pull is implemented
		return changesetRev;
	}
/*
	private Pair<Integer, Integer> getManifestParents() {
		return new Pair<Integer, Integer>(extractManifestRevisionIndex(p1Commit), extractManifestRevisionIndex(p2Commit));
	}

	private int extractManifestRevisionIndex(int clogRevIndex) {
		if (clogRevIndex == NO_REVISION) {
			return NO_REVISION;
		}
		RawChangeset commitObject = repo.getChangelog().range(clogRevIndex, clogRevIndex).get(0);
		Nodeid manifestRev = commitObject.manifest();
		if (manifestRev.isNull()) {
			return NO_REVISION;
		}
		return repo.getManifest().getRevisionIndex(manifestRev);
	}
*/
}
