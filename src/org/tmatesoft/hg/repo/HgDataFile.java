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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;



/**
 * ? name:HgFileNode?
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgDataFile extends Revlog {

	// absolute from repo root?
	// slashes, unix-style?
	// repo location agnostic, just to give info to user, not to access real storage
	private final Path path;
	
	/*package-local*/HgDataFile(HgRepository hgRepo, Path path, RevlogStream content) {
		super(hgRepo, content);
		this.path = path;
	}
	
	public boolean exists() {
		return content != null; // XXX need better impl
	}

	public Path getPath() {
		return path; // hgRepo.backresolve(this) -> name?
	}

	public int length(Nodeid nodeid) {
		return content.dataLength(getLocalRevisionNumber(nodeid));
	}

	public byte[] content() {
		return content(TIP);
	}

	public void history(Changeset.Inspector inspector) {
		history(0, content.revisionCount() - 1, inspector);
	}

	public void history(int start, int end, Changeset.Inspector inspector) {
		if (!exists()) {
			throw new IllegalStateException("Can't get history of invalid repository file node"); 
		}
		final int[] commitRevisions = new int[end - start + 1];
		Revlog.Inspector insp = new Revlog.Inspector() {
			int count = 0;
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				commitRevisions[count++] = linkRevision;
			}
		};
		content.iterate(start, end, false, insp);
		getRepo().getChangelog().range(inspector, commitRevisions);
	}
}
