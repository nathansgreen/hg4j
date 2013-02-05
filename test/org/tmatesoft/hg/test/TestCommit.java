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

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;

import org.junit.Test;
import org.tmatesoft.hg.repo.CommitFacility;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestCommit {

	@Test
	public void testCommitToNonEmpty() throws Exception {
		File repoLoc = RepoUtils.initEmptyTempRepo("test-commit2non-empty");
		FileWriter fw = new FileWriter(new File(repoLoc, "file1"));
		fw.write("hello");
		fw.close();
		new ExecHelper(new OutputParser.Stub(true), repoLoc).run("hg", "commit", "--addremove", "-m", "FIRST");
		//
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		CommitFacility cf = new CommitFacility(hgRepo, 0 /*NO_REVISION*/);
		// FIXME test diff for processing changed newlines - if a whole line or just changed endings are in the patch!
		cf.add(hgRepo.getFileNode("file1"), new ByteArraySupplier("hello\nworld".getBytes()));
		cf.commit("commit 1");
		// /tmp/test-commit2non-empty/.hg/ store/data/file1.i dumpData
	}
	
	public static void main(String[] args) throws Exception {
		new TestCommit().testCommitToNonEmpty();
		String input = "abcdefghijklmnopqrstuvwxyz";
		ByteArraySupplier bas = new ByteArraySupplier(input.getBytes());
		ByteBuffer bb = ByteBuffer.allocate(7);
		byte[] result = new byte[26];
		int rpos = 0;
		while (bas.read(bb) != -1) {
			bb.flip();
			bb.get(result, rpos, bb.limit());
			rpos += bb.limit();
			bb.clear();
		}
		if (input.length() != rpos) {
			throw new AssertionError();
		}
		String output = new String(result);
		if (!input.equals(output)) {
			throw new AssertionError();
		}
		System.out.println(output);
	}

	static class ByteArraySupplier implements CommitFacility.ByteDataSupplier {

		private final byte[] data;
		private int pos = 0;

		public ByteArraySupplier(byte[] source) {
			data = source;
		}

		public int read(ByteBuffer buf) {
			if (pos >= data.length) {
				return -1;
			}
			int count = Math.min(buf.remaining(), data.length - pos);
			buf.put(data, pos, count);
			pos += count;
			return count;
		}
	}
}
