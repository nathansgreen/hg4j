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
import static org.tmatesoft.hg.test.RepoUtils.createFile;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgAddRemoveCommand;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestAddRemove {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	private ExecHelper eh;

	public TestAddRemove() {
	}
	
	@Test
	public void testScheduleAddition() throws Exception {
		File testRepoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-addremove-1", false);
		repo = new HgLookup().detect(testRepoLoc);
		
		StatusOutputParser statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, testRepoLoc);
		eh.run("hg", "status", "-A");
		assertEquals("[sanity]", 0, statusParser.getUnknown().size());
		assertEquals("[sanity]", 0, statusParser.getAdded().size());
		//
		createFile(new File(testRepoLoc, "one"), "1");
		createFile(new File(testRepoLoc, "two"), "2");
		statusParser.reset();
		eh.run("hg", "status", "-A");
		assertEquals("[sanity]", 2, statusParser.getUnknown().size());
		assertEquals("[sanity]", 0, statusParser.getAdded().size());
		
		new HgAddRemoveCommand(repo).add(Path.create("one"), Path.create("two")).execute();
		statusParser.reset();
		eh.run("hg", "status", "-A");
		assertEquals(0, statusParser.getUnknown().size());
		assertEquals(2, statusParser.getAdded().size());
	}
	
	@Test
	public void testScheduleRemoval() throws Exception {
		File testRepoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-addremove-2", false);
		repo = new HgLookup().detect(testRepoLoc);

		StatusOutputParser statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, testRepoLoc);
		eh.run("hg", "status", "-A");
		assertEquals("[sanity]", 0, statusParser.getUnknown().size());
		assertEquals("[sanity]", 0, statusParser.getRemoved().size());
		
		new HgAddRemoveCommand(repo).remove(Path.create("b"), Path.create("d")).execute();
		statusParser.reset();
		eh.run("hg", "status", "-A");
		assertEquals(2, statusParser.getRemoved().size());
	}
}
