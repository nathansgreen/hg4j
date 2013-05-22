/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import java.util.BitSet;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * Represent indicators which revisions are descendants of the supplied root revision
 * This is sort of lightweight alternative to ParentWalker#childrenOf 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevisionDescendants {

	private final HgRepository repo;
	private final int rootRevIndex;
	private final int tipRevIndex; // this is the last revision we cache to
	private final BitSet descendants;

	// in fact, may be refactored to deal not only with changelog, but any revlog (not sure what would be the usecase, though)
	public RevisionDescendants(HgRepository hgRepo, int revisionIndex) throws HgRuntimeException {
		repo = hgRepo;
		rootRevIndex = revisionIndex;
		// even if tip moves, we still answer correctly for those isCandidate()
		tipRevIndex = repo.getChangelog().getLastRevision(); 
		if (revisionIndex < 0 || revisionIndex > tipRevIndex) {
			String m = "Revision to build descendants for shall be in range [%d,%d], not %d";
			throw new IllegalArgumentException(String.format(m, 0, tipRevIndex, revisionIndex));
		}
		descendants = new BitSet(tipRevIndex - rootRevIndex + 1);
	}
	
	public void build() throws HgRuntimeException {
		final BitSet result = descendants;
		result.set(0);
		if (rootRevIndex == tipRevIndex) {
			return;
		}
		repo.getChangelog().indexWalk(rootRevIndex+1, tipRevIndex, new HgChangelog.ParentInspector() {
			// TODO ParentRevisionInspector, with no parent nodeids, just indexes?

			private int i = 1; // above we start with revision next to rootRevIndex, which is at offset 0
			public void next(int revisionIndex, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2) {
				int p1x = parent1 - rootRevIndex;
				int p2x = parent2 - rootRevIndex;
				boolean p1IsDescendant = false, p2IsDescendant = false;
				if (p1x >= 0) { // parent1 is among descendants candidates
					assert p1x < result.size();
					p1IsDescendant = result.get(p1x);
				}
				if (p2x >= 0) {
					assert p2x < result.size();
					p2IsDescendant = result.get(p2x);
				}
				//
				int rx = revisionIndex - rootRevIndex;
				if (rx != i) {
					throw new HgInvalidStateException(String.format("Sanity check failed. Revision %d. Expected:%d, was:%d", revisionIndex, rx, i));
				}
				// current revision is descendant if any of its parents is descendant
				result.set(rx, p1IsDescendant || p2IsDescendant);
				i++;
			}
		});
	}

	// deliberately doesn't allow TIP
	public boolean isCandidate(int revIndex) {
		return (revIndex >= rootRevIndex && revIndex <= tipRevIndex) ;
	}

	public boolean hasDescendants() { // isEmpty is better name?
		// bit at rootRevIndex is always set
		return descendants.nextSetBit(rootRevIndex+1) != -1;
	}

	/**
	 * Tells whether specified revision is on a descent line from the root revision.
	 * <p>NOTE, root revision itself is considered to be its own descendant.
	 * 
	 * @param revisionIndex revision index to check, shall pass {@link #isCandidate(int)}
	 * @return <code>true</code> if revision is descendant of or is the same as root revision
	 */
	public boolean isDescendant(int revisionIndex) {
		assert isCandidate(revisionIndex);
		int ix = revisionIndex - rootRevIndex;
		assert ix < descendants.size();
		return descendants.get(ix);
	}
}
