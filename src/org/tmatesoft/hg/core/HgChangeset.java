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

import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgPhase;
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

	// these get initialized
	private RawChangeset changeset;
	private int revNumber;
	private Nodeid nodeid;

	class ShareDataStruct {
		ShareDataStruct(HgStatusCollector statusCollector, Path.Source pathFactory) {
			statusHelper = statusCollector;
			pathHelper = pathFactory;
		}
		public final HgStatusCollector statusHelper;
		public final Path.Source pathHelper;

		public HgChangelog.ParentWalker parentHelper;
		public PhasesHelper phaseHelper;
	};

	// Helpers/utilities shared among few instances of HgChangeset
	private final ShareDataStruct shared;

	// these are built on demand
	private List<HgFileRevision> modifiedFiles, addedFiles;
	private List<Path> deletedFiles;
	private byte[] parent1, parent2;
	

	// XXX consider CommandContext with StatusCollector, PathPool etc. Commands optionally get CC through a cons or create new
	// and pass it around
	/*package-local*/HgChangeset(HgStatusCollector statusCollector, Path.Source pathFactory) {
		shared = new ShareDataStruct(statusCollector, pathFactory);
	}

	/*package-local*/ void init(int localRevNumber, Nodeid nid, RawChangeset rawChangeset) {
		revNumber = localRevNumber;
		nodeid = nid;
		changeset = rawChangeset.clone();
		modifiedFiles = addedFiles = null;
		deletedFiles = null;
		parent1 = parent2 = null;
		// keep references to shared (and everything in there: parentHelper, statusHelper, phaseHelper and pathHelper)
	}

	/*package-local*/ void setParentHelper(HgChangelog.ParentWalker pw) {
		if (pw != null) {
			if (pw.getRepo() != shared.statusHelper.getRepo()) {
				throw new IllegalArgumentException();
			}
		}
		shared.parentHelper = pw;
	}

	public int getRevision() {
		return revNumber;
	}
	public Nodeid getNodeid() {
		return nodeid;
	}
	public String getUser() {
		return changeset.user();
	}
	public String getComment() {
		return changeset.comment();
	}
	public String getBranch() {
		return changeset.branch();
	}

	/**
	 * @return used to be String, now {@link HgDate}, use {@link HgDate#toString()} to get same result as before 
	 */
	public HgDate getDate() {
		return new HgDate(changeset.date().getTime(), changeset.timezone());
	}
	public Nodeid getManifestRevision() {
		return changeset.manifest();
	}

	public List<Path> getAffectedFiles() {
		// reports files as recorded in changelog. Note, merge revisions may have no
		// files listed, and thus this method would return empty list, while
		// #getModifiedFiles() would return list with merged file(s) (because it uses status to get 'em, not
		// what #files() gives).
		ArrayList<Path> rv = new ArrayList<Path>(changeset.files().size());
		for (String name : changeset.files()) {
			rv.add(shared.pathHelper.path(name));
		}
		return rv;
	}

	public List<HgFileRevision> getModifiedFiles() throws HgInvalidControlFileException {
		if (modifiedFiles == null) {
			initFileChanges();
		}
		return modifiedFiles;
	}

	public List<HgFileRevision> getAddedFiles() throws HgInvalidControlFileException {
		if (addedFiles == null) {
			initFileChanges();
		}
		return addedFiles;
	}

	public List<Path> getRemovedFiles() throws HgInvalidControlFileException {
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
		if (shared.parentHelper != null) {
			return shared.parentHelper.safeFirstParent(nodeid);
		}
		// read once for both p1 and p2
		if (parent1 == null) {
			parent1 = new byte[20];
			parent2 = new byte[20];
			getRepo().getChangelog().parents(revNumber, new int[2], parent1, parent2);
		}
		return Nodeid.fromBinary(parent1, 0);
	}
	
	/**
	 * @return never <code>null</code>
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 */
	public Nodeid getSecondParentRevision() throws HgInvalidControlFileException {
		if (shared.parentHelper != null) {
			return shared.parentHelper.safeSecondParent(nodeid);
		}
		if (parent2 == null) {
			parent1 = new byte[20];
			parent2 = new byte[20];
			getRepo().getChangelog().parents(revNumber, new int[2], parent1, parent2);
		}
		return Nodeid.fromBinary(parent2, 0);
	}
	
	/**
	 * Tells the phase this changeset belongs to.
	 * @return one of {@link HgPhase} values
	 */
	public HgPhase getPhase() throws HgInvalidControlFileException {
		if (shared.phaseHelper == null) {
			// XXX would be handy to obtain ProgressSupport (perhaps, from statusHelper?)
			// and pass it to #init(), so that  there could be indication of file being read and cache being built
			synchronized (shared) {
				// ensure field is initialized only once 
				if (shared.phaseHelper == null) {
					shared.phaseHelper = new PhasesHelper(getRepo(), shared.parentHelper);
				}
			}
		}
		return shared.phaseHelper.getPhase(this);
	}

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
	
	private HgRepository getRepo() {
		return shared.statusHelper.getRepo();
	}

	private /*synchronized*/ void initFileChanges() throws HgInvalidControlFileException {
		ArrayList<Path> deleted = new ArrayList<Path>();
		ArrayList<HgFileRevision> modified = new ArrayList<HgFileRevision>();
		ArrayList<HgFileRevision> added = new ArrayList<HgFileRevision>();
		HgStatusCollector.Record r = new HgStatusCollector.Record();
		shared.statusHelper.change(revNumber, r);
		final HgRepository repo = getRepo();
		for (Path s : r.getModified()) {
			Nodeid nid = r.nodeidAfterChange(s);
			if (nid == null) {
				throw new HgBadStateException();
			}
			modified.add(new HgFileRevision(repo, nid, s, null));
		}
		final Map<Path, Path> copied = r.getCopied();
		for (Path s : r.getAdded()) {
			Nodeid nid = r.nodeidAfterChange(s);
			if (nid == null) {
				throw new HgBadStateException();
			}
			added.add(new HgFileRevision(repo, nid, s, copied.get(s)));
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