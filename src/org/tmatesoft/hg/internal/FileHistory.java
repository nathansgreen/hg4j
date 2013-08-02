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
import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;

import java.util.Collections;
import java.util.LinkedList;

import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.FileRenameHistory.Chunk;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRuntimeException;

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

	public void build() throws HgRuntimeException {
		fileCompleteHistory.clear(); // just in case, #build() is not expected to be called more than once
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(csetTo, df.getPath());
		int fileRevIndex = df.getRevisionIndex(fileRev);
		FileRenameHistory frh = new FileRenameHistory(csetFrom, csetTo);
		if (frh.isOutOfRange(df, fileRevIndex)) {
			return;
		}
		frh.build(df, fileRevIndex);
		FileRevisionHistoryChunk prevChunk = null;
		for (Chunk c : frh.iterate(OldToNew)) {
			FileRevisionHistoryChunk fileHistory = new FileRevisionHistoryChunk(c.file(), c.firstCset(), c.lastCset(), c.firstFileRev(), c.lastFileRev());
			fileHistory.init();
			if (fileHistory.revisionCount() == 0) {
				// no revisions on our cset range of interest
				continue;
			}
			if (prevChunk != null) {
				prevChunk.linkTo(fileHistory);
			}
			fileCompleteHistory.addLast(fileHistory); // to get the list in old-to-new order
			prevChunk = fileHistory;
		}
		// fileCompleteHistory is in (origin, intermediate target, ultimate target) order
	}
	
	public Iterable<FileRevisionHistoryChunk> iterate(HgIterateDirection order) {
		if (order == NewToOld) {
			return ReverseIterator.reversed(fileCompleteHistory);
		}
		return Collections.unmodifiableList(fileCompleteHistory);
	}
}
