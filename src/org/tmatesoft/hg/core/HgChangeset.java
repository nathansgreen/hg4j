/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.util.Path;


/**
 * Record in the Mercurial changelog, describing single commit.
 * 
 * Not thread-safe, don't try to read from different threads
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgChangeset implements Cloneable {
	private final HgStatusCollector statusHelper;
	private final Path.Source pathHelper;

	private HgChangelog.ParentWalker parentHelper;

	//
	private RawChangeset changeset;
	private Nodeid nodeid;

	//
	private List<HgFileRevision> modifiedFiles, addedFiles;
	private List<Path> deletedFiles;
	private int revNumber;
	private byte[] parent1, parent2;

	// XXX consider CommandContext with StatusCollector, PathPool etc. Commands optionally get CC through a cons or create new
	// and pass it around
	/*package-local*/HgChangeset(HgStatusCollector statusCollector, Path.Source pathFactory) {
		statusHelper = statusCollector;
		pathHelper = pathFactory;
	}

	/*package-local*/ void init(int localRevNumber, Nodeid nid, RawChangeset rawChangeset) {
		revNumber = localRevNumber;
		nodeid = nid;
		changeset = rawChangeset.clone();
		modifiedFiles = addedFiles = null;
		deletedFiles = null;
		parent1 = parent2 = null;
		// keep references to parentHelper, statusHelper and pathHelper
	}

	/*package-local*/ void setParentHelper(HgChangelog.ParentWalker pw) {
		parentHelper = pw;
		if (parentHelper != null) {
			if (parentHelper.getRepo() != statusHelper.getRepo()) {
				throw new IllegalArgumentException();
			}
		}
	}

	/**
	 * Index of the changeset in local repository. Note, this number is relevant only for local repositories/operations, use 
	 * {@link Nodeid nodeid} to uniquely identify a revision.
	 *   
	 * @return index of the changeset revision
	 */
	public int getRevisionIndex() {
		return revNumber;
	}

	/**
	 * @deprecated use {@link #getRevisionIndex()}
	 */
	@Deprecated
	public int getRevision() {
		return revNumber;
	}

	/**
	 * Unique identity of this changeset revision
	 * @return revision identifier, never <code>null</code>
	 */
	public Nodeid getNodeid() {
		return nodeid;
	}

	/**
	 * Name of the user who made this commit
	 * @return author of the commit, never <code>null</code>
	 */
	public String getUser() {
		return changeset.user();
	}
	
	/**
	 * Commit description
	 * @return content of the corresponding field in changeset record; empty string if none specified.
	 */
	public String getComment() {
		return changeset.comment();
	}

	/**
	 * Name of the branch this commit was made in. Returns "default" for main branch.
	 * @return name of the branch, non-empty string
	 */
	public String getBranch() {
		return changeset.branch();
	}

	/**
	 * @return used to be String, now {@link HgDate}, use {@link HgDate#toString()} to get same result as before 
	 */
	public HgDate getDate() {
		return new HgDate(changeset.date().getTime(), changeset.timezone());
	}

	/**
	 * Indicates revision of manifest that tracks state of repository at the moment of this commit.
	 * Note, may be {@link Nodeid#NULL} in certain scenarios (e.g. first changeset in an empty repository, usually by bogus tools)
	 *  
	 * @return revision identifier, never <code>null</code>
	 */
	public Nodeid getManifestRevision() {
		return changeset.manifest();
	}

	/**
	 * Lists names of files affected by this commit, as recorded in the changeset itself. Unlike {@link #getAddedFiles()}, 
	 * {@link #getModifiedFiles()} and {@link #getRemovedFiles()}, this method doesn't analyze actual changes done 
	 * in the commit, rather extracts value from the changeset record.
	 * 
	 * List returned by this method may be empty, while aforementioned methods may produce non-empty result.
	 *   
	 * @return list of filenames, never <code>null</code>
	 */
	public List<Path> getAffectedFiles() {
		// reports files as recorded in changelog. Note, merge revisions may have no
		// files listed, and thus this method would return empty list, while
		// #getModifiedFiles() would return list with merged file(s) (because it uses status to get 'em, not
		// what #files() gives).
		ArrayList<Path> rv = new ArrayList<Path>(changeset.files().size());
		for (String name : changeset.files()) {
			rv.add(pathHelper.path(name));
		}
		return rv;
	}

	/**
	 * Figures out files and specific revisions thereof that were modified in this commit
	 *  
	 * @return revisions of files modified in this commit
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
 	 * @throws HgException in case of some other library issue 
	 */
	public List<HgFileRevision> getModifiedFiles() throws HgException {
		if (modifiedFiles == null) {
			initFileChanges();
		}
		return modifiedFiles;
	}

	/**
	 * Figures out files added in this commit
	 * 
	 * @return revisions of files added in this commit
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 * @throws HgException in case of some other library issue 
	 */
	public List<HgFileRevision> getAddedFiles() throws HgException {
		if (addedFiles == null) {
			initFileChanges();
		}
		return addedFiles;
	}

	/**
	 * Figures out files that were deleted as part of this commit
	 * 
	 * @return revisions of files deleted in this commit
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 * @throws HgException in case of some other library issue 
	 */
	public List<Path> getRemovedFiles() throws HgException {
		if (deletedFiles == null) {
			initFileChanges();
		}
		return deletedFiles;
	}

	public boolean isMerge() throws HgInvalidControlFileException {
		// p1 == -1 and p2 != -1 is legitimate case
		return !(getFirstParentRevision().isNull() || getSecondParentRevision().isNull()); 
	}
	
	/**
	 * @return never <code>null</code>
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 */
	public Nodeid getFirstParentRevision() throws HgInvalidControlFileException {
		if (parentHelper != null) {
			return parentHelper.safeFirstParent(nodeid);
		}
		// read once for both p1 and p2
		if (parent1 == null) {
			parent1 = new byte[20];
			parent2 = new byte[20];
			statusHelper.getRepo().getChangelog().parents(revNumber, new int[2], parent1, parent2);
		}
		return Nodeid.fromBinary(parent1, 0);
	}
	
	/**
	 * @return never <code>null</code>
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 */
	public Nodeid getSecondParentRevision() throws HgInvalidControlFileException {
		if (parentHelper != null) {
			return parentHelper.safeSecondParent(nodeid);
		}
		if (parent2 == null) {
			parent1 = new byte[20];
			parent2 = new byte[20];
			statusHelper.getRepo().getChangelog().parents(revNumber, new int[2], parent1, parent2);
		}
		return Nodeid.fromBinary(parent2, 0);
	}

	/**
	 * Create a copy of this changeset 
	 */
	@Override
	public HgChangeset clone() {
		try {
			HgChangeset copy = (HgChangeset) super.clone();
			// copy.changeset references this.changeset, doesn't need own copy
			return copy;
		} catch (CloneNotSupportedException ex) {
			throw new InternalError(ex.toString());
		}
	}

	private /*synchronized*/ void initFileChanges() throws HgException {
		ArrayList<Path> deleted = new ArrayList<Path>();
		ArrayList<HgFileRevision> modified = new ArrayList<HgFileRevision>();
		ArrayList<HgFileRevision> added = new ArrayList<HgFileRevision>();
		HgStatusCollector.Record r = new HgStatusCollector.Record();
		statusHelper.change(revNumber, r);
		final HgRepository repo = statusHelper.getRepo();
		for (Path s : r.getModified()) {
			Nodeid nid = r.nodeidAfterChange(s);
			if (nid == null) {
				throw new HgException(String.format("For the file %s recorded as modified couldn't find revision after change", s));
			}
			modified.add(new HgFileRevision(repo, nid, null, s, null));
		}
		final Map<Path, Path> copied = r.getCopied();
		for (Path s : r.getAdded()) {
			Nodeid nid = r.nodeidAfterChange(s);
			if (nid == null) {
				throw new HgException(String.format("For the file %s recorded as added couldn't find revision after change", s));
			}
			added.add(new HgFileRevision(repo, nid, null, s, copied.get(s)));
		}
		for (Path s : r.getRemoved()) {
			// with Path from getRemoved, may just copy
			deleted.add(s);
		}
		modified.trimToSize();
		added.trimToSize();
		deleted.trimToSize();
		modifiedFiles = Collections.unmodifiableList(modified);
		addedFiles = Collections.unmodifiableList(added);
		deletedFiles = Collections.unmodifiableList(deleted);
	}
}