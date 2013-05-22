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

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgAddRemoveCommand;
import org.tmatesoft.hg.core.HgCatCommand;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgStatus.Kind;
import org.tmatesoft.hg.core.HgStatusCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.COWTransaction;
import org.tmatesoft.hg.internal.CommitFacility;
import org.tmatesoft.hg.internal.DataSerializer.ByteArrayDataSource;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.FileContentSupplier;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.Transaction;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Outcome;
import org.tmatesoft.hg.util.Path;

/**
 * Handy for debug to see patch content:
 * ...RevlogDump /tmp/test-commit2non-empty/.hg/ store/data/file1.i dumpData
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestCommit {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	private final Transaction.Factory trFactory = new COWTransaction.Factory();
//	{
//		public Transaction create(Source ctxSource) {
//			return new Transaction.NoRollback();
//		}
//	};
	
	@Test
	public void testCommitToNonEmpty() throws Exception {
		File repoLoc = RepoUtils.initEmptyTempRepo("test-commit2non-empty");
		RepoUtils.createFile(new File(repoLoc, "file1"), "hello\n");
		new ExecHelper(new OutputParser.Stub(), repoLoc).run("hg", "commit", "--addremove", "-m", "FIRST");
		//
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), 0);
		HgDataFile df = hgRepo.getFileNode("file1");
		cf.add(df, new ByteArrayDataSource("hello\nworld".getBytes()));
		Transaction tr = newTransaction(hgRepo);
		Nodeid secondRev = cf.commit("SECOND", tr);
		tr.commit();
		//
		List<HgChangeset> commits = new HgLogCommand(hgRepo).execute();
		errorCollector.assertEquals(2, commits.size());
		HgChangeset c1 = commits.get(0);
		HgChangeset c2 = commits.get(1);
		errorCollector.assertEquals("FIRST", c1.getComment());
		errorCollector.assertEquals("SECOND", c2.getComment());
		errorCollector.assertEquals(df.getPath(), c2.getAffectedFiles().get(0));
		errorCollector.assertEquals(c1.getNodeid(), c2.getFirstParentRevision());
		errorCollector.assertEquals(Nodeid.NULL, c2.getSecondParentRevision());
		errorCollector.assertEquals(secondRev, c2.getNodeid());
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
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), NO_REVISION);
		HgDataFile df = hgRepo.getFileNode(fname);
		final byte[] initialContent = "hello\nworld".getBytes();
		cf.add(df, new ByteArrayDataSource(initialContent));
		String comment = "commit 1";
		Transaction tr = newTransaction(hgRepo);
		Nodeid c1Rev = cf.commit(comment,  tr);
		tr.commit();
		List<HgChangeset> commits = new HgLogCommand(hgRepo).execute();
		errorCollector.assertEquals(1, commits.size());
		HgChangeset c1 = commits.get(0);
		errorCollector.assertEquals(1, c1.getAffectedFiles().size());
		errorCollector.assertEquals(df.getPath(), c1.getAffectedFiles().get(0));
		errorCollector.assertEquals(0, c1.getRevisionIndex());
		errorCollector.assertEquals(Nodeid.NULL, c1.getFirstParentRevision());
		errorCollector.assertEquals(Nodeid.NULL, c1.getSecondParentRevision());
		errorCollector.assertEquals(HgRepository.DEFAULT_BRANCH_NAME, c1.getBranch());
		errorCollector.assertEquals(comment, c1.getComment());
		errorCollector.assertEquals(c1Rev, c1.getNodeid());
		ByteArrayChannel bac = new ByteArrayChannel();
		new HgCatCommand(hgRepo).file(df.getPath()).execute(bac);
		assertArrayEquals(initialContent, bac.toArray());
	}
	
	@Test
	public void testCommitIntoBranch() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-commit2branch", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		HgDataFile dfD = hgRepo.getFileNode("d");
		assertTrue("[sanity]", dfD.exists());
		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", fileD.canRead());
		final int parentCsetRevIndex = hgRepo.getChangelog().getLastRevision();
		HgChangeset parentCset = new HgLogCommand(hgRepo).range(parentCsetRevIndex, parentCsetRevIndex).execute().get(0);
		assertEquals("[sanity]", DEFAULT_BRANCH_NAME, parentCset.getBranch());
		assertEquals("[sanity]", DEFAULT_BRANCH_NAME, hgRepo.getWorkingCopyBranchName());
		//
		RepoUtils.modifyFileAppend(fileD, "A CHANGE\n");
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), parentCsetRevIndex);
		FileContentSupplier contentProvider = new FileContentSupplier(hgRepo, fileD);
		cf.add(dfD, contentProvider);
		cf.branch("branch1");
		Transaction tr = newTransaction(hgRepo);
		Nodeid commitRev1 = cf.commit("FIRST",  tr);
		tr.commit();
		//
		List<HgChangeset> commits = new HgLogCommand(hgRepo).range(parentCsetRevIndex+1, TIP).execute();
		assertEquals(1, commits.size());
		HgChangeset c1 = commits.get(0);
		errorCollector.assertEquals(c1.getNodeid(), commitRev1);
		errorCollector.assertEquals("branch1", c1.getBranch());
		errorCollector.assertEquals("FIRST", c1.getComment());
		//
		// check if cached value in hgRepo got updated
		errorCollector.assertEquals("branch1", hgRepo.getWorkingCopyBranchName());
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
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), hgRepo.getChangelog().getLastRevision());
		FileContentSupplier contentProvider = new FileContentSupplier(hgRepo, new File(repoLoc, "xx"));
		cf.add(hgRepo.getFileNode("xx"), contentProvider);
		cf.forget(hgRepo.getFileNode("d"));
		Transaction tr = newTransaction(hgRepo);
		Nodeid commitRev = cf.commit("Commit with add/remove cmd",  tr);
		tr.commit();
		//
		List<HgChangeset> commits = new HgLogCommand(hgRepo).changeset(commitRev).execute();
		HgChangeset cmt = commits.get(0);
		errorCollector.assertEquals(1, cmt.getAddedFiles().size());
		errorCollector.assertEquals("xx", cmt.getAddedFiles().get(0).getPath().toString());
		errorCollector.assertEquals(1, cmt.getRemovedFiles().size());
		errorCollector.assertEquals("d", cmt.getRemovedFiles().get(0).toString());
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
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-sequential-commits", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		HgDataFile dfD = hgRepo.getFileNode("d");
		assertTrue("[sanity]", dfD.exists());
		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", fileD.canRead());
		//
		RepoUtils.modifyFileAppend(fileD, " 1 \n");
		final int parentCsetRevIndex = hgRepo.getChangelog().getLastRevision();
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), parentCsetRevIndex);
		FileContentSupplier contentProvider = new FileContentSupplier(hgRepo, fileD);
		cf.add(dfD, contentProvider);
		cf.branch("branch1");
		Transaction tr = newTransaction(hgRepo);
		Nodeid commitRev1 = cf.commit("FIRST",  tr);
		//
		RepoUtils.modifyFileAppend(fileD, " 2 \n");
		cf.add(dfD, contentProvider = new FileContentSupplier(hgRepo, fileD));
		cf.branch("branch2");
		Nodeid commitRev2 = cf.commit("SECOND",  tr);
		//
		RepoUtils.modifyFileAppend(fileD, " 2 \n");
		cf.add(dfD, contentProvider = new FileContentSupplier(hgRepo, fileD));
		cf.branch(DEFAULT_BRANCH_NAME);
		Nodeid commitRev3 = cf.commit("THIRD",  tr);
		tr.commit();
		//
		List<HgChangeset> commits = new HgLogCommand(hgRepo).range(parentCsetRevIndex+1, TIP).execute();
		assertEquals(3, commits.size());
		HgChangeset c1 = commits.get(0);
		HgChangeset c2 = commits.get(1);
		HgChangeset c3 = commits.get(2);
		errorCollector.assertEquals(c1.getNodeid(), commitRev1);
		errorCollector.assertEquals(c2.getNodeid(), commitRev2);
		errorCollector.assertEquals(c3.getNodeid(), commitRev3);
		errorCollector.assertEquals("branch1", c1.getBranch());
		errorCollector.assertEquals("branch2", c2.getBranch());
		errorCollector.assertEquals(DEFAULT_BRANCH_NAME, c3.getBranch());
		errorCollector.assertEquals("FIRST", c1.getComment());
		errorCollector.assertEquals("SECOND", c2.getComment());
		errorCollector.assertEquals("THIRD", c3.getComment());
		assertHgVerifyOk(repoLoc);
	}
	
	@Test
	public void testCommandBasics() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-commit-cmd", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		HgDataFile dfB = hgRepo.getFileNode("b");
		assertTrue("[sanity]", dfB.exists());
		File fileB = new File(repoLoc, "b");
		assertTrue("[sanity]", fileB.canRead());
		RepoUtils.modifyFileAppend(fileB, " 1 \n");

		HgCommitCommand cmd = new HgCommitCommand(hgRepo);
		assertFalse(cmd.isMergeCommit());
		Outcome r = cmd.message("FIRST").execute();
		errorCollector.assertTrue(r.isOk());
		Nodeid c1 = cmd.getCommittedRevision();
		
		// check that modified files are no longer reported as such
		TestStatus.StatusCollector status = new TestStatus.StatusCollector();
		new HgStatusCommand(hgRepo).all().execute(status);
		errorCollector.assertTrue(status.getErrors().isEmpty());
		errorCollector.assertTrue(status.get(Kind.Modified).isEmpty());
		errorCollector.assertEquals(1, status.get(dfB.getPath()).size());
		errorCollector.assertTrue(status.get(dfB.getPath()).contains(Kind.Clean));
		
		HgDataFile dfD = hgRepo.getFileNode("d");
		assertTrue("[sanity]", dfD.exists());
		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", fileD.canRead());
		//
		RepoUtils.modifyFileAppend(fileD, " 1 \n");
		cmd = new HgCommitCommand(hgRepo);
		assertFalse(cmd.isMergeCommit());
		r = cmd.message("SECOND").execute();
		errorCollector.assertTrue(r.isOk());
		Nodeid c2 = cmd.getCommittedRevision();
		//
		int lastRev = hgRepo.getChangelog().getLastRevision();
		List<HgChangeset> csets = new HgLogCommand(hgRepo).range(lastRev-1, lastRev).execute();
		errorCollector.assertEquals(csets.get(0).getNodeid(), c1);
		errorCollector.assertEquals(csets.get(1).getNodeid(), c2);
		errorCollector.assertEquals(csets.get(0).getComment(), "FIRST");
		errorCollector.assertEquals(csets.get(1).getComment(), "SECOND");
		assertHgVerifyOk(repoLoc);
	}
	
	@Test
	public void testUpdateActiveBookmark() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-commit-bookmark-update", false);
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(), repoLoc);
		String activeBookmark = "bm1";
		eh.run("hg", "bookmarks", activeBookmark);

		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		assertEquals("[sanity]", activeBookmark, hgRepo.getBookmarks().getActiveBookmarkName());
		Nodeid activeBookmarkRevision = hgRepo.getBookmarks().getRevision(activeBookmark);
		assertEquals("[sanity]", activeBookmarkRevision, hgRepo.getWorkingCopyParents().first());
		
		HgDataFile dfD = hgRepo.getFileNode("d");
		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", dfD.exists());
		assertTrue("[sanity]", fileD.canRead());

		RepoUtils.modifyFileAppend(fileD, " 1 \n");
		HgCommitCommand cmd = new HgCommitCommand(hgRepo).message("FIRST");
		Outcome r = cmd.execute();
		errorCollector.assertTrue(r.isOk());
		Nodeid c = cmd.getCommittedRevision();
		
		errorCollector.assertEquals(activeBookmark, hgRepo.getBookmarks().getActiveBookmarkName());
		errorCollector.assertEquals(c, hgRepo.getBookmarks().getRevision(activeBookmark));
		// reload repo, and repeat the check
		hgRepo = new HgLookup().detect(repoLoc);
		errorCollector.assertEquals(activeBookmark, hgRepo.getBookmarks().getActiveBookmarkName());
		errorCollector.assertEquals(c, hgRepo.getBookmarks().getRevision(activeBookmark));
	}

	/**
	 * from the wiki:
	 * "active bookmarks are automatically updated when committing to the changeset they are pointing to"
	 * Synopsis: commit 1 (c1), hg bookmark active (points to commit1), make commit 2, hg bookmark -f -r c1 active, commit 3, check active still points to c1 
	 */
	@Test
	public void testNoBookmarkUpdate() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-no-bookmark-upd", false);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		assertNull("[sanity]", hgRepo.getBookmarks().getActiveBookmarkName());
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(), repoLoc);
		String activeBookmark = "bm1";
		eh.run("hg", "bookmarks", activeBookmark);
		assertEquals("Bookmarks has to reload", activeBookmark, hgRepo.getBookmarks().getActiveBookmarkName());
		Nodeid initialBookmarkRevision = hgRepo.getBookmarks().getRevision(activeBookmark); // c1
		assertEquals("[sanity]", initialBookmarkRevision, hgRepo.getWorkingCopyParents().first());

		File fileD = new File(repoLoc, "d");
		assertTrue("[sanity]", fileD.canRead());
		RepoUtils.modifyFileAppend(fileD, " 1 \n");
		HgCommitCommand cmd = new HgCommitCommand(hgRepo).message("FIRST");
		Outcome r = cmd.execute();
		errorCollector.assertTrue(r.isOk());
		Nodeid c2 = cmd.getCommittedRevision();
		errorCollector.assertEquals(c2, hgRepo.getBookmarks().getRevision(activeBookmark));
		//
		if (!Internals.runningOnWindows()) {
			// need change to happen not the same moment as the last commit (and read of bookmark file)
			Thread.sleep(1000); // XXX remove once better file change detection in place
		}
		eh.run("hg", "bookmark", activeBookmark, "--force", "--rev", initialBookmarkRevision.toString());
		//
		RepoUtils.modifyFileAppend(fileD, " 2 \n");
		cmd = new HgCommitCommand(hgRepo).message("SECOND");
		r = cmd.execute();
		errorCollector.assertTrue(r.isOk());
		//Nodeid c3 = cmd.getCommittedRevision();
		errorCollector.assertEquals(initialBookmarkRevision, hgRepo.getBookmarks().getRevision(activeBookmark));
	}

	@Test
	public void testRefreshTagsAndBranches() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-branches", "test-refresh-after-commit", false);
		final String tag = "tag.refresh", branch = "branch-refresh";
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		assertFalse(hgRepo.getTags().getAllTags().containsKey(tag));
		assertNull(hgRepo.getBranches().getBranch(branch));
		RepoUtils.modifyFileAppend(new File(repoLoc, "a"), "whatever");
		//
		final int parentCsetRevIndex = hgRepo.getChangelog().getLastRevision();
		// HgCommitCommand can't do branch yet
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), parentCsetRevIndex);
		cf.add(hgRepo.getFileNode("a"), new FileContentSupplier(hgRepo, new File(repoLoc, "a")));
		cf.branch(branch);
		Transaction tr = newTransaction(hgRepo);
		Nodeid commit = cf.commit("FIRST",  tr);
		tr.commit();
		errorCollector.assertEquals("commit with branch shall update WC", branch, hgRepo.getWorkingCopyBranchName());
		
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(), repoLoc);
		eh.run("hg", "tag", tag);
		assertEquals("[sanity]", 0, eh.getExitValue());
		
		errorCollector.assertTrue(hgRepo.getTags().getAllTags().containsKey(tag));
		errorCollector.assertFalse(hgRepo.getBranches().getBranch(branch) == null);
		errorCollector.assertTrue(hgRepo.getTags().tagged(tag).contains(commit));
		errorCollector.assertTrue(hgRepo.getTags().tags(commit).contains(tag));
	}
	
	@Test
	public void testAddedFilesGetStream() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-commit-addfile-stream", false);
		final File newFile = new File(repoLoc, "xx");
		final byte[] newFileContent = "xyz".getBytes();
		RepoUtils.createFile(newFile, newFileContent);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		new HgAddRemoveCommand(hgRepo).add(Path.create("xx")).execute();
		// save the reference to HgDataFile without valid RevlogStream (entry in the dirstate
		// doesn't make it valid)
		final HgDataFile newFileNode = hgRepo.getFileNode("xx");
		assertFalse(newFileNode.exists());
		HgCommitCommand cmd = new HgCommitCommand(hgRepo).message("FIRST");
		Outcome r = cmd.execute();
		errorCollector.assertTrue(r.isOk());
		TestStatus.StatusCollector status = new TestStatus.StatusCollector();
		new HgStatusCommand(hgRepo).all().execute(status);
		errorCollector.assertTrue(status.getErrors().isEmpty());
		errorCollector.assertTrue(status.get(Kind.Added).isEmpty());
		errorCollector.assertTrue(status.get(newFileNode.getPath()).contains(Kind.Clean));
		//
		errorCollector.assertTrue(newFileNode.exists());
		final ByteArrayChannel read1 = new ByteArrayChannel();
		newFileNode.content(0, read1);
		errorCollector.assertEquals("Read from existing HgDataFile instance", newFileContent, read1.toArray());
		final ByteArrayChannel read2 = new ByteArrayChannel();
		hgRepo.getFileNode(newFileNode.getPath()).content(0, read2);
		errorCollector.assertEquals("Read from fresh HgDataFile instance", newFileContent, read2.toArray());
	}
	
	@Test
	public void testRollback() throws Exception {
		// Important: copy, not a clone of a repo to ensure old timestamps
		// on repository files. Otherwise, there're chances transacition.rollback()
		// would happen the very second (when fs timestamp granularity is second)
		// repository got cloned, and RevlogChangeMonitor won't notice the file change
		// (timestamp is the same, file size increased (CommitFacility) and decreased
		// on rollback back to memorized value), and subsequent hgRepo access would fail
		// trying to read more (due to Revlog#revisionAdded) revisions than there are in 
		// the store file. 
		// With copy and original timestamps we pretend commit happens to an existing repository
		// in a regular manner (it's unlikely to have commits within the same second in a real life)
		// XXX Note, once we have more robust method to detect file changes (e.g. Java7), this
		// approach shall be abandoned.
		File repoLoc = RepoUtils.copyRepoToTempLocation("log-1", "test-commit-rollback");
		final Path newFilePath = Path.create("xx");
		final File newFile = new File(repoLoc, newFilePath.toString());
		RepoUtils.createFile(newFile, "xyz");
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		HgDataFile dfB = hgRepo.getFileNode("b");
		HgDataFile dfD = hgRepo.getFileNode("d");
		assertTrue("[sanity]", dfB.exists());
		assertTrue("[sanity]", dfD.exists());
		final File modifiedFile = new File(repoLoc, "b");
		RepoUtils.modifyFileAppend(modifiedFile, " 1 \n");
		//
		new HgAddRemoveCommand(hgRepo).add(newFilePath).remove(dfD.getPath()).execute();
		//
		TestStatus.StatusCollector status = new TestStatus.StatusCollector();
		new HgStatusCommand(hgRepo).all().execute(status);
		assertTrue(status.getErrors().isEmpty());
		assertTrue(status.get(Kind.Added).contains(newFilePath));
		assertTrue(status.get(Kind.Modified).contains(dfB.getPath()));
		assertTrue(status.get(Kind.Removed).contains(dfD.getPath()));
		assertEquals(DEFAULT_BRANCH_NAME, hgRepo.getWorkingCopyBranchName());
		//
		final int lastClogRevision = hgRepo.getChangelog().getLastRevision();
		final int lastManifestRev = hgRepo.getManifest().getLastRevision();
		CommitFacility cf = new CommitFacility(Internals.getInstance(hgRepo), lastClogRevision);
		cf.add(hgRepo.getFileNode("xx"), new FileContentSupplier(hgRepo, newFile));
		cf.add(dfB, new FileContentSupplier(hgRepo, modifiedFile));
		cf.forget(dfD);
		cf.branch("another-branch");
		Transaction tr = newTransaction(hgRepo);
		Nodeid commitRev = cf.commit("Commit to fail",  tr);
		tr.rollback();
		//
		errorCollector.assertEquals(lastClogRevision, hgRepo.getChangelog().getLastRevision());
		errorCollector.assertEquals(lastManifestRev, hgRepo.getManifest().getLastRevision());
		errorCollector.assertEquals(DEFAULT_BRANCH_NAME, DirstateReader.readBranch(Internals.getInstance(hgRepo)));
		errorCollector.assertFalse(hgRepo.getChangelog().isKnown(commitRev));
		errorCollector.assertFalse(hgRepo.getFileNode("xx").exists());
		// check dirstate
		status = new TestStatus.StatusCollector();
		new HgStatusCommand(hgRepo).all().execute(status);
		errorCollector.assertTrue(status.getErrors().isEmpty());
		errorCollector.assertTrue(status.get(Kind.Added).contains(newFilePath));
		errorCollector.assertTrue(status.get(Kind.Modified).contains(dfB.getPath()));
		errorCollector.assertTrue(status.get(Kind.Removed).contains(dfD.getPath()));
		
		assertHgVerifyOk(repoLoc);
	}
	
	private void assertHgVerifyOk(File repoLoc) throws InterruptedException, IOException {
		ExecHelper verifyRun = new ExecHelper(new OutputParser.Stub(), repoLoc);
		verifyRun.run("hg", "verify");
		errorCollector.assertEquals("hg verify", 0, verifyRun.getExitValue());
	}
	
	private Transaction newTransaction(SessionContext.Source ctxSource) {
		return trFactory.create(ctxSource);
	}

	public static void main(String[] args) throws Exception {
		new TestCommit().testCommitToEmpty();
	}
}
