/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestByteChannel {

	private HgRepository repo;

	public static void main(String[] args) throws Exception {
//		HgRepoFacade rf = new HgRepoFacade();
//		rf.init();
//		HgDataFile file = rf.getRepository().getFileNode("src/org/tmatesoft/hg/internal/KeywordFilter.java");
//		for (int i = file.getLastRevision(); i >= 0; i--) {
//			System.out.print("Content for revision:" + i);
//			compareContent(file, i);
//			System.out.println(" OK");
//		}
		//CatCommand cmd = rf.createCatCommand();
	}

//	private static void compareContent(HgDataFile file, int rev) throws Exception {
//		byte[] oldAccess = file.content(rev);
//		ByteArrayChannel ch = new ByteArrayChannel();
//		file.content(rev, ch);
//		byte[] newAccess = ch.toArray();
//		Assert.assertArrayEquals(oldAccess, newAccess);
//		// don't trust anyone (even JUnit) 
//		if (!Arrays.equals(oldAccess, newAccess)) {
//			throw new RuntimeException("Failed:" + rev);
//		}
//	}

	@Test
	public void testContent() throws Exception {
		repo = Configuration.get().find("log-1");
		final byte[] expectedContent = new byte[] { 'a', ' ', 13, 10 };
		ByteArrayChannel ch = new ByteArrayChannel();
		repo.getFileNode("dir/b").content(0, ch);
		assertArrayEquals(expectedContent, ch.toArray());
		repo.getFileNode("d").content(HgRepository.TIP, ch = new ByteArrayChannel() );
		assertArrayEquals(expectedContent, ch.toArray());
	}

	@Test
	public void testStripMetadata() throws Exception {
		repo = Configuration.get().find("log-1");
		ByteArrayChannel ch = new ByteArrayChannel();
		HgDataFile dir_b = repo.getFileNode("dir/b");
		Assert.assertTrue(dir_b.isCopy());
		Assert.assertEquals("b", dir_b.getCopySourceName().toString());
		Assert.assertEquals("e44751cdc2d14f1eb0146aa64f0895608ad15917", dir_b.getCopySourceRevision().toString());
		dir_b.content(0, ch);
		// assert rawContent has 1 10 ... 1 10
		assertArrayEquals("a \r\n".getBytes(), ch.toArray());
		//
		// try once again to make sure metadata records/extracts correct offsets
		dir_b.content(0, ch = new ByteArrayChannel());
		assertArrayEquals("a \r\n".getBytes(), ch.toArray());
	}

	@Test
	public void testWorkingCopyFileAccess() throws Exception {
		final File repoDir = RepoUtils.initEmptyTempRepo("testWorkingCopyFileAccess");
		final Map<String, ?> props = Collections.singletonMap(Internals.CFG_PROPERTY_REVLOG_STREAM_CACHE, false);
		repo = new HgLookup(new BasicSessionContext(props, null)).detect(repoDir);
		File f1 = new File(repoDir, "file1");
		final String c1 = "First", c2 = "Second", c3 = "Third";
		ByteArrayChannel ch;
		ExecHelper exec = new ExecHelper(new OutputParser.Stub(), repoDir);
		// commit cset 0
		write(f1, c1);
		exec.run("hg", "add");
		Assert.assertEquals(0, exec.getExitValue());
		exec.run("hg", "commit", "-m", "c0");
		Assert.assertEquals(0, exec.getExitValue());
		// commit cset 1
		write(f1, c2);
		exec.run("hg", "commit", "-m", "c1");
		assertEquals(0, exec.getExitValue());
		//
		// modify working copy
		write(f1, c3);
		//
		HgDataFile df = repo.getFileNode(f1.getName());
		// 1. Shall take content of the file from the dir
		df.workingCopy(ch = new ByteArrayChannel());
		assertArrayEquals(c3.getBytes(), ch.toArray());
		// 2. Shall supply working copy even if no local file is there
		f1.delete();
		assertFalse(f1.exists());
		df = repo.getFileNode(f1.getName());
		df.workingCopy(ch = new ByteArrayChannel());
		assertArrayEquals(c2.getBytes(), ch.toArray());
		//
		// 3. Shall extract revision of the file that corresponds actual parents (from dirstate) not the TIP as it was  
		exec.run("hg", "update", "-r", "0");
		assertEquals(0, exec.getExitValue());
		f1.delete();
		assertFalse(f1.exists());
		// there's no file and workingCopy shall do some extra work to find out actual revision to check out
		df = repo.getFileNode(f1.getName());
		df.workingCopy(ch = new ByteArrayChannel());
		assertArrayEquals(c1.getBytes(), ch.toArray());
	}

	private static void write(File f, String content) throws IOException {
		FileWriter fw = new FileWriter(f);
		fw.write(content);
		fw.close();
	}
}
