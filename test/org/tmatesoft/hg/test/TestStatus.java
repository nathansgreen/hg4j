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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.core.StatusCommand;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;


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
		HgRepository repo = new HgLookup().detectFromWorkingDir();
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
		final HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(repo);
		statusParser.reset();
		eh.run("hg", "status", "-A");
		HgStatusCollector.Record r = wcc.status(HgRepository.TIP);
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
		r = new HgStatusCollector.Record();
		new HgStatusCollector(repo).change(revision, r);
		report("status -A --change " + revision, r, statusParser);
		//
		statusParser.reset();
		int rev2 = 80;
		final String range = String.valueOf(revision) + ":" + String.valueOf(rev2);
		eh.run("hg", "status", "-A", "--rev", range);
		r = new HgStatusCollector(repo).status(revision, rev2);
		report("Status -A -rev " + range, r, statusParser);
	}
	
	public void testStatusCommand() throws Exception {
		final StatusCommand sc = new StatusCommand(repo).all();
		HgStatusCollector.Record r;
		statusParser.reset();
		eh.run("hg", "status", "-A");
		sc.execute(r = new HgStatusCollector.Record());
		report("hg status -A", r, statusParser);
		//
		statusParser.reset();
		int revision = 3;
		eh.run("hg", "status", "-A", "--rev", String.valueOf(revision));
		sc.base(revision).execute(r = new HgStatusCollector.Record());
		report("status -A --rev " + revision, r, statusParser);
		//
		statusParser.reset();
		eh.run("hg", "status", "-A", "--change", String.valueOf(revision));
		sc.base(TIP).revision(revision).execute(r = new HgStatusCollector.Record());
		report("status -A --change " + revision, r, statusParser);
		
		// TODO check not -A, but defaults()/custom set of modifications 
	}
	
	public void testRemovedAgainstNonTip() {
		/*
		 status --rev N when a file added past revision N was removed ((both physically and in dirstate), but not yet committed

		 Reports extra REMOVED file (the one added and removed in between). Shall not
		 */
	}
	
	private static void report(String what, HgStatusCollector.Record r, StatusOutputParser statusParser) {
		System.out.println(">>>" + what);
		reportNotEqual("MODIFIED", r.getModified(), statusParser.getModified());
		reportNotEqual("ADDED", r.getAdded(), statusParser.getAdded());
		reportNotEqual("REMOVED", r.getRemoved(), statusParser.getRemoved());
		reportNotEqual("CLEAN", r.getClean(), statusParser.getClean());
		reportNotEqual("IGNORED", r.getIgnored(), statusParser.getIgnored());
		reportNotEqual("MISSING", r.getMissing(), statusParser.getMissing());
		reportNotEqual("UNKNOWN", r.getUnknown(), statusParser.getUnknown());
		List<Path> copiedKeyDiff = difference(r.getCopied().keySet(), statusParser.getCopied().keySet());
		HashMap<Path, String> copyDiff = new HashMap<Path,String>();
		if (copiedKeyDiff.isEmpty()) {
			for (Path jk : r.getCopied().keySet()) {
				Path jv = r.getCopied().get(jk);
				if (statusParser.getCopied().containsKey(jk)) {
					Path cmdv = statusParser.getCopied().get(jk);
					if (!jv.equals(cmdv)) {
						copyDiff.put(jk, jv + " instead of " + cmdv);
					}
				} else {
					copyDiff.put(jk, "ERRONEOUSLY REPORTED IN JAVA");
				}
			}
		}
		System.out.println("COPIED" + (copiedKeyDiff.isEmpty() && copyDiff.isEmpty() ? " are the same" : " are NOT the same:"));
		for (Path s : copiedKeyDiff) {
			System.out.println("\tNon-matching key:" + s);
		}
		for (Path s : copyDiff.keySet()) {
			System.out.println(s + " : " + copyDiff.get(s));
		}
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
