/**
 * Copyright (c) 2010 Artem Tikhomirov
 */
package com.tmate.hgkit.ll;

import java.io.DataInput;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representation of the Mercurial changelog file (list of ChangeSets)
 * @author artem
 */
public class Changelog extends Revlog {

	private RevlogStream content;

	/*package-local*/ Changelog(HgRepository hgRepo) {
		super(hgRepo);
		content = hgRepo.resolve(".hg/store/00changelog.i");
	}

	public List<Changeset> all() {
		throw HgRepository.notImplemented();
	}
	
	public void all(Changeset.Callback callback) {
		throw HgRepository.notImplemented();
	}

	public List<Changeset> range(int start, int end) {
		//read from outline[start].start .. (outline[end].start + outline[end].length)
		// parse changesets
		final ArrayList<Changeset> rv = new ArrayList<Changeset>(end - start + 1);
		Revlog.Inspector i = new Revlog.Inspector() {
			
			public void next(int compressedLen, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				// TODO Auto-generated method stub
				Changeset.parse(data);
				i.add();
				throw HgRepository.notImplemented();
			}
		};
		content.iterate(start, end, true, i);
		return rv; 
	}
}
