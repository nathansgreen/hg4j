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

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgRevertCommand;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;


/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestRevert {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	private ExecHelper eh;

	
	public TestRevert() {
	}
	
	@Test
	public void testCommand() throws Exception {
		// get a copy of a repository
		File testRepoLoc = RepoUtils.cloneRepoToTempLocation(Configuration.get().find("log-1"), "test-revert", false);
		
		repo = new HgLookup().detect(testRepoLoc);
		Path targetFile = Path.create("b");
		RepoUtils.modifyFileAppend(new File(testRepoLoc, targetFile.toString()), "XXX");
		
		StatusOutputParser statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, testRepoLoc);
		eh.run("hg", "status", "-A");
		assertEquals("[sanity]", 1, statusParser.getModified().size());
		assertEquals("[sanity]", 2, statusParser.getClean().size());
		assertEquals("[sanity]", targetFile, statusParser.getModified().get(0));
		
		HgRevertCommand cmd = new HgRevertCommand(repo);
		cmd.file(targetFile).execute();
		statusParser.reset();
		eh.run("hg", "status", "-A");

		errorCollector.assertEquals(3, statusParser.getClean().size());
		errorCollector.assertTrue(statusParser.getClean().contains(targetFile));
		errorCollector.assertEquals(1, statusParser.getUnknown().size());
		errorCollector.assertEquals(targetFile.toString() + ".orig", statusParser.getUnknown().get(0).toString());
	}
}
