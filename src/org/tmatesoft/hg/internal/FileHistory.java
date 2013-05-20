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

import static org.tmatesoft.hg.core.HgIterateDirection.NewToOld;

import java.util.Collections;
import java.util.LinkedList;

import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * History of a file, with copy/renames, and corresponding revision information.
 * Facility for file history iteration. 
 * 
 * TODO [post-1.1] Utilize in HgLogCommand and anywhere else we need to follow file history
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileHistory {
	
	private LinkedList<FileRevisionHistoryChunk> fileCompleteHistory = new LinkedList<FileRevisionHistoryChunk>();
	private final HgDataFile df;
	private final int csetTo;
	private final int csetFrom;
	
	public FileHistory(HgDataFile file, int fromChangeset, int toChangeset) {
		df = file;
		csetFrom = fromChangeset;
		csetTo = toChangeset;
	}
	
	public int getStartChangeset() {
		return csetFrom;
	}
	
	public int getEndChangeset() {
		return csetTo;
	}

	public void build() {
		assert fileCompleteHistory.isEmpty();
		HgDataFile currentFile = df;
		final int changelogRevIndexEnd = csetTo;
		final int changelogRevIndexStart = csetFrom;
		int fileLastClogRevIndex = changelogRevIndexEnd;
		FileRevisionHistoryChunk nextChunk = null;
		fileCompleteHistory.clear(); // just in case, #build() is not expected to be called more than once
		do {
			FileRevisionHistoryChunk fileHistory = new FileRevisionHistoryChunk(currentFile);
			fileHistory.init(fileLastClogRevIndex);
			fileHistory.linkTo(nextChunk);
			fileCompleteHistory.addFirst(fileHistory); // to get the list in old-to-new order
			nextChunk = fileHistory;
			if (fileHistory.changeset(0) > changelogRevIndexStart && currentFile.isCopy()) {
				// fileHistory.changeset(0) is the earliest revision we know about so far,
				// once we get to revisions earlier than the requested start, stop digging.
				// The reason there's NO == (i.e. not >=) because:
				// (easy): once it's equal, we've reached our intended start
				// (hard): if changelogRevIndexStart happens to be exact start of one of renames in the 
				// chain of renames (test-annotate2 repository, file1->file1a->file1b, i.e. points 
				// to the very start of file1a or file1 history), presence of == would get us to the next 
				// chunk and hence changed parents of present chunk's first element. Our annotate alg 
				// relies on parents only (i.e. knows nothing about 'last iteration element') to find out 
				// what to compare, and hence won't report all lines of 'last iteration element' (which is the
				// first revision of the renamed file) as "added in this revision", leaving gaps in annotate
				HgRepository repo = currentFile.getRepo();
				Nodeid originLastRev = currentFile.getCopySourceRevision();
				currentFile = repo.getFileNode(currentFile.getCopySourceName());
				fileLastClogRevIndex = currentFile.getChangesetRevisionIndex(currentFile.getRevisionIndex(originLastRev));
				// XXX perhaps, shall fail with meaningful exception if new file doesn't exist (.i/.d not found for whatever reason)
				// or source revision is missing?
			} else {
				fileHistory.chopAtChangeset(changelogRevIndexStart);
				currentFile = null; // stop iterating
			}
		} while (currentFile != null && fileLastClogRevIndex > changelogRevIndexStart);
		// fileCompleteHistory is in (origin, intermediate target, ultimate target) order
	}
	
	public Iterable<FileRevisionHistoryChunk> iterate(HgIterateDirection order) {
		if (order == NewToOld) {
			return ReverseIterator.reversed(fileCompleteHistory);
		}
		return Collections.unmodifiableList(fileCompleteHistory);
	}
}
