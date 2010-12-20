/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

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

	private static final int TIP = -2;

	public byte[] content() {
		return content(TIP);
	}
	
	public byte[] content(int revision) {
		throw HgRepository.notImplemented();
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
