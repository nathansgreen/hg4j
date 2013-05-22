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

import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;
import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;

import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * Piece of file history, identified by path, limited to file revisions from range [chop..init] of changesets, 
 * can be linked to another piece.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class FileRevisionHistoryChunk {
	private final HgDataFile df;
	// change ancestry, sequence of file revisions
	private IntVector fileRevsToVisit;
	// parent pairs of complete file history
	private IntVector fileParentRevs;
	// map file revision to changelog revision (sparse array, only file revisions to visit are set)
	private int[] file2changelog;
	private int originChangelogRev = BAD_REVISION, originFileRev = BAD_REVISION;
	private int csetRangeStart = NO_REVISION, csetRangeEnd = BAD_REVISION; 
	

	public FileRevisionHistoryChunk(HgDataFile file) {
		df = file;
	}
	
	/**
	 * @return file at this specific chunk of history (i.e. its path may be different from the paths of other chunks)
	 */
	public HgDataFile getFile() {
		return df;
	}
	
	/**
	 * @return changeset this file history chunk was chopped at, or {@link HgRepository#NO_REVISION} if none specified
	 */
	public int getStartChangeset() {
		return csetRangeStart;
	}
	
	/**
	 * @return changeset this file history chunk ends at
	 */
	public int getEndChangeset() {
		return csetRangeEnd;
	}
	
	public void init(int changelogRevisionIndex) throws HgRuntimeException {
		csetRangeEnd = changelogRevisionIndex;
		// XXX df.indexWalk(0, fileRevIndex, ) might be more effective
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(changelogRevisionIndex, df.getPath());
		int fileRevIndex = df.getRevisionIndex(fileRev);
		int[] fileRevParents = new int[2];
		fileParentRevs = new IntVector((fileRevIndex+1) * 2, 0);
		fileParentRevs.add(NO_REVISION, NO_REVISION); // parents of fileRevIndex == 0
		for (int i = 1; i <= fileRevIndex; i++) {
			df.parents(i, fileRevParents, null, null);
			fileParentRevs.add(fileRevParents[0], fileRevParents[1]);
		}
		// fileRevsToVisit keep file change ancestry from new to old
		fileRevsToVisit = new IntVector(fileRevIndex + 1, 0);
		// keep map of file revision to changelog revision
		file2changelog = new int[fileRevIndex+1];
		// only elements worth visit would get mapped, so there would be unfilled areas in the file2changelog,
		// prevent from error (make it explicit) by bad value
		Arrays.fill(file2changelog, BAD_REVISION);
		LinkedList<Integer> queue = new LinkedList<Integer>();
		BitSet seen = new BitSet(fileRevIndex + 1);
		queue.add(fileRevIndex);
		do {
			int x = queue.removeFirst();
			if (seen.get(x)) {
				continue;
			}
			seen.set(x);
			fileRevsToVisit.add(x);
			file2changelog[x] = df.getChangesetRevisionIndex(x);
			int p1 = fileParentRevs.get(2*x);
			int p2 = fileParentRevs.get(2*x + 1);
			if (p1 != NO_REVISION) {
				queue.addLast(p1);
			}
			if (p2 != NO_REVISION) {
				queue.addLast(p2);
			}
		} while (!queue.isEmpty());
		// make sure no child is processed before we handled all (grand-)parents of the element
		fileRevsToVisit.sort(false);
	}
	
	public void linkTo(FileRevisionHistoryChunk target) {
		// assume that target.init() has been called already 
		if (target == null) {
			return;
		}
		target.originFileRev = fileRevsToVisit.get(0); // files to visit are new to old
		target.originChangelogRev = changeset(target.originFileRev);
	}

	/**
	 * Mark revision closest(ceil) to specified as the very first one (no parents) 
	 */
	public void chopAtChangeset(int firstChangelogRevOfInterest) {
		csetRangeStart = firstChangelogRevOfInterest;
		if (firstChangelogRevOfInterest == 0) {
			return; // nothing to do
		}
		int i = 0, x = fileRevsToVisit.size(), fileRev = BAD_REVISION;
		// fileRevsToVisit is new to old, greater numbers to smaller
		while (i < x && changeset(fileRev = fileRevsToVisit.get(i)) >= firstChangelogRevOfInterest) {
			i++;
		}
		assert fileRev != BAD_REVISION; // there's at least 1 revision in fileRevsToVisit
		if (i == x && changeset(fileRev) != firstChangelogRevOfInterest) {
			assert false : "Requested changeset shall belong to the chunk";
			return;
		}
		fileRevsToVisit.trimTo(i); // no need to iterate more
		// pretend fileRev got no parents
		fileParentRevs.set(fileRev * 2, NO_REVISION);
		fileParentRevs.set(fileRev, NO_REVISION);
	}

	public int[] fileRevisions(HgIterateDirection iterateOrder) {
		// fileRevsToVisit is { r10, r7, r6, r5, r0 }, new to old
		int[] rv = fileRevsToVisit.toArray();
		if (iterateOrder == OldToNew) {
			// reverse return value
			for (int a = 0, b = rv.length-1; a < b; a++, b--) {
				int t = rv[b];
				rv[b] = rv[a];
				rv[a] = t;
			}
		}
		return rv;
	}
	
	/**
	 * @return number of file revisions in this chunk of its history
	 */
	public int revisionCount() {
		return fileRevsToVisit.size();
	}
	
	public int changeset(int fileRevIndex) {
		return file2changelog[fileRevIndex];
	}
	
	public void fillFileParents(int fileRevIndex, int[] fileParents) {
		if (fileRevIndex == 0 && originFileRev != BAD_REVISION) {
			// this chunk continues another file
			assert originFileRev != NO_REVISION;
			fileParents[0] = originFileRev;
			fileParents[1] = NO_REVISION;
			return;
		}
		fileParents[0] = fileParentRevs.get(fileRevIndex * 2);
		fileParents[1] = fileParentRevs.get(fileRevIndex * 2 + 1);
	}
	
	public void fillCsetParents(int fileRevIndex, int[] csetParents) {
		if (fileRevIndex == 0 && originFileRev != BAD_REVISION) {
			assert originFileRev != NO_REVISION;
			csetParents[0] = originChangelogRev;
			csetParents[1] = NO_REVISION; // I wonder if possible to start a copy with two parents?
			return;
		}
		int fp1 = fileParentRevs.get(fileRevIndex * 2);
		int fp2 = fileParentRevs.get(fileRevIndex * 2 + 1);
		csetParents[0] = fp1 == NO_REVISION ? NO_REVISION : changeset(fp1);
		csetParents[1] = fp2 == NO_REVISION ? NO_REVISION : changeset(fp2);
	}
}