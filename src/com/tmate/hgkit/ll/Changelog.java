/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov
 */
package com.tmate.hgkit.ll;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.tmate.hgkit.fs.DataAccess;

/**
 * Representation of the Mercurial changelog file (list of ChangeSets)
 * @author artem
 */
public class Changelog extends Revlog {

	/*package-local*/ Changelog(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content);
	}

	public void all(final Changeset.Inspector inspector) {
		range(0, content.revisionCount() - 1, inspector);
	}

	public void range(int start, int end, final Changeset.Inspector inspector) {
		Revlog.Inspector i = new Revlog.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
				byte[] data = da.byteArray();
				Changeset cset = Changeset.parse(data, 0, data.length);
				// XXX there's no guarantee for Changeset.Callback that distinct instance comes each time, consider instance reuse
				inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
			}
		};
		content.iterate(start, end, true, i);
	}

	public List<Changeset> range(int start, int end) {
		final ArrayList<Changeset> rv = new ArrayList<Changeset>(end - start + 1);
		Revlog.Inspector i = new Revlog.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
				byte[] data = da.byteArray();
				Changeset cset = Changeset.parse(data, 0, data.length);
				rv.add(cset);
			}
		};
		content.iterate(start, end, true, i);
		return rv; 
	}

	public void range(final Changeset.Inspector inspector, final int... revisions) {
		if (revisions == null || revisions.length == 0) {
			return;
		}
		Revlog.Inspector i = new Revlog.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
				if (Arrays.binarySearch(revisions, revisionNumber) >= 0) {
					byte[] data = da.byteArray();
					Changeset cset = Changeset.parse(data, 0, data.length);
					inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
				}
			}
		};
		Arrays.sort(revisions);
		content.iterate(revisions[0], revisions[revisions.length - 1], true, i);
	}
}
