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
import org.tmatesoft.hg.test.LogOutputParser.Record;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;

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
		TestHistory th = new TestHistory(new RepositoryLookup().detectFromWorkingDir());
		th.testCompleteLog();
	}

	public TestHistory(HgRepository hgRepo) {
		repo = hgRepo;
		eh = new ExecHelper(changelogParser = new LogOutputParser(true), null);
	}

	public void testCompleteLog() throws Exception {
		changelogParser.reset();
		eh.run("hg", "log", "--debug");
		List<Cset> r = new LogCommand(repo).execute();
		report("hg log", r); 
	}

	private void report(String what, List<Cset> r) {
		final List<Record> consoleResult = changelogParser.getResult();
		Collections.reverse(consoleResult);
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
