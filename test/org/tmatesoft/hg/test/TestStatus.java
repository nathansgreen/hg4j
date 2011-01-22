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

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.io.File;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.StatusCommand;

import com.tmate.hgkit.fs.FileWalker;
import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.StatusCollector;
import com.tmate.hgkit.ll.WorkingCopyStatusCollector;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestStatus {

	private final HgRepository repo;
	private StatusOutputParser statusParser;
	private ExecHelper eh;

	public static void main(String[] args) throws Exception {
		HgRepository repo = new RepositoryLookup().detectFromWorkingDir();
		TestStatus test = new TestStatus(repo);
		test.testLowLevel();
		test.testStatusCommand();
	}
	
	public TestStatus(HgRepository hgRepo) {
		repo = hgRepo;
		statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, null);
	}
	
	public void testLowLevel() throws Exception {
		final WorkingCopyStatusCollector wcc = new WorkingCopyStatusCollector(repo, new FileWalker(new File(System.getProperty("user.dir"))));
		statusParser.reset();
		eh.run("hg", "status", "-A");
		StatusCollector.Record r = wcc.status(HgRepository.TIP);
		report("hg status -A", r, statusParser);
		//
		statusParser.reset();
		int revision = 3;
		eh.run("hg", "status", "-A", "--rev", String.valueOf(revision));
		r = wcc.status(revision);
		report("status -A --rev " + revision, r, statusParser);
		//
		statusParser.reset();
		eh.run("hg", "status", "-A", "--change", String.valueOf(revision));
		r = new StatusCollector.Record();
		new StatusCollector(repo).change(revision, r);
		report("status -A --change " + revision, r, statusParser);
	}
	
	public void testStatusCommand() throws Exception {
		final StatusCommand sc = new StatusCommand(repo).all();
		StatusCollector.Record r;
		statusParser.reset();
		eh.run("hg", "status", "-A");
		sc.execute(r = new StatusCollector.Record());
		report("hg status -A", r, statusParser);
		//
		statusParser.reset();
		int revision = 3;
		eh.run("hg", "status", "-A", "--rev", String.valueOf(revision));
		sc.base(revision).execute(r = new StatusCollector.Record());
		report("status -A --rev " + revision, r, statusParser);
		//
		statusParser.reset();
		eh.run("hg", "status", "-A", "--change", String.valueOf(revision));
		sc.base(TIP).revision(revision).execute(r = new StatusCollector.Record());
		report("status -A --change " + revision, r, statusParser);
		
		// TODO check not -A, but defaults()/custom set of modifications 
	}
	
	private static void report(String what, StatusCollector.Record r, StatusOutputParser statusParser) {
		System.out.println(">>>" + what);
		reportNotEqual("MODIFIED", r.getModified(), statusParser.getModified());
		reportNotEqual("ADDED", r.getAdded(), statusParser.getAdded());
		reportNotEqual("REMOVED", r.getRemoved(), statusParser.getRemoved());
		reportNotEqual("CLEAN", r.getClean(), statusParser.getClean());
		reportNotEqual("IGNORED", r.getIgnored(), statusParser.getIgnored());
		reportNotEqual("MISSING", r.getMissing(), statusParser.getMissing());
		reportNotEqual("UNKNOWN", r.getUnknown(), statusParser.getUnknown());
		// TODO compare equals
		System.out.println("<<<\n");
	}
	
	private static <T> void reportNotEqual(String what, Collection<T> l1, Collection<T> l2) {
		List<T> diff = difference(l1, l2);
		System.out.print(what);
		if (!diff.isEmpty()) {
			System.out.print(" are NOT the same: ");
			for (T t : diff) {
				System.out.print(t);
				System.out.print(", ");
			}
			System.out.println();
		} else {
			System.out.println(" are the same");
		}
	}

	private static <T> List<T> difference(Collection<T> l1, Collection<T> l2) {
		LinkedList<T> result = new LinkedList<T>(l2);
		for (T t : l1) {
			if (l2.contains(t)) {
				result.remove(t);
			} else {
				result.add(t);
			}
		}
		return result;
	}
}
