/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import static com.tmate.hgkit.ll.HgRepository.TIP;


/**
 * ? name:HgFileNode?
 * @author artem
 */
public class HgDataFile extends Revlog {

	// absolute from repo root?
	// slashes, unix-style?
	// repo location agnostic, just to give info to user, not to access real storage
	private final String path;
	
	/*package-local*/HgDataFile(HgRepository hgRepo, String path, RevlogStream content) {
		super(hgRepo, content);
		this.path = path;
	}
	
	public boolean exists() {
		return content != null; // XXX need better impl
	}

	public String getPath() {
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
