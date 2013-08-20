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

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgAddRemoveCommand;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgInitCommand;
import org.tmatesoft.hg.core.HgMergeCommand;
import org.tmatesoft.hg.core.HgRevertCommand;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgMergeState;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Outcome;
import org.tmatesoft.hg.util.Path;

/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ComplexTest {
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	/**
	 * Regular work sequence with checkout, add, remove, revert and commit
	 */
	@Test
	public void testLocalScenario1() throws Exception {
		File repoLoc = RepoUtils.createEmptyDir("composite-scenario-1");
		// init empty
		HgRepository hgRepo = new HgInitCommand().location(repoLoc).revlogV1().execute();
		assertFalse("[sanity]", hgRepo.isInvalid());
		assertEquals("[sanity]", 0, hgRepo.getChangelog().getRevisionCount());
		// add 2 files
		Path fa = Path.create("a"), fb = Path.create("b");
		final File fileA = new File(repoLoc, fa.toString());
		final File fileB = new File(repoLoc, fb.toString());
		RepoUtils.createFile(fileA, "first file");
		RepoUtils.createFile(fileB, "second file");
		new HgAddRemoveCommand(hgRepo).add(fa, fb).execute();
		new HgCommitCommand(hgRepo).message("FIRST").execute();
		// add one more file
		// remove one initial file
		Path fc = Path.create("c");
		final File fileC = new File(repoLoc, fc.toString());
		RepoUtils.createFile(fileC, "third file");
		fileB.delete();
		// TODO HgAddRemoveCommand needs #copy(from, to) method 
		new HgAddRemoveCommand(hgRepo).add(fc).remove(fb).execute();
		new HgCommitCommand(hgRepo).message("SECOND").execute();
		//
		assertEquals(2, hgRepo.getChangelog().getRevisionCount());
		errorCollector.assertEquals("SECOND", hgRepo.getCommitLastMessage());
		// checkout previous version
		new HgCheckoutCommand(hgRepo).changeset(0).clean(true).execute();
		assertTrue(fileA.isFile());
		assertTrue(fileB.isFile());
		assertFalse(fileC.isFile());
		// branch/two heads
		RepoUtils.modifyFileAppend(fileA, "A1");
		RepoUtils.modifyFileAppend(fileB, "B1");
		new HgCommitCommand(hgRepo).message("THIRD").execute();
		//
		new HgCheckoutCommand(hgRepo).changeset(1).clean(true).execute();
		assertTrue(fileA.isFile());
		assertFalse(fileB.isFile());
		assertTrue(fileC.isFile());
		RepoUtils.modifyFileAppend(fileA, "A2");
		RepoUtils.modifyFileAppend(fileC, "C1");
		new HgRevertCommand(hgRepo).changeset(1).file(fa).execute();
		errorCollector.assertTrue(new File(fileA.getParent(), fileA.getName() + ".orig").isFile());
		new HgCommitCommand(hgRepo).message("FOURTH").execute();
		// TODO merge and HgMergeCommand
		
		errorCollector.assertEquals(2, hgRepo.getFileNode(fa).getRevisionCount());
		errorCollector.assertEquals(2, hgRepo.getFileNode(fb).getRevisionCount());
		errorCollector.assertEquals(2, hgRepo.getFileNode(fc).getRevisionCount());
		final HgManifest mf = hgRepo.getManifest();
		errorCollector.assertEquals(mf.getFileRevision(0, fa), mf.getFileRevision(3, fa)); // "A2" was reverted
	}

	@Test
	public void testMergeAndCommit() throws Exception {
		File repoLoc = RepoUtils.createEmptyDir("composite-scenario-2");
		HgRepository hgRepo = new HgInitCommand().location(repoLoc).revlogV1().execute();
		Path fa = Path.create("file1"), fb = Path.create("file2"), fc = Path.create("file3");
		final File fileA = new File(repoLoc, fa.toString());
		final File fileB = new File(repoLoc, fb.toString());
		// rev0: +file1, +file2
		RepoUtils.createFile(fileA, "first file");
		RepoUtils.createFile(fileB, "second file");
		new HgAddRemoveCommand(hgRepo).add(fa, fb).execute();
		final HgCommitCommand commitCmd = new HgCommitCommand(hgRepo);
		commitCmd.message("FIRST").execute();
		// rev1: *file1, *file2
		RepoUtils.modifyFileAppend(fileA, "A1");
		RepoUtils.modifyFileAppend(fileB, "B1");
		commitCmd.message("SECOND").execute();
		// rev2: *file1, -file2
		RepoUtils.modifyFileAppend(fileA, "A2");
		fileB.delete();
		new HgAddRemoveCommand(hgRepo).remove(fb).execute();
		commitCmd.message("THIRD").execute();
		// rev3: fork rev0, +file3, *file2
		new HgCheckoutCommand(hgRepo).changeset(0).clean(true).execute();
		final File fileC = new File(repoLoc, fc.toString());
		RepoUtils.createFile(fileC, "third file");
		RepoUtils.modifyFileAppend(fileB, "B2");
		new HgAddRemoveCommand(hgRepo).add(fc).execute();
		commitCmd.message("FOURTH").execute();
		// rev4: *file3
		RepoUtils.modifyFileAppend(fileC, "C1");
		commitCmd.message("FIFTH").execute();
		// rev5: merge rev2 with rev3
		new HgCheckoutCommand(hgRepo).changeset(2).clean(true).execute();
		new HgMergeCommand(hgRepo).changeset(3).execute(new HgMergeCommand.MediatorBase());
		commitCmd.message("SIXTH: merge rev2 and rev3");
		errorCollector.assertTrue(commitCmd.isMergeCommit());
		HgMergeState ms = hgRepo.getMergeState();
		ms.refresh();
		errorCollector.assertTrue(ms.isMerging());
		errorCollector.assertFalse(ms.isStale());
		errorCollector.assertEquals(0, ms.getConflicts().size());
		Outcome o = commitCmd.execute();
		errorCollector.assertTrue(o.getMessage(), o.isOk());
		ms.refresh();
		errorCollector.assertFalse(ms.isMerging());
		errorCollector.assertEquals(0, ms.getConflicts().size());
		RepoUtils.assertHgVerifyOk(errorCollector, repoLoc);
	}
}
