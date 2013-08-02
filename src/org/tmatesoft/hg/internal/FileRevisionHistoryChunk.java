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
	// parent pairs of complete file history, index offset by fileRevFrom
	private IntVector fileParentRevs;
	// map file revision to changelog revision (sparse array, only file revisions to visit are set), index offset by fileRevFrom
	private int[] file2changelog;
	private int originChangelogRev = BAD_REVISION, originFileRev = BAD_REVISION;
	private final int csetFrom, csetTo; 
	private final int fileRevFrom, fileRevTo;
	

	public FileRevisionHistoryChunk(HgDataFile file, int csetStart, int csetEnd, int fileStart, int fileEnd) {
		assert fileEnd >= fileStart;
		df = file;
		csetFrom = csetStart;
		csetTo = csetEnd;
		fileRevFrom = fileStart;
		fileRevTo = fileEnd;
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
		return csetFrom;
	}
	
	/**
	 * @return changeset this file history chunk ends at
	 */
	public int getEndChangeset() {
		return csetTo;
	}
	
	public void init() throws HgRuntimeException {
		int[] fileRevParents = new int[2];
		final int totalFileRevs = fileRevTo - fileRevFrom + 1;
		fileParentRevs = new IntVector(totalFileRevs * 2, 0);
		// pretend parents of fileRevStart are not set, regardless of actual state as we are not going to visit them anyway
		fileParentRevs.add(NO_REVISION, NO_REVISION); 
		// XXX df.indexWalk(fileRevStart, fileRevEnd, ) might be more effective
		for (int i = fileRevFrom+1; i <= fileRevTo; i++) {
			df.parents(i, fileRevParents, null, null);
			fileParentRevs.add(fileRevParents[0], fileRevParents[1]);
		}
		// fileRevsToVisit keep file change ancestry from new to old
		fileRevsToVisit = new IntVector(totalFileRevs, 0);
		// keep map of file revision to changelog revision
		file2changelog = new int[totalFileRevs];
		// only elements worth visit would get mapped, so there would be unfilled areas in the file2changelog,
		// prevent from error (make it explicit) by bad value
		Arrays.fill(file2changelog, BAD_REVISION);
		LinkedList<Integer> queue = new LinkedList<Integer>();
		BitSet seen = new BitSet(totalFileRevs);
		queue.add(fileRevTo);
		do {
			int fileRev = queue.removeFirst();
			int offFileRev = fileRev - fileRevFrom;
			if (seen.get(offFileRev)) {
				continue;
			}
			seen.set(offFileRev);
			int csetRev = df.getChangesetRevisionIndex(fileRev);
			if (csetRev < csetFrom || csetRev > csetTo) {
				continue;
			}
			fileRevsToVisit.add(fileRev);

			file2changelog[offFileRev] = csetRev;
			int p1 = fileParentRevs.get(2*offFileRev);
			int p2 = fileParentRevs.get(2*offFileRev + 1);
			if (p1 != NO_REVISION && p1 >= fileRevFrom) {
				queue.addLast(p1);
			}
			if (p2 != NO_REVISION && p2 >= fileRevFrom) {
				queue.addLast(p2);
			}
		} while (!queue.isEmpty());
		// make sure no child is processed before we handled all (grand-)parents of the element
		fileRevsToVisit.sort(false);
	}
	
	public void linkTo(FileRevisionHistoryChunk next) {
		// assume that init() has been called already 
		if (next == null) {
			return;
		}
		next.originFileRev = fileRevsToVisit.get(0); // files to visit are new to old
		next.originChangelogRev = changeset(next.originFileRev);
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
		return file2changelog[fileRevIndex - fileRevFrom];
	}
	
	public void fillFileParents(int fileRevIndex, int[] fileParents) {
		if (fileRevIndex == fileRevFrom && originFileRev != BAD_REVISION) {
			// this chunk continues another file
			assert originFileRev != NO_REVISION;
			fileParents[0] = originFileRev;
			fileParents[1] = NO_REVISION;
			return;
		}
		int x = fileRevIndex - fileRevFrom;
		fileParents[0] = fileParentRevs.get(x * 2);
		fileParents[1] = fileParentRevs.get(x * 2 + 1);
	}
	
	public void fillCsetParents(int fileRevIndex, int[] csetParents) {
		if (fileRevIndex == fileRevFrom && originFileRev != BAD_REVISION) {
			assert originChangelogRev != NO_REVISION;
			csetParents[0] = originChangelogRev;
			csetParents[1] = NO_REVISION; // I wonder if possible to start a copy with two parents?
			return;
		}
		int x = fileRevIndex - fileRevFrom;
		int fp1 = fileParentRevs.get(x * 2);
		int fp2 = fileParentRevs.get(x * 2 + 1);
		csetParents[0] = fp1 == NO_REVISION ? NO_REVISION : changeset(fp1);
		csetParents[1] = fp2 == NO_REVISION ? NO_REVISION : changeset(fp2);
	}
}