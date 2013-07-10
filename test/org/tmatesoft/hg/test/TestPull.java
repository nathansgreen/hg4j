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

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgAddRemoveCommand;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgIncomingCommand;
import org.tmatesoft.hg.core.HgPullCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * FIXME need TestTransaction to check transaction rolback/commit as it's tricky to test transactions as part of pull/push commands
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestPull {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	@Test
	public void testPullToEmpty() throws Exception {
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-pull2empty-src", false);
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-pull2empty-dst");
		HgServer server = new HgServer().start(srcRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRemoteRepository srcRemote = hgLookup.detect(server.getURL());
			final HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			HgPullCommand cmd = new HgPullCommand(dstRepo).source(srcRemote);
			cmd.execute();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			checkRepositoriesAreSame(srcRepo, dstRepo);
			final List<Nodeid> incoming = new HgIncomingCommand(dstRepo).against(srcRemote).executeLite();
			errorCollector.assertTrue(incoming.toString(), incoming.isEmpty());
			RepoUtils.assertHgVerifyOk(errorCollector, dstRepoLoc);
		} finally {
			server.stop();
		}
	}

	/**
	 * pull comes with 2 changes, one of them with new file
	 */
	@Test
	public void testPullChanges() throws Exception {
 
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-pull-src", false);
		File dstRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-pull-dst", false);
		File f1 = new File(srcRepoLoc, "file1");
		assertTrue("[sanity]", f1.canWrite());
		final HgLookup hgLookup = new HgLookup();
		// add two commits, one with new file at different branch
		// commit 1
		final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
		assertEquals("[sanity]", "default", srcRepo.getWorkingCopyBranchName());
		RepoUtils.modifyFileAppend(f1, "change1");
		HgCommitCommand commitCmd = new HgCommitCommand(srcRepo).message("Commit 1");
		assertTrue(commitCmd.execute().isOk());
		final Nodeid cmt1 = commitCmd.getCommittedRevision();
		// commit 2
		new HgCheckoutCommand(srcRepo).changeset(7).clean(true).execute();
		assertEquals("[sanity]", "no-merge", srcRepo.getWorkingCopyBranchName());
		RepoUtils.createFile(new File(srcRepoLoc, "file-new"), "whatever");
		new HgAddRemoveCommand(srcRepo).add(Path.create("file-new")).execute();
		commitCmd = new HgCommitCommand(srcRepo).message("Commit 2");
		assertTrue(commitCmd.execute().isOk());
		final Nodeid cmt2 = commitCmd.getCommittedRevision();
		//
		// pull
		HgServer server = new HgServer().start(srcRepoLoc);
		final HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
		try {
			final HgRemoteRepository srcRemote = hgLookup.detect(server.getURL());
			new HgPullCommand(dstRepo).source(srcRemote).execute();
		} finally {
			server.stop();
		}
		//
		errorCollector.assertTrue(dstRepo.getChangelog().isKnown(cmt1));
		errorCollector.assertTrue(dstRepo.getChangelog().isKnown(cmt2));
		checkRepositoriesAreSame(srcRepo, dstRepo);
		RepoUtils.assertHgVerifyOk(errorCollector, dstRepoLoc);
	}
	
	/**
	 * Add two draft changesets, one child of r8 (local:draft, remote:public) and another 
	 * as child of r4 (public), pull and see if 5, 7 and 8 became public, but newly added drafts remained
	 */
	@Test
	public void testPullFromPublishing() throws Exception {
		// copy, not clone as latter updates phase information
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-pull-pub-src");
		File dstRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-pull-pub-dst");
		File f1 = new File(dstRepoLoc, "hello.c");
		assertTrue("[sanity]", f1.canWrite());
		final HgLookup hgLookup = new HgLookup();
		HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
		PhasesHelper phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo));
		//
		// new child revision for shared public parent
		assertEquals(HgPhase.Public, phaseHelper.getPhase(4, null));
		new HgCheckoutCommand(dstRepo).changeset(4).clean(true).execute();
		RepoUtils.modifyFileAppend(f1, "// aaa");
		HgCommitCommand commitCmd = new HgCommitCommand(dstRepo).message("Commit 1");
		assertTrue(commitCmd.execute().isOk());
		final Nodeid cmt1 = commitCmd.getCommittedRevision();
		//
		// new child rev for parent locally draft, remotely public
		assertEquals(HgPhase.Draft, phaseHelper.getPhase(5, null));
		assertEquals(HgPhase.Draft, phaseHelper.getPhase(7, null));
		assertEquals(HgPhase.Draft, phaseHelper.getPhase(8, null));
		new HgCheckoutCommand(dstRepo).changeset(8).clean(true).execute();
		RepoUtils.modifyFileAppend(f1, "// bbb");
		commitCmd = new HgCommitCommand(dstRepo).message("Commit 2");
		assertTrue(commitCmd.execute().isOk());
		final Nodeid cmt2 = commitCmd.getCommittedRevision();
		// both new revisions shall be draft
		phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo)); // refresh PhasesHelper
		assertEquals(HgPhase.Draft, phaseHelper.getPhase(dstRepo.getChangelog().getRevisionIndex(cmt1), cmt1));
		assertEquals(HgPhase.Draft, phaseHelper.getPhase(dstRepo.getChangelog().getRevisionIndex(cmt2), cmt2));
		//

		HgServer server = new HgServer().publishing(true).start(srcRepoLoc);
		try {
			final HgRemoteRepository srcRemote = hgLookup.detect(server.getURL());
			new HgPullCommand(dstRepo).source(srcRemote).execute();
		} finally {
			server.stop();
		}
		// refresh PhasesHelper
		phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo));
		errorCollector.assertEquals(HgPhase.Public, phaseHelper.getPhase(5, null));
		errorCollector.assertEquals(HgPhase.Public, phaseHelper.getPhase(7, null));
		errorCollector.assertEquals(HgPhase.Public, phaseHelper.getPhase(8, null));
		// phase of local-only new revisions shall not change
		errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(dstRepo.getChangelog().getRevisionIndex(cmt1), cmt1));
		errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(dstRepo.getChangelog().getRevisionIndex(cmt2), cmt2));
	}
	
	@Test
	public void testPullFromNonPublishing() throws Exception {
		// copy, not clone as latter updates phase information
		File srcRepoLoc = RepoUtils.copyRepoToTempLocation("test-phases", "test-pull-nopub-src");
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-pull-nopub-dst");
		Map<String,?> props = Collections.singletonMap(Internals.CFG_PROPERTY_CREATE_PHASEROOTS, true);
		final HgLookup hgLookup = new HgLookup(new BasicSessionContext(props, null));
		HgRepository srcRepo = hgLookup.detect(srcRepoLoc);		
		// revisions 6 and 9 are secret, so
		// index of revisions 4 and 5 won't change, but that of 7 and 8 would
		Nodeid r7 = srcRepo.getChangelog().getRevision(7);
		Nodeid r8 = srcRepo.getChangelog().getRevision(8);

		HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
		HgServer server = new HgServer().publishing(false).start(srcRepoLoc);
		try {
			final HgRemoteRepository srcRemote = hgLookup.detect(server.getURL());
			new HgPullCommand(dstRepo).source(srcRemote).execute();
		} finally {
			server.stop();
		}
		PhasesHelper phaseHelper = new PhasesHelper(HgInternals.getImplementationRepo(dstRepo));
		errorCollector.assertEquals(HgPhase.Public, phaseHelper.getPhase(4, null));
		errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(5, null));
		errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(dstRepo.getChangelog().getRevisionIndex(r7), r7));
		errorCollector.assertEquals(HgPhase.Draft, phaseHelper.getPhase(dstRepo.getChangelog().getRevisionIndex(r8), r8));
		final RevisionSet dstSecret = phaseHelper.allSecret();
		errorCollector.assertTrue(dstSecret.toString(), dstSecret.isEmpty());
	}

	private void checkRepositoriesAreSame(HgRepository srcRepo, HgRepository dstRepo) {
		// XXX copy of TestPush#checkRepositoriesAreSame
		errorCollector.assertEquals(srcRepo.getChangelog().getRevisionCount(), dstRepo.getChangelog().getRevisionCount());
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(0), dstRepo.getChangelog().getRevision(0));
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(TIP), dstRepo.getChangelog().getRevision(TIP));
	}
}
