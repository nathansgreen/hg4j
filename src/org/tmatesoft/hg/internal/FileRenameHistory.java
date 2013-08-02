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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.repo.HgDataFile;

/**
 * Traces file renames. Quite similar to HgChangesetFileSneaker, although the latter tries different paths
 * to find origin names, while this class traces first renames found only.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class FileRenameHistory {
	
	private final int csetFrom;
	private final int csetTo;
	private final List<Chunk> history;

	public FileRenameHistory(int csetStartIndex, int csetEndIndex) {
		csetFrom = csetStartIndex;
		csetTo = csetEndIndex;
		history = new ArrayList<Chunk>(3);
	}
	
	public int startChangeset() {
		return csetFrom;
	}
	
	public int endChangeset() {
		return csetTo;
	}
	
	public boolean isOutOfRange(HgDataFile df, int fileRev) {
		return df.getChangesetRevisionIndex(fileRev) < csetFrom || df.getChangesetRevisionIndex(0) > csetTo;
	}
	
	public void build(HgDataFile df, int fileRev) {
		assert !isOutOfRange(df, fileRev);
		LinkedList<Chunk> chunks = new LinkedList<Chunk>();
		int chunkStart = 0, chunkEnd = fileRev;
		int csetChunkEnd = -1, csetChunkStart = -1;
		while (fileRev >= 0) {
			int cset = df.getChangesetRevisionIndex(fileRev);
			if (csetChunkEnd == -1) {
				csetChunkEnd = cset;
			}
			if (cset <= csetFrom) {
				chunkStart = fileRev;
				csetChunkStart = csetFrom;
				break;
			}
			if (cset > csetTo) {
				chunkEnd = --fileRev;
				csetChunkEnd = -1;
				continue;
			}
			csetChunkStart = cset;
			if (df.isCopy(fileRev)) {
				chunks.addFirst(new Chunk(df, fileRev, chunkEnd, csetChunkStart, csetChunkEnd));
				HgFileRevision origin = df.getCopySource(fileRev);
				df = df.getRepo().getFileNode(origin.getPath());
				fileRev = chunkEnd = df.getRevisionIndex(origin.getRevision());
				chunkStart = 0;
				csetChunkEnd = cset - 1; // if df is copy, cset can't be 0
				csetChunkStart = -1;
			} else {
				fileRev--;
			}
		}
		assert chunkStart >= 0;
		assert chunkEnd >= 0; // can be negative only if df.cset(0) > csetTo
		assert csetChunkEnd >= 0;
		assert csetChunkStart >= 0;
		chunks.addFirst(new Chunk(df, chunkStart, chunkEnd, csetChunkStart, csetChunkEnd));

		history.clear();
		history.addAll(chunks);
	}

	public Iterable<Chunk> iterate(HgIterateDirection order) {
		if (order == HgIterateDirection.NewToOld) {
			return ReverseIterator.reversed(history);
		}
		assert order == HgIterateDirection.OldToNew;
		return Collections.unmodifiableList(history);
	}
	
	public int chunks() {
		return history.size();
	}
	
	public Chunk chunkAt(int cset) {
		if (cset < csetFrom || cset > csetTo) {
			return null;
		}
		for (Chunk c : history) {
			if (c.firstCset() > cset) {
				break;
			}
			if (cset <= c.lastCset()) {
				return c;
			}
		}
		return null;
	}


	/**
	 * file has changes [firstFileRev..lastFileRev] that have occurred somewhere in [firstCset..lastCset] 
	 */
	public static class Chunk {
		private final HgDataFile df;
		private final int fileRevFrom;
		private final int fileRevTo;
		private final int csetFrom;
		private final int csetTo;
		Chunk(HgDataFile file, int fileRevStart, int fileRevEnd, int csetStart, int csetEnd) {
			df = file;
			fileRevFrom = fileRevStart;
			fileRevTo = fileRevEnd;
			csetFrom = csetStart;
			csetTo = csetEnd;
		}
		public HgDataFile file() {
			return df;
		}
		public int firstFileRev() {
			return fileRevFrom;
		}
		public int lastFileRev() {
			return fileRevTo;
		}
		public int firstCset() {
			return csetFrom;
		}
		public int lastCset() {
			return csetTo;
		}
	}
}
