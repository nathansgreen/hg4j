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

import static org.junit.Assert.*;
import static org.tmatesoft.hg.repo.HgRepository.*;
import static org.tmatesoft.hg.repo.HgRepository.DEFAULT_BRANCH_NAME;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

import org.junit.Test;
import org.tmatesoft.hg.core.HgAddRemoveCommand;
import org.tmatesoft.hg.core.HgCatCommand;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.repo.CommitFacility;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * Handy for debug to see patch content:
 * ...RevlogDump /tmp/test-commit2non-empty/.hg/ store/data/file1.i dumpData
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestCommit {

	@Test
	public void testCommitToNonEmpty() throws Exception {
		File repoLoc = RepoUtils.initEmptyTempRepo("test-commit2non-empty");
		RepoUtils.createFile(new File(repoLoc, "file1"), "hello\n");
		new ExecHelper(new OutputParser.Stub(), repoLoc).run("hg", "commit", "--addremove", "-m", "FIRST");
		//
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		CommitFacility cf = new CommitFacility(hgRepo, 0);
		// FIXME test diff for processing changed newlines (ie \r\n -> \n or vice verse) - if a whole line or 
		// just changed endings are in the patch!
		HgDataFile df = hgRepo.getFileNode("file1");
		cf.add(df, new ByteArraySupplier("hello\nworld".getBytes()));
		Nodeid secondRev = cf.commit("SECOND");
		//
		List<HgChangeset> commits = new HgLogCommand(hgRepo).execute();
		assertEquals(2, commits.size());
		HgChangeset c1 = commits.get(0);
		HgChangeset c2 = commits.get(1);
		assertEquals("FIRST", c1.getComment());
		assertEquals("SECOND", c2.getComment());
		assertEquals(df.getPath(), c2.getAffectedFiles().get(0));
		assertEquals(c1.getNodeid(), c2.getFirstParentRevision());
		assertEquals(Nodeid.NULL, c2.getSecondParentRevision());
		assertEquals(secondRev, c2.getNodeid());
	}
	
	@Test
	public void testCommitToEmpty() throws Exception {
		File repoLoc = RepoUtils.initEmptyTempRepo("test-commit2empty");
		String fname = "file1";
		RepoUtils.createFile(new File(repoLoc, fname), null);
		new ExecHelper(new OutputParser.Stub(), repoLoc).run("hg", "add", fname);
		//
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		assertEquals("[sanity]", 0, new HgLogCommand(hgRepo).execute().size());
		CommitFacility cf = new CommitFacility(hgRepo, NO_REVISION);
		HgDataFile df = hgRepo.getFileNode(fname);
		final byte[] initialContent = "hello\nworld".getBytes();
		cf.add(df, new ByteArraySupplier(initialContent));
		String comment = "commit 1";
		Nodeid c1Rev = cf.commit(comment);
		List<HgChangeset> commits = new HgLogCommand(hgRepo).execute();
		assertEquals(1, commits.size());
		HgChangeset c1 = commits.get(0);
		assertEquals(1, c1.getAffectedFiles().size());
		assertEquals(df.getPath(), c1.getAffectedFiles().get(0));
		assertEquals(0, c1.getRevisionIndex());
		assertEquals(Nodeid.NULL, c1.getFirstParentRevision());
		assertEquals(Nodeid.NULL, c1.getSecondParentRevision());
		assertEquals(HgRepository.DEFAULT_BRANCH_NAME, c1.getBranch());
		assertEquals(comment, c1.getComment());
		assertEquals(c1Rev, c1.getNodeid());
		ByteArrayChannel bac = new ByteArrayChannel();
		new HgCatCommand(hgRepo).file(df.getPath()).execute(bac);
		assertArrayEquals(initialContent, bac.toArray());
	}
	
	@Test
	public void testCommitIntoBranch() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-add-remove-commit", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		HgDataFile dfD = hgRepo.getFileNode("d");
		assertTrue("[sanity]", dfD.exists());
		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", fileD.canRead());
		final int parentCsetRevIndex = hgRepo.getChangelog().getLastRevision();
		HgChangeset parentCset = new HgLogCommand(hgRepo).range(parentCsetRevIndex, parentCsetRevIndex).execute().get(0);
		assertEquals("[sanity]", DEFAULT_BRANCH_NAME, parentCset.getBranch());
		//
		RepoUtils.modifyFileAppend(fileD, "A CHANGE\n");
		CommitFacility cf = new CommitFacility(hgRepo, parentCsetRevIndex);
		FileContentSupplier contentProvider = new FileContentSupplier(fileD);
		cf.add(dfD, contentProvider);
		cf.branch("branch1");
		Nodeid commitRev1 = cf.commit("FIRST");
		contentProvider.done();
		//
		// FIXME requirement to reload repository is disgusting 
		hgRepo = new HgLookup().detect(repoLoc);
		List<HgChangeset> commits = new HgLogCommand(hgRepo).range(parentCsetRevIndex+1, TIP).execute();
		assertEquals(1, commits.size());
		HgChangeset c1 = commits.get(0);
		assertEquals(c1.getNodeid(), commitRev1);
		assertEquals("branch1", c1.getBranch());
		assertEquals("FIRST", c1.getComment());
		//
		assertHgVerifyOk(repoLoc);
	}

	/**
	 * use own add and remove commands and then commit
	 */
	@Test
	public void testCommitWithAddRemove() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-add-remove-commit", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		assertTrue("[sanity]", hgRepo.getFileNode("d").exists());
		assertTrue("[sanity]", new File(repoLoc, "d").canRead());
		RepoUtils.createFile(new File(repoLoc, "xx"), "xyz");
		new HgAddRemoveCommand(hgRepo).add(Path.create("xx")).remove(Path.create("d")).execute();
		CommitFacility cf = new CommitFacility(hgRepo, hgRepo.getChangelog().getLastRevision());
		FileContentSupplier contentProvider = new FileContentSupplier(new File(repoLoc, "xx"));
		cf.add(hgRepo.getFileNode("xx"), contentProvider);
		cf.forget(hgRepo.getFileNode("d"));
		Nodeid commitRev = cf.commit("Commit with add/remove cmd");
		contentProvider.done();
		// Note, working directory still points to original revision, CommitFacility doesn't update dirstate
		//
		// FIXME requirement to reload repository is disgusting 
		hgRepo = new HgLookup().detect(repoLoc);
		List<HgChangeset> commits = new HgLogCommand(hgRepo).changeset(commitRev).execute();
		HgChangeset cmt = commits.get(0);
		assertEquals(1, cmt.getAddedFiles().size());
		assertEquals("xx", cmt.getAddedFiles().get(0).getPath().toString());
		assertEquals(1, cmt.getRemovedFiles().size());
		assertEquals("d", cmt.getRemovedFiles().get(0).toString());
		ByteArrayChannel sink = new ByteArrayChannel();
		new HgCatCommand(hgRepo).file(Path.create("xx")).changeset(commitRev).execute(sink);
		assertArrayEquals("xyz".getBytes(), sink.toArray());
		//
		assertHgVerifyOk(repoLoc);
	}
	/**
	 * perform few commits one by one, into different branches
	 */
	@Test
	public void testSequentialCommits() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-add-remove-commit", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		HgDataFile dfD = hgRepo.getFileNode("d");
		assertTrue("[sanity]", dfD.exists());
		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", fileD.canRead());
		//
		RepoUtils.modifyFileAppend(fileD, " 1 \n");
		final int parentCsetRevIndex = hgRepo.getChangelog().getLastRevision();
		CommitFacility cf = new CommitFacility(hgRepo, parentCsetRevIndex);
		FileContentSupplier contentProvider = new FileContentSupplier(fileD);
		cf.add(dfD, contentProvider);
		cf.branch("branch1");
		Nodeid commitRev1 = cf.commit("FIRST");
		contentProvider.done();
		//
		RepoUtils.modifyFileAppend(fileD, " 2 \n");
		cf.add(dfD, contentProvider = new FileContentSupplier(fileD));
		cf.branch("branch2");
		Nodeid commitRev2 = cf.commit("SECOND");
		contentProvider.done();
		//
		RepoUtils.modifyFileAppend(fileD, " 2 \n");
		cf.add(dfD, contentProvider = new FileContentSupplier(fileD));
		cf.branch(DEFAULT_BRANCH_NAME);
		Nodeid commitRev3 = cf.commit("THIRD");
		contentProvider.done();
		//
		// FIXME requirement to reload repository is disgusting 
		hgRepo = new HgLookup().detect(repoLoc);
		List<HgChangeset> commits = new HgLogCommand(hgRepo).range(parentCsetRevIndex+1, TIP).execute();
		assertEquals(3, commits.size());
		HgChangeset c1 = commits.get(0);
		HgChangeset c2 = commits.get(1);
		HgChangeset c3 = commits.get(2);
		assertEquals(c1.getNodeid(), commitRev1);
		assertEquals(c2.getNodeid(), commitRev2);
		assertEquals(c3.getNodeid(), commitRev3);
		assertEquals("branch1", c1.getBranch());
		assertEquals("branch2", c2.getBranch());
		assertEquals(DEFAULT_BRANCH_NAME, c3.getBranch());
		assertEquals("FIRST", c1.getComment());
		assertEquals("SECOND", c2.getComment());
		assertEquals("THIRD", c3.getComment());
		assertHgVerifyOk(repoLoc);
	}
	
	private void assertHgVerifyOk(File repoLoc) throws InterruptedException, IOException {
		ExecHelper verifyRun = new ExecHelper(new OutputParser.Stub(), repoLoc);
		verifyRun.run("hg", "verify");
		assertEquals("hg verify", 0, verifyRun.getExitValue());
	}

	public static void main(String[] args) throws Exception {
		new TestCommit().testCommitToEmpty();
		if (Boolean.TRUE.booleanValue()) {
			return;
		}
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
	
	static class FileContentSupplier implements CommitFacility.ByteDataSupplier {
		private final FileChannel channel;
		private IOException error;

		public FileContentSupplier(File f) throws IOException {
			if (!f.canRead()) {
				throw new IOException(String.format("Can't read file %s", f));
			}
			channel = new FileInputStream(f).getChannel();
		}

		public int read(ByteBuffer buf) {
			if (error != null) {
				return -1;
			}
			try {
				return channel.read(buf);
			} catch (IOException ex) {
				error = ex;
			}
			return -1;
		}
		
		public void done() throws IOException {
			channel.close();
			if (error != null) {
				throw error;
			}
		}
	}
}
