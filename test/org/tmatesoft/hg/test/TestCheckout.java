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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.test.RepoUtils.cloneRepoToTempLocation;

import java.io.File;
import java.io.FileFilter;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.FileInfo;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestCheckout {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	private ExecHelper eh;


	@Test
	public void testCleanCheckoutInEmptyDir() throws Exception {
		File testRepoLoc = cloneRepoToTempLocation("log-1", "test-checkout-clean", true);
		repo = new HgLookup().detect(testRepoLoc);
		// nothing but .hg dir
		assertEquals("[sanity]", 0, testRepoLoc.listFiles(new FilesOnlyFilter()).length);
		
		new HgCheckoutCommand(repo).clean(true).changeset(1).execute();
		errorCollector.assertEquals(2, testRepoLoc.listFiles(new FilesOnlyFilter()).length);

		Pair<Nodeid, Nodeid> workingCopyParents = repo.getWorkingCopyParents();
		errorCollector.assertEquals("da3461cd828dae8eb5fd11189d40e9df1961f191", workingCopyParents.first().toString());
		errorCollector.assertEquals(Nodeid.NULL, workingCopyParents.second());
		errorCollector.assertEquals(HgRepository.DEFAULT_BRANCH_NAME, repo.getWorkingCopyBranchName());

		StatusOutputParser statusOutputParser = new StatusOutputParser();
		eh = new ExecHelper(statusOutputParser, testRepoLoc);
		eh.run("hg", "status", "-A");
		errorCollector.assertEquals(2, statusOutputParser.getClean().size());
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("a")));
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("b")));
		
		new HgCheckoutCommand(repo).clean(true).changeset(3).execute();
		statusOutputParser.reset();
		eh.run("hg", "status", "-A");
		errorCollector.assertEquals(3, statusOutputParser.getClean().size());
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("b")));
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("d")));
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("dir/b")));
	}

	/**
	 * Make sure WC is cleared prior to clean checkout
	 */
	@Test
	public void testCleanCheckoutInDirtyDir() throws Exception {
		File repoLoc = cloneRepoToTempLocation("log-1", "test-checkout-dirty", false);
		File untrackedFile = new File(repoLoc, "aaa");
		RepoUtils.createFile(untrackedFile, "shall survive hg co --clean");
		File modifiedFile = new File(repoLoc, "b");
		assertTrue("[sanity]", modifiedFile.canRead());
		final long modifiedFileInitialLen = modifiedFile.length();
		RepoUtils.modifyFileAppend(modifiedFile, "the change shall not survive");
		assertTrue("[sanity]", modifiedFile.length() > modifiedFileInitialLen);
		//
		repo = new HgLookup().detect(repoLoc);
		new HgCheckoutCommand(repo).clean(true).changeset(TIP).execute();
		errorCollector.assertTrue(untrackedFile.canRead());
		errorCollector.assertEquals(modifiedFileInitialLen, modifiedFile.length());
	}

	@Test
	public void testBranchCheckout() throws Exception {
		File testRepoLoc = cloneRepoToTempLocation("log-branches", "test-checkoutBranch", true);
		repo = new HgLookup().detect(testRepoLoc);
		
		new HgCheckoutCommand(repo).clean(true).changeset(3 /*branch test*/).execute();

		StatusOutputParser statusOutputParser = new StatusOutputParser();
		eh = new ExecHelper(statusOutputParser, testRepoLoc);
		eh.run("hg", "status", "-A");
		errorCollector.assertEquals(3, statusOutputParser.getClean().size());
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("a")));
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("b")));
		errorCollector.assertTrue(statusOutputParser.getClean().contains(Path.create("c")));
		
		errorCollector.assertEquals("test", repo.getWorkingCopyBranchName());
	}
	
	@Test
	public void testCheckoutLinkAndExec() throws Exception {
		File testRepoLoc = cloneRepoToTempLocation("test-flags", "test-checkout-flags", true);
		repo = new HgLookup().detect(testRepoLoc);
		new HgCheckoutCommand(repo).clean(true).changeset(0).execute();
		
		FileWalker fw = new FileWalker(repo, testRepoLoc, new Path.SimpleSource(), null);
		int execFound, linkFound, regularFound;
		execFound = linkFound = regularFound = 0;
		while(fw.hasNext()) {
			fw.next();
			FileInfo fi = fw.file();
			boolean executable = fi.isExecutable();
			boolean symlink = fi.isSymlink();
			if (executable) {
				execFound++;
			}
			if (symlink) {
				linkFound++;
			}
			if (!executable && !symlink) {
				regularFound++;
			}
		}
		// TODO alter expected values to pass on Windows 
		errorCollector.assertEquals("Executable files", 1, execFound);
		errorCollector.assertEquals("Symlink files", 1, linkFound);
		errorCollector.assertEquals("Regular files", 1, regularFound);
	}

	private static final class FilesOnlyFilter implements FileFilter {
		public boolean accept(File f) {
			return f.isFile();
		}
	}
}
