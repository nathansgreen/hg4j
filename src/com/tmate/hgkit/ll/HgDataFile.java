/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import static com.tmate.hgkit.ll.HgRepository.TIP;

/**
 * Extends Revlog/uses RevlogStream?
 * ? name:HgFileNode?
 * @author artem
 */
public class HgDataFile extends Revlog {

	private final RevlogStream content; // XXX move up to Revlog?

	// absolute from repo root?
	// slashes, unix-style?
	// repo location agnostic, just to give info to user, not to access real storage
	private final String path;
	
	/*package-local*/HgDataFile(HgRepository hgRepo, String path, RevlogStream content) {
		super(hgRepo);
		this.path = path;
		this.content = content;
	}
	
	public boolean exists() {
		return content != null; // XXX need better impl
	}

	public String getPath() {
		return path; // hgRepo.backresolve(this) -> name?
	}

	public int getRevisionCount() {
		return content.revisionCount();
	}

	public byte[] content() {
		return content(TIP);
	}
	
	public byte[] content(int revision) {
		final byte[][] dataPtr = new byte[1][];
		Revlog.Inspector insp = new Revlog.Inspector() {
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				dataPtr[0] = data;
			}
		};
		content.iterate(revision, revision, true, insp);
		return dataPtr[0];
	}

	public void history(Changeset.Inspector inspector) {
		if (!exists()) {
			throw new IllegalStateException("Can't get history of invalid repository file node"); 
		}
		final int[] commitRevisions = new int[content.revisionCount()];
		Revlog.Inspector insp = new Revlog.Inspector() {
			int count = 0;
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				commitRevisions[count++] = linkRevision;
			}
		};
		content.iterate(0, -1, false, insp);
		getRepo().getChangelog().range(inspector, commitRevisions);
	}

	public void history(int start, int end, Changeset.Inspector i) {
		throw HgRepository.notImplemented();
	}
}
