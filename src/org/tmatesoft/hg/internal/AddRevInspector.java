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

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgBundle.GroupElement;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Pair;

/**
 * FIXME pretty much alike HgCloneCommand.WriteDownMate, shall converge
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class AddRevInspector implements HgBundle.Inspector {
	private final Internals repo;
	private final Transaction tr;
	private final FNCacheFile.Mediator fncache;
	private Set<Nodeid> added;
	private RevlogStreamWriter revlog;
	private RevMap clogRevs;
	private RevMap revlogRevs;
	private HgDataFile fileNode;
	private boolean newFile = false;

	public AddRevInspector(Internals implRepo, Transaction transaction) {
		repo = implRepo;
		tr = transaction;
		fncache = new FNCacheFile.Mediator(implRepo);
	}

	public void changelogStart() throws HgRuntimeException {
		RevlogStream rs = repo.getImplAccess().getChangelogStream();
		revlog = new RevlogStreamWriter(repo, rs, tr);
		revlogRevs = clogRevs = new RevMap(rs);
	}

	public void changelogEnd() throws HgRuntimeException {
		revlog = null;
		revlogRevs = null;
		added = clogRevs.added();
	}

	public void manifestStart() throws HgRuntimeException {
		RevlogStream rs = repo.getImplAccess().getManifestStream();
		revlog = new RevlogStreamWriter(repo, rs, tr);
		revlogRevs = new RevMap(rs);
	}

	public void manifestEnd() throws HgRuntimeException {
		revlog = null;
		revlogRevs = null;
	}

	public void fileStart(String name) throws HgRuntimeException {
		fileNode = repo.getRepo().getFileNode(name);
		newFile = !fileNode.exists();
		RevlogStream rs = repo.getImplAccess().getStream(fileNode);
		revlog = new RevlogStreamWriter(repo, rs, tr);
		revlogRevs = new RevMap(rs);
	}

	public void fileEnd(String name) throws HgRuntimeException {
		if (newFile) {
			fncache.registerNew(fileNode.getPath(), revlog.getRevlogStream());
		}
		revlog = null;
		revlogRevs = null;
	}

	public boolean element(GroupElement ge) throws HgRuntimeException {
		assert clogRevs != null;
		assert revlogRevs != null;
		if (revlog.getRevlogStream().findRevisionIndex(ge.node()) != HgRepository.BAD_REVISION) {
			// HgRemoteRepository.getChanges(common) builds a bundle that includes these common
			// revisions. Hence, shall not add these common (i.e. known locally) revisions
			// once again
			return true;
		}
		try {
			Pair<Integer, Nodeid> newRev = revlog.addPatchRevision(ge, clogRevs, revlogRevs);
			revlogRevs.update(newRev.first(), newRev.second());
			return true;
		} catch (HgIOException ex) {
			throw new HgInvalidControlFileException(ex, true);
		}
	}

	public RevisionSet addedChangesets() {
		return new RevisionSet(added);
	}
	
	public void done() throws IOException {
		fncache.complete();
	}

	private static class RevMap implements RevlogStreamWriter.RevisionToIndexMap {
		
		private final RevlogStream revlog;
		private HashMap<Nodeid, Integer> added = new HashMap<Nodeid, Integer>();

		public RevMap(RevlogStream revlogStream) {
			revlog = revlogStream;
		}

		public int revisionIndex(Nodeid revision) {
			Integer a = added.get(revision);
			if (a != null) {
				return a;
			}
			int f = revlog.findRevisionIndex(revision);
			return f == HgRepository.BAD_REVISION ? HgRepository.NO_REVISION : f;
		}
		
		public void update(Integer revIndex, Nodeid rev) {
			added.put(rev, revIndex);
		}
		
		Set<Nodeid> added() {
			return added.keySet();
		}
	}
}