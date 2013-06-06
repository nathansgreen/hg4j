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
import org.tmatesoft.hg.core.HgRevertCommand;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
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
		// TODO HgInitCommand
		RepoUtils.exec(repoLoc, 0, "hg", "init");
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
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
}
