/*
 * Copyright (c) 2011 TMate Software Ltd
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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.tmatesoft.hg.core.Cset;
import org.tmatesoft.hg.core.LogCommand;
import org.tmatesoft.hg.core.LogCommand.CollectHandler;
import org.tmatesoft.hg.core.LogCommand.FileHistoryHandler;
import org.tmatesoft.hg.core.LogCommand.FileRevision;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.test.LogOutputParser.Record;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestHistory {

	private final HgRepository repo;
	private ExecHelper eh;
	private LogOutputParser changelogParser;
	
	public static void main(String[] args) throws Exception {
		TestHistory th = new TestHistory(new HgLookup().detectFromWorkingDir());
		th.testCompleteLog();
		th.testFollowHistory();
	}

	public TestHistory(HgRepository hgRepo) {
		repo = hgRepo;
		eh = new ExecHelper(changelogParser = new LogOutputParser(true), null);
	}

	public void testCompleteLog() throws Exception {
		changelogParser.reset();
		eh.run("hg", "log", "--debug");
		List<Cset> r = new LogCommand(repo).execute();
		report("hg log - COMPLETE REPO HISTORY", r, true); 
	}
	
	public void testFollowHistory() throws Exception {
		final Path f = Path.create("cmdline/org/tmatesoft/hg/console/Remote.java");
		try {
			if (repo.getFileNode(f).exists()) { // FIXME getFileNode shall not fail with IAE
				changelogParser.reset();
				eh.run("hg", "log", "--debug", "--follow", f.toString());
				
				class H extends CollectHandler implements FileHistoryHandler {
					boolean copyReported = false;
					boolean fromMatched = false;
					public void copy(FileRevision from, FileRevision to) {
						copyReported = true;
						fromMatched = "src/com/tmate/hgkit/console/Remote.java".equals(from.getPath().toString());
					}
				};
				H h = new H();
				new LogCommand(repo).file(f, true).execute(h);
				System.out.print("hg log - FOLLOW FILE HISTORY");
				System.out.println("\tcopyReported:" + h.copyReported + ", and was " + (h.fromMatched ? "CORRECT" : "WRONG"));
				report("hg log - FOLLOW FILE HISTORY", h.getChanges(), false);
			}
		} catch (IllegalArgumentException ex) {
			System.out.println("Can't test file history with follow because need to query specific file with history");
		}
	}

	private void report(String what, List<Cset> r, boolean reverseConsoleResults) {
		final List<Record> consoleResult = changelogParser.getResult();
		if (reverseConsoleResults) {
			Collections.reverse(consoleResult);
		}
		Iterator<LogOutputParser.Record> consoleResultItr = consoleResult.iterator();
		boolean hasErrors = false;
		for (Cset cs : r) {
			LogOutputParser.Record cr = consoleResultItr.next();
			int x = cs.getRevision() == cr.changesetIndex ? 0x1 : 0;
			x |= cs.getDate().equals(cr.date) ? 0x2 : 0;
			x |= cs.getNodeid().toString().equals(cr.changesetNodeid) ? 0x4 : 0;
			x |= cs.getUser().equals(cr.user) ? 0x8 : 0;
			x |= cs.getComment().equals(cr.description) ? 0x10 : 0;
			if (x != 0x1f) {
				System.err.printf("Error in %d (%d):0%o\n", cs.getRevision(), cr.changesetIndex, x);
				hasErrors = true;
			}
			consoleResultItr.remove();
		}
		if (consoleResultItr.hasNext()) {
			System.out.println("Insufficient results from Java");
			hasErrors = true;
		}
		System.out.println(what + (hasErrors ? " FAIL" : " OK"));
	}
}
