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
package org.tmatesoft.hg.test;

import static org.tmatesoft.hg.core.HgIterateDirection.NewToOld;
import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgChangesetFileSneaker;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.internal.FileRenameHistory;
import org.tmatesoft.hg.internal.FileRenameHistory.Chunk;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * TODO add tests for {@link HgChangesetFileSneaker}
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestFileRenameUtils {
	

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testFileRenameHistory() throws HgException {
		HgRepository repo = Configuration.get().find("log-renames");
		// history of files from their TIP perspective 
		// series of (fname, fileRefStart, fileRevEnd, csetStart, csetEnd), new to old
		Object[] historyA = new Object[] {"a", 2, 2, 5, 5, "c", 0, 1, 2, 4, "b", 0, 1, 0, 1};
		Object[] historyB = new Object[] {"b", 2, 3, 3, 4, "a", 0, 1, 0, 2 };
		Object[] historyC = new Object[] {"c", 0, 1, 2, 4, "b", 0, 1, 0, 1 };
		Object[] historyD = new Object[] {"d", 1, 1, 5, 5, "b", 2, 3, 3, 4, "a", 0, 1, 0, 2};
		
		FileRenameHistory frh = new FileRenameHistory(0, 5);
		for (Object[] history : new Object[][] {historyA, historyB, historyC, historyD}) {
			String fname = history[0].toString();
			HgDataFile df = repo.getFileNode(fname);
			Assert.assertFalse(frh.isOutOfRange(df, df.getLastRevision()));
			frh.build(df, df.getLastRevision());
			int recordIndex = 0;
			errorCollector.assertEquals(history.length / 5, frh.chunks());
			for (Chunk c : frh.iterate(HgIterateDirection.NewToOld)) {
				compareChunk(fname, c, history, recordIndex++);
			}
			errorCollector.assertEquals("Shall compare full history", history.length, recordIndex * 5);
		}
		//
		HgDataFile df = repo.getFileNode("d");
		Assert.assertFalse(frh.isOutOfRange(df, 0));
		frh.build(df, 0);
		errorCollector.assertEquals(1, frh.chunks());
		Chunk c = frh.iterate(NewToOld).iterator().next();
		compareChunk("abandoned d(0)", c, new Object[] { "d", 0, 0, 4, 4 }, 0);
		//
		df = repo.getFileNode("a");
		Assert.assertFalse(frh.isOutOfRange(df, 0));
		frh.build(df, 0);
		errorCollector.assertEquals(1, frh.chunks());
		c = frh.iterate(NewToOld).iterator().next();
		compareChunk("a(0) and boundary checks", c, new Object[] { "a", 0, 0, 0, 0 }, 0);
		//
		repo = Configuration.get().find("test-annotate"); // need a long file history
		df = repo.getFileNode("file1");
		Assert.assertTrue("[sanity]", repo.getChangelog().getLastRevision() >=9);
		Assert.assertTrue("[sanity]", df.exists() && df.getLastRevision() >= 9);
		frh = new FileRenameHistory(0, 9);
		frh.build(df, 9);
		errorCollector.assertEquals(1, frh.chunks());
		c = frh.iterate(NewToOld).iterator().next();
		compareChunk("regular file, no renames", c, new Object[] { "file1", 0, 9, 0, 9 }, 0);
		// restricted range
		frh = new FileRenameHistory(3, 6);
		Assert.assertFalse(frh.isOutOfRange(df, 9));
		frh.build(df, 9); // start from out of range revision
		errorCollector.assertEquals(1, frh.chunks());
		c = frh.iterate(OldToNew).iterator().next();
		compareChunk("regular file, no renames, in range 3..6", c, new Object[] { "file1", 3, 6, 3, 6 }, 0);
	}
	
	private void compareChunk(String msg, Chunk chunk, Object[] expected, int recordOffset) {
		int off = recordOffset * 5;
		errorCollector.assertEquals(msg, expected[off], chunk.file().getPath().toString());
		errorCollector.assertEquals(msg, expected[off+1], chunk.firstFileRev());
		errorCollector.assertEquals(msg, expected[off+2], chunk.lastFileRev());
		errorCollector.assertEquals(msg, expected[off+3], chunk.firstCset());
		errorCollector.assertEquals(msg, expected[off+4], chunk.lastCset());
	}

}
