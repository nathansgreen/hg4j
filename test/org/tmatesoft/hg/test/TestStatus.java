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
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.tmatesoft.hg.core.HgStatus.*;
import static org.tmatesoft.hg.core.HgStatus.Kind.*;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgStatus;
import org.tmatesoft.hg.core.HgStatusCommand;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;
import org.tmatesoft.hg.util.Path;


/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestStatus {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private final HgRepository repo;
	private StatusOutputParser statusParser;
	private ExecHelper eh;

	public static void main(String[] args) throws Throwable {
		TestStatus test = new TestStatus();
		test.testLowLevel();
		test.testStatusCommand();
		test.testPerformance();
		test.errorCollector.verify();
	}
	
	public TestStatus() throws Exception {
		this(new HgLookup().detectFromWorkingDir());
	}

	private TestStatus(HgRepository hgRepo) {
		repo = hgRepo;
		Assume.assumeTrue(!repo.isInvalid());
		statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, null);
	}
	
	@Test
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
	
	@Test
	public void testStatusCommand() throws Exception {
		final HgStatusCommand sc = new HgStatusCommand(repo).all();
		StatusCollector r;
		statusParser.reset();
		eh.run("hg", "status", "-A");
		sc.execute(r = new StatusCollector());
		report("hg status -A", r);
		//
		statusParser.reset();
		int revision = 3;
		eh.run("hg", "status", "-A", "--rev", String.valueOf(revision));
		sc.base(revision).execute(r = new StatusCollector());
		report("status -A --rev " + revision, r);
		//
		statusParser.reset();
		eh.run("hg", "status", "-A", "--change", String.valueOf(revision));
		sc.base(TIP).revision(revision).execute(r = new StatusCollector());
		report("status -A --change " + revision, r);
		
		// TODO check not -A, but defaults()/custom set of modifications 
	}
	
	private static class StatusCollector implements HgStatusCommand.Handler {
		private final Map<HgStatus.Kind, List<Path>> map = new TreeMap<HgStatus.Kind, List<Path>>();

		public void handleStatus(HgStatus s) {
			List<Path> l = map.get(s.getKind());
			if (l == null) {
				l = new LinkedList<Path>();
				map.put(s.getKind(), l);
			}
			l.add(s.getPath());
		}
		
		public List<Path> get(Kind k) {
			List<Path> rv = map.get(k);
			if (rv == null) {
				return Collections.emptyList();
			}
			return rv;
		}
	}
	
	public void testRemovedAgainstNonTip() {
		/*
		 status --rev N when a file added past revision N was removed ((both physically and in dirstate), but not yet committed

		 Reports extra REMOVED file (the one added and removed in between). Shall not
		 */
	}
	
	/*
	 * With warm-up of previous tests, 10 runs, time in milliseconds
	 * 'hg status -A': Native client total 953 (95 per run), Java client 94 (9)
	 * 'hg status -A --rev 3:80': Native client total 1828 (182 per run), Java client 235 (23)
	 * 'hg log --debug', 10 runs: Native client total 1766 (176 per run), Java client 78 (7)
	 * 
	 * 18.02.2011
	 * 'hg status -A --rev 3:80', 10 runs:  Native client total 2000 (200 per run), Java client 250 (25)
	 * 'hg log --debug', 10 runs: Native client total 2297 (229 per run), Java client 125 (12)
	 */
	public void testPerformance() throws Exception {
		final int runs = 10;
		final long start1 = System.currentTimeMillis();
		for (int i = 0; i < runs; i++) {
			statusParser.reset();
			eh.run("hg", "status", "-A", "--rev", "3:80");
		}
		final long start2 = System.currentTimeMillis();
		for (int i = 0; i < runs; i++) {
			StatusCollector r = new StatusCollector();
			new HgStatusCommand(repo).all().base(3).revision(80).execute(r);
		}
		final long end = System.currentTimeMillis();
		System.out.printf("'hg status -A --rev 3:80', %d runs:  Native client total %d (%d per run), Java client %d (%d)\n", runs, start2-start1, (start2-start1)/runs, end-start2, (end-start2)/runs);
	}
	
	private void report(String what, StatusCollector r) {
		reportNotEqual(what + "#MODIFIED", r.get(Modified), statusParser.getModified());
		reportNotEqual(what + "#ADDED", r.get(Added), statusParser.getAdded());
		reportNotEqual(what + "#REMOVED", r.get(Removed), statusParser.getRemoved());
		reportNotEqual(what + "#CLEAN", r.get(Clean), statusParser.getClean());
		reportNotEqual(what + "#IGNORED", r.get(Ignored), statusParser.getIgnored());
		reportNotEqual(what + "#MISSING", r.get(Missing), statusParser.getMissing());
		reportNotEqual(what + "#UNKNOWN", r.get(Unknown), statusParser.getUnknown());
		// FIXME test copies
	}

	private void report(String what, HgStatusCollector.Record r, StatusOutputParser statusParser) {
		reportNotEqual(what + "#MODIFIED", r.getModified(), statusParser.getModified());
		reportNotEqual(what + "#ADDED", r.getAdded(), statusParser.getAdded());
		reportNotEqual(what + "#REMOVED", r.getRemoved(), statusParser.getRemoved());
		reportNotEqual(what + "#CLEAN", r.getClean(), statusParser.getClean());
		reportNotEqual(what + "#IGNORED", r.getIgnored(), statusParser.getIgnored());
		reportNotEqual(what + "#MISSING", r.getMissing(), statusParser.getMissing());
		reportNotEqual(what + "#UNKNOWN", r.getUnknown(), statusParser.getUnknown());
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
		errorCollector.checkThat(what + "#Non-matching 'copied' keys: ", copiedKeyDiff, equalTo(Collections.<Path>emptyList()));
		errorCollector.checkThat(what + "#COPIED", copyDiff, equalTo(Collections.<Path,String>emptyMap()));
	}
	
	private <T> void reportNotEqual(String what, Collection<T> l1, Collection<T> l2) {
		List<T> diff = difference(l1, l2);
		errorCollector.checkThat(what, diff, equalTo(Collections.<T>emptyList()));
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
