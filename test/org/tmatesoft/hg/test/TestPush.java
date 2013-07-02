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
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgOutgoingCommand;
import org.tmatesoft.hg.core.HgPushCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.repo.HgBookmarks;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestPush {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testPushToEmpty() throws Exception {
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-push2empty-src", false);
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-push2empty-dst");
		HgServer server = new HgServer().start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			HgPushCommand cmd = new HgPushCommand(srcRepo);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			cmd.destination(dstRemote);
			cmd.execute();
			final HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			checkRepositoriesAreSame(srcRepo, dstRepo);
			final List<Nodeid> outgoing = new HgOutgoingCommand(srcRepo).against(dstRemote).executeLite();
			errorCollector.assertTrue(outgoing.toString(), outgoing.isEmpty());
		} finally {
			server.stop();
		}
	}

	@Test
	public void testPushChanges() throws Exception {
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-push-src", false);
		File dstRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-push-dst", false);
		File f1 = new File(srcRepoLoc, "file1");
		assertTrue("[sanity]", f1.canWrite());
		HgServer server = new HgServer().start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			RepoUtils.modifyFileAppend(f1, "change1");
			new HgCommitCommand(srcRepo).message("Commit 1").execute();
			new HgCheckoutCommand(srcRepo).changeset(7).clean(true).execute();
			assertEquals("[sanity]", "no-merge", srcRepo.getWorkingCopyBranchName());
			RepoUtils.modifyFileAppend(f1, "change2");
			new HgCommitCommand(srcRepo).message("Commit 2").execute();
			//
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			checkRepositoriesAreSame(srcRepo, hgLookup.detect(dstRepoLoc));
			final List<Nodeid> outgoing = new HgOutgoingCommand(srcRepo).against(dstRemote).executeLite();
			errorCollector.assertTrue(outgoing.toString(), outgoing.isEmpty());
		} finally {
			server.stop();
		}
	}
	
	@Test
	public void testPushToNonPublishingServer() throws Exception {
		// check drafts are same as on server
		// copy, not clone as latter updates phase information
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-push-nopub-src");
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-push-nopub-dst");
		File f1 = new File(srcRepoLoc, "hello.c");
		assertTrue("[sanity]", f1.canWrite());
		HgServer server = new HgServer().publishing(false).start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			PhasesHelper phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
			final RevisionSet allDraft = phaseHelper.allDraft();
			assertFalse("[sanity]", allDraft.isEmpty());
			final int publicCsetToBranchAt = 4;
			assertEquals("[sanity]", HgPhase.Public, phaseHelper.getPhase(publicCsetToBranchAt, null));
			// in addition to existing draft csets, add one more draft, branching at some other public revision
			new HgCheckoutCommand(srcRepo).changeset(publicCsetToBranchAt).clean(true).execute();
			RepoUtils.modifyFileAppend(f1, "// aaa");
			final HgCommitCommand commitCmd = new HgCommitCommand(srcRepo).message("Commit aaa");
			assertTrue(commitCmd.execute().isOk());
			Nodeid newCommit = commitCmd.getCommittedRevision();
			//
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			final HgChangelog srcClog = srcRepo.getChangelog();
			final HgChangelog dstClog = dstRepo.getChangelog();
			// refresh PhasesHelper
			phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
			// check if phase didn't change
			errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(srcClog.getRevisionIndex(newCommit), newCommit));
			for (Nodeid n : allDraft) {
				// check drafts from src were actually pushed to dst 
				errorCollector.assertTrue(dstClog.isKnown(n));
				// check drafts didn't change their phase
				errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(srcClog.getRevisionIndex(n), n));
			}
		} finally {
			server.stop();
		}
	}
	
	/**
	 * If server lists revisions we know as drafts as public, update them locally
	 */
	@Test
	public void testPushUpdatesPublishedDrafts() throws Exception {
		/* o		r9, secret
		 * |  o		r8, draft
		 * |  |
		 * |  o		r7, draft
		 * o  |		r6, secret 
		 * | /
		 * o		r5, draft
		 * |
		 * o		r4, public
		 */
		// remote: r5 -> public, r6 -> draft, r8 -> secret
		// local: new draft from r4, push
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-push-phase-update-1-src");
		File dstRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-push-phase-update-1-dst");
		File f1 = new File(srcRepoLoc, "hello.c");
		assertTrue("[sanity]", f1.canWrite());
		final HgLookup hgLookup = new HgLookup();
		final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
		final ExecHelper dstRun = new ExecHelper(new OutputParser.Stub(), dstRepoLoc);
		final int publicCsetToBranchAt = 4;
		final int r5 = 5, r6 = 6, r8 = 8;
		PhasesHelper srcPhase = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
		assertEquals("[sanity]", HgPhase.Draft, srcPhase.getPhase(r5, null));
		assertEquals("[sanity]", HgPhase.Secret, srcPhase.getPhase(r6, null));
		assertEquals("[sanity]", HgPhase.Draft, srcPhase.getPhase(r8, null));
		// change phases in repository of remote server:
		dstRun.exec("hg", "phase", "--public", String.valueOf(r5));
		assertEquals(0, dstRun.getExitValue());
		dstRun.exec("hg", "phase", "--draft", String.valueOf(r6));
		assertEquals(0, dstRun.getExitValue());
		dstRun.exec("hg", "phase", "--secret", "--force", String.valueOf(r8));
		assertEquals(0, dstRun.getExitValue());
		HgServer server = new HgServer().publishing(false).start(dstRepoLoc);
		try {
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			// commit new draft head
			new HgCheckoutCommand(srcRepo).changeset(publicCsetToBranchAt).clean(true).execute();
			RepoUtils.modifyFileAppend(f1, "// aaa");
			final HgCommitCommand commitCmd = new HgCommitCommand(srcRepo).message("Commit aaa");
			assertTrue(commitCmd.execute().isOk());
			final Nodeid newCommit = commitCmd.getCommittedRevision();
			//
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			// refresh phase information
			srcPhase = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
			// r5 and r6 are changed to match server phases (more exposed)
			errorCollector.assertEquals(HgPhase.Public, srcPhase.getPhase(r5, null));
			errorCollector.assertEquals(HgPhase.Draft, srcPhase.getPhase(r6, null));
			// r8 is secret on server, locally can't make it less exposed though
			errorCollector.assertEquals(HgPhase.Draft, srcPhase.getPhase(r8, null));
			//
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			final HgChangelog dstClog = dstRepo.getChangelog();
			assertTrue(dstClog.isKnown(newCommit));
			PhasesHelper dstPhase = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo));
			errorCollector.assertEquals(HgPhase.Draft, dstPhase.getPhase(dstClog.getRevisionIndex(newCommit), newCommit));
			// the one that was secret is draft now
			errorCollector.assertEquals(HgPhase.Draft, srcPhase.getPhase(r8, null));
		} finally {
			server.stop();
		}
	}
	
	/**
	 * update phases of local revisions and push changes
	 */
	@Test
	public void testPushPublishAndUpdates() throws Exception {
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-push-phase-update-2-src");
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-push-phase-update-1-dst");
		final int r4 = 4, r5 = 5, r6 = 6, r9 = 9;
		HgServer server = new HgServer().publishing(false).start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			//
			// make sure pushed repository got same draft root
			final Nodeid r4PublicHead = srcRepo.getChangelog().getRevision(r4);
			final Nodeid r5DraftRoot = srcRepo.getChangelog().getRevision(r5);
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			final HgChangelog dstClog = dstRepo.getChangelog();
			PhasesHelper dstPhase = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo));
			assertEquals(HgPhase.Public, dstPhase.getPhase(dstClog.getRevisionIndex(r4PublicHead), r4PublicHead));
			assertEquals(HgPhase.Draft, dstPhase.getPhase(dstClog.getRevisionIndex(r5DraftRoot), r5DraftRoot));
			//
			// now, graduate some local revisions, r5:draft->public, r6:secret->public, r9: secret->draft
			final ExecHelper srcRun = new ExecHelper(new OutputParser.Stub(), srcRepoLoc);
			srcRun.exec("hg", "phase", "--public", String.valueOf(r5));
			srcRun.exec("hg", "phase", "--public", String.valueOf(r6));
			srcRun.exec("hg", "phase", "--draft", String.valueOf(r9));
			// PhaseHelper shall be new for the command, and would pick up these external changes 
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			final Nodeid r6Nodeid = srcRepo.getChangelog().getRevision(r6);
			final Nodeid r9Nodeid = srcRepo.getChangelog().getRevision(r9);
			// refresh 
			dstPhase = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo));
			// not errorCollector as subsequent code would fail if these secret revs didn't get into dst
			assertTrue(dstClog.isKnown(r6Nodeid));
			assertTrue(dstClog.isKnown(r9Nodeid));
			errorCollector.assertEquals(HgPhase.Public, dstPhase.getPhase(dstClog.getRevisionIndex(r5DraftRoot), r5DraftRoot));
			errorCollector.assertEquals(HgPhase.Public, dstPhase.getPhase(dstClog.getRevisionIndex(r6Nodeid), r6Nodeid));
			errorCollector.assertEquals(HgPhase.Draft, dstPhase.getPhase(dstClog.getRevisionIndex(r9Nodeid), r9Nodeid));
		} finally {
			server.stop();
		}
	}

	
	@Test
	public void testPushToPublishingServer() throws Exception {
		// copy, not clone as latter updates phase information
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-push-pub-src");
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-push-pub-dst");
		HgServer server = new HgServer().publishing(true).start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			PhasesHelper phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
			final RevisionSet allDraft = phaseHelper.allDraft();
			assertFalse("[sanity]", allDraft.isEmpty());
			// push all changes
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			final HgChangelog srcClog = srcRepo.getChangelog();
			final HgChangelog dstClog = dstRepo.getChangelog();
			// refresh PhasesHelper
			phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
			for (Nodeid n : allDraft) {
				// check drafts from src were actually pushed to dst 
				errorCollector.assertTrue(dstClog.isKnown(n));
				// check drafts became public
				errorCollector.assertEquals(HgPhase.Public, phaseHelper.getPhase(srcClog.getRevisionIndex(n), n));
			}
		} finally {
			server.stop();
		}
	}

	@Test
	public void testPushSecretChangesets() throws Exception {
		// copy, not clone as latter updates phase information
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-push-no-secret-src");
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-push-no-secret-dst");
		HgServer server = new HgServer().start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			PhasesHelper phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(srcRepo));
			final RevisionSet allSecret = phaseHelper.allSecret();
			assertFalse("[sanity]", allSecret.isEmpty());
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			final HgChangelog srcClog = srcRepo.getChangelog();
			final HgChangelog dstClog = dstRepo.getChangelog();
			errorCollector.assertEquals(srcClog.getRevisionCount() - allSecret.size(), dstClog.getRevisionCount());
			for (Nodeid n : allSecret) {		
				errorCollector.assertTrue(n.toString(), !dstClog.isKnown(n));
			}
		} finally {
			server.stop();
		}
	}

	@Test
	public void testUpdateBookmarkOnPush() throws Exception {
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-push-src", false);
		File dstRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-push-dst", false);
		final ExecHelper srcRun = new ExecHelper(new OutputParser.Stub(), srcRepoLoc);
		final ExecHelper dstRun = new ExecHelper(new OutputParser.Stub(), dstRepoLoc);
		File f1 = new File(srcRepoLoc, "file1");
		assertTrue("[sanity]", f1.canWrite());
		//
		final String bm1 = "mark1", bm2 = "mark2", bm3 = "mark3", bm4 = "mark4", bm5 = "mark5";
		final int bm2Local = 1, bm2Remote = 6, bm3Local = 7, bm3Remote = 2, bm_4_5 = 3;
		// 1) bm1 - local active bookmark, check that push updates in remote
		srcRun.exec("hg", "bookmark", bm1);
		dstRun.exec("hg", "bookmark", "-r", "8", bm1);
		// 2) bm2 - local points to ancestor of revision remote points to
		srcRun.exec("hg", "bookmark", "-r", String.valueOf(bm2Local), bm2);
		dstRun.exec("hg", "bookmark", "-r", String.valueOf(bm2Remote), bm2);
		// 3) bm3 - remote points to ancestor of revision local one points to   
		srcRun.exec("hg", "bookmark", "-r", String.valueOf(bm3Local), bm3);
		dstRun.exec("hg", "bookmark", "-r", String.valueOf(bm3Remote), bm3);
		// 4) bm4 - remote bookmark, not known locally
		dstRun.exec("hg", "bookmark", "-r", String.valueOf(bm_4_5), bm4);
		// 5) bm5 - local bookmark, not known remotely
		srcRun.exec("hg", "bookmark", "-r", String.valueOf(bm_4_5), bm5);
		//
		HgServer server = new HgServer().start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			RepoUtils.modifyFileAppend(f1, "change1");
			final HgCommitCommand commitCmd = new HgCommitCommand(srcRepo).message("Commit 1");
			assertTrue(commitCmd.execute().isOk());
			assertEquals(bm1, srcRepo.getBookmarks().getActiveBookmarkName());
			assertEquals(commitCmd.getCommittedRevision(), srcRepo.getBookmarks().getRevision(bm1));
			//
			new HgPushCommand(srcRepo).destination(dstRemote).execute();
			Thread.sleep(300); // let the server perform the update
			//
			HgBookmarks srcBookmarks = srcRepo.getBookmarks();
			final HgChangelog srcClog = srcRepo.getChangelog();
			// first, check local bookmarks are intact
			errorCollector.assertEquals(srcClog.getRevision(bm2Local), srcBookmarks.getRevision(bm2));
			errorCollector.assertEquals(srcClog.getRevision(bm3Local), srcBookmarks.getRevision(bm3));
			errorCollector.assertEquals(null, srcBookmarks.getRevision(bm4));
			errorCollector.assertEquals(srcClog.getRevision(bm_4_5), srcBookmarks.getRevision(bm5));
			// now, check remote bookmarks were touched
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			HgBookmarks dstBookmarks = dstRepo.getBookmarks();
			final HgChangelog dstClog = dstRepo.getChangelog();
			// bm1 changed and points to newly pushed commit.
			// if the test fails (bm1 points to r8), chances are server didn't manage to update
			// bookmarks yet (there's Thread.sleep() above to give it a chance).
			errorCollector.assertEquals(commitCmd.getCommittedRevision(), dstBookmarks.getRevision(bm1));
			// bm2 didn't change
			errorCollector.assertEquals(dstClog.getRevision(bm2Remote), dstBookmarks.getRevision(bm2));
			// bm3 did change, now points to value we've got in srcRepo
			errorCollector.assertEquals(srcClog.getRevision(bm3Local), dstBookmarks.getRevision(bm3));
			// bm4 is not affected
			errorCollector.assertEquals(dstClog.getRevision(bm_4_5), dstBookmarks.getRevision(bm4));
			// bm5 is not known remotely
			errorCollector.assertEquals(null, dstBookmarks.getRevision(bm5));
		} finally {
			server.stop();
		}
	}


	private void checkRepositoriesAreSame(HgRepository srcRepo, HgRepository dstRepo) {
		errorCollector.assertEquals(srcRepo.getChangelog().getRevisionCount(), dstRepo.getChangelog().getRevisionCount());
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(0), dstRepo.getChangelog().getRevision(0));
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(TIP), dstRepo.getChangelog().getRevision(TIP));
	}

	static class HgServer {
		private Process serverProcess;
		private boolean publish = true;
		
		public HgServer publishing(boolean pub) {
			publish = pub;
			return this;
		}

		public HgServer start(File dir) throws IOException, InterruptedException {
			if (serverProcess != null) {
				stop();
			}
			List<String> cmdline = new ArrayList<String>();
			cmdline.add("hg");
			cmdline.add("--config");
			cmdline.add("web.allow_push=*");
			cmdline.add("--config");
			cmdline.add("web.push_ssl=False");
			cmdline.add("--config");
			cmdline.add("server.validate=True");
			cmdline.add("--config");
			cmdline.add(String.format("web.port=%d", port()));
			if (!publish) {
				cmdline.add("--config");
				cmdline.add("phases.publish=False");
			}
			cmdline.add("serve");
			serverProcess = new ProcessBuilder(cmdline).directory(dir).start();
			Thread.sleep(500);
			return this;
		}
		
		public URL getURL() throws MalformedURLException {
			return new URL(String.format("http://localhost:%d/", port()));
		}

		public int port() {
			return 9090;
		}
		
		public void stop() {
			if (serverProcess == null) {
				return;
			}
			// if Process#destroy() doesn't perform well with scripts and child processes
			// may need to write server pid to a file and send a kill <pid> here
			serverProcess.destroy();
			serverProcess = null;
		}
	}
}
