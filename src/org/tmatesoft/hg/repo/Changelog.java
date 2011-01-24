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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.RevlogStream;


/**
 * Representation of the Mercurial changelog file (list of ChangeSets)
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Changelog extends Revlog {

	/*package-local*/ Changelog(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content);
	}

	public void all(final Changeset.Inspector inspector) {
		range(0, content.revisionCount() - 1, inspector);
	}

	public void range(int start, int end, final Changeset.Inspector inspector) {
		RevlogStream.Inspector i = new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				Changeset cset = Changeset.parse(data, 0, data.length);
				// XXX there's no guarantee for Changeset.Callback that distinct instance comes each time, consider instance reuse
				inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
			}
		};
		content.iterate(start, end, true, i);
	}

	public List<Changeset> range(int start, int end) {
		final ArrayList<Changeset> rv = new ArrayList<Changeset>(end - start + 1);
		RevlogStream.Inspector i = new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
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
		RevlogStream.Inspector i = new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				if (Arrays.binarySearch(revisions, revisionNumber) >= 0) {
					Changeset cset = Changeset.parse(data, 0, data.length);
					inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
				}
			}
		};
		Arrays.sort(revisions);
		content.iterate(revisions[0], revisions[revisions.length - 1], true, i);
	}
}
