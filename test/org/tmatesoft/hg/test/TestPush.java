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

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgOutgoingCommand;
import org.tmatesoft.hg.core.HgPushCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
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
		Assert.fail();
	}
	
	@Test
	public void testPushToPublishingServer() throws Exception {
		Assert.fail();
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
		Assert.fail();
	}


	private void checkRepositoriesAreSame(HgRepository srcRepo, HgRepository dstRepo) {
		errorCollector.assertEquals(srcRepo.getChangelog().getRevisionCount(), dstRepo.getChangelog().getRevisionCount());
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(0), dstRepo.getChangelog().getRevision(0));
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(TIP), dstRepo.getChangelog().getRevision(TIP));
	}

	static class HgServer {
		private Process serverProcess;

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
