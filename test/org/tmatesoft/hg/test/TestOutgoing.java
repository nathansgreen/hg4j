/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgOutgoingCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestOutgoing {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	public static void main(String[] args) throws Throwable {
		Configuration.get().remoteServers("http://localhost:8000/");
		TestOutgoing t = new TestOutgoing();
		t.testSimple();
		t.errorCollector.verify();
	}

	public TestOutgoing() {
	}

	@Test
	public void testSimple() throws Exception {
		int x = 0;
		HgLookup lookup = new HgLookup();
		for (HgRemoteRepository hgRemote : Configuration.get().allRemote()) {
			File dest = RepoUtils.createEmptyDir("test-outgoing-" + x++);
			ExecHelper eh0 = new ExecHelper(new OutputParser.Stub(false), null);
			eh0.run("hg", "clone", hgRemote.getLocation(), dest.toString());
			eh0.cwd(dest);
			Assert.assertEquals("initial clone failed", 0, eh0.getExitValue());
			HgOutgoingCommand cmd = new HgOutgoingCommand(lookup.detect(dest)).against(hgRemote);
			LogOutputParser outParser = new LogOutputParser(true);
			ExecHelper eh = new ExecHelper(outParser, dest);
			HgLogCommand.CollectHandler collector = new HgLogCommand.CollectHandler();
			//
			cmd.executeFull(collector);
			List<Nodeid> liteResult = cmd.executeLite();
			eh.run("hg", "outgoing", "--debug", hgRemote.getLocation());
			TestIncoming.report(collector, outParser, liteResult, errorCollector);
			//
			File f = new File(dest, "Test.txt");
			RepoUtils.createFile(f, "1");
			eh0.run("hg", "add");
			eh0.run("hg", "commit", "-m", "1");
			RepoUtils.modifyFileAppend(f, "2");
			eh0.run("hg", "commit", "-m", "2");
			//
			cmd = new HgOutgoingCommand(lookup.detect(dest)).against(hgRemote);
			cmd.executeFull(collector = new HgLogCommand.CollectHandler());
			liteResult = cmd.executeLite();
			outParser.reset();
			eh.run("hg", "outgoing", "--debug", hgRemote.getLocation());
			TestIncoming.report(collector, outParser, liteResult, errorCollector);
		}
	}
	
	/**
	 * Issue 47: Incorrect set of outgoing changes when revision spins off prior to common revision of local and remote repos
	 */
	@Test
	public void testOutgoingPreceedsCommon() throws Exception {
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-outgoing-src", false);
		File dstRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-outgoing-dst", false);
		File f1 = new File(srcRepoLoc, "file1");
		assertTrue("[sanity]", f1.canWrite());
		HgServer server = new HgServer().start(dstRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			final HgRemoteRepository dstRemote = hgLookup.detect(server.getURL());
			new HgCheckoutCommand(srcRepo).changeset(6).clean(true).execute();
			assertEquals("[sanity]", "with-merge", srcRepo.getWorkingCopyBranchName());
			RepoUtils.modifyFileAppend(f1, "change1");
			new HgCommitCommand(srcRepo).message("Commit 1").execute();
			new HgCheckoutCommand(srcRepo).changeset(5).clean(true).execute();
			assertEquals("[sanity]", "no-merge", srcRepo.getWorkingCopyBranchName());
			RepoUtils.modifyFileAppend(f1, "change2");
			new HgCommitCommand(srcRepo).message("Commit 2").execute();
			//
			HgOutgoingCommand cmd = new HgOutgoingCommand(srcRepo).against(dstRemote);
			LogOutputParser outParser = new LogOutputParser(true);
			ExecHelper eh = new ExecHelper(outParser, srcRepoLoc);
			HgLogCommand.CollectHandler collector = new HgLogCommand.CollectHandler();
			//
			List<Nodeid> liteResult = cmd.executeLite();
			cmd.executeFull(collector);
			eh.run("hg", "outgoing", "--debug", dstRemote.getLocation());
			TestIncoming.report(collector, outParser, liteResult, errorCollector);
		} finally {
			server.stop();
		}
	}
}
