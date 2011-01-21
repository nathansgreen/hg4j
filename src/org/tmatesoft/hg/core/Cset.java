/*
 * Copyright (c) 2011 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.core.LogCommand.FileRevision;
import org.tmatesoft.hg.util.PathPool;

import com.tmate.hgkit.ll.Changeset;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;
import com.tmate.hgkit.ll.StatusCollector;

/**
 * TODO rename to Changeset along with original Changeset moved to .repo and renamed to HgChangeset?
 * Not thread-safe, don't try to read from different threads
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Cset implements Cloneable {
	private final StatusCollector statusHelper;
	private final PathPool pathHelper;

	//
	private Changeset changeset;
	private Nodeid nodeid;

	//
	private List<FileRevision> modifiedFiles, addedFiles;
	private List<Path> deletedFiles;
	private int revNumber;

	// XXX consider CommandContext with StatusCollector, PathPool etc. Commands optionally get CC through a cons or create new
	// and pass it around
	/*package-local*/Cset(StatusCollector statusCollector, PathPool pathPool) {
		statusHelper = statusCollector;
		pathHelper = pathPool;
	}
	
	/*package-local*/
	void init(int localRevNumber, Nodeid nid, Changeset rawChangeset) {
		revNumber = localRevNumber;
		nodeid = nid;
		changeset = rawChangeset;
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
	public String getDate() {
		return changeset.dateString();
	}

	public List<Path> getAffectedFiles() {
		ArrayList<Path> rv = new ArrayList<Path>(changeset.files().size());
		for (String name : changeset.files()) {
			rv.add(pathHelper.path(name));
		}
		return rv;
	}

	public List<FileRevision> getModifiedFiles() {
		if (modifiedFiles == null) {
			initFileChanges();
		}
		return modifiedFiles;
	}

	public List<FileRevision> getAddedFiles() {
		if (addedFiles == null) {
			initFileChanges();
		}
		return addedFiles;
	}

	public List<Path> getRemovedFiles() {
		if (deletedFiles == null) {
			initFileChanges();
		}
		return deletedFiles;
	}

	@Override
	public Cset clone() {
		try {
			Cset copy = (Cset) super.clone();
			copy.changeset = changeset.clone();
			return copy;
		} catch (CloneNotSupportedException ex) {
			throw new InternalError(ex.toString());
		}
	}

	private /*synchronized*/ void initFileChanges() {
		ArrayList<Path> deleted = new ArrayList<Path>();
		ArrayList<FileRevision> modified = new ArrayList<FileRevision>();
		ArrayList<FileRevision> added = new ArrayList<FileRevision>();
		StatusCollector.Record r = new StatusCollector.Record();
		statusHelper.change(revNumber, r);
		final HgRepository repo = statusHelper.getRepo();
		for (String s : r.getModified()) {
			Path p = pathHelper.path(s);
			Nodeid nid = r.nodeidAfterChange(s);
			if (nid == null) {
				throw new IllegalArgumentException();
			}
			modified.add(new FileRevision(repo, nid, p));
		}
		for (String s : r.getAdded()) {
			Path p = pathHelper.path(s);
			Nodeid nid = r.nodeidAfterChange(s);
			if (nid == null) {
				throw new IllegalArgumentException();
			}
			added.add(new FileRevision(repo, nid, p));
		}
		for (String s : r.getRemoved()) {
			deleted.add(pathHelper.path(s));
		}
		modified.trimToSize();
		added.trimToSize();
		deleted.trimToSize();
		modifiedFiles = Collections.unmodifiableList(modified);
		addedFiles = Collections.unmodifiableList(added);
		deletedFiles = Collections.unmodifiableList(deleted);
	}
}