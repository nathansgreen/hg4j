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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.tmatesoft.hg.core.HgStatus.Kind.*;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgStatus;
import org.tmatesoft.hg.core.HgStatus.Kind;
import org.tmatesoft.hg.core.HgStatusCommand;
import org.tmatesoft.hg.internal.PathGlobMatcher;
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

	private HgRepository repo;
	private StatusOutputParser statusParser;
	private ExecHelper eh;

	public static void main(String[] args) throws Throwable {
		TestStatus test = new TestStatus();
		test.testLowLevel();
		test.testStatusCommand();
		test.testPerformance();
		test.errorCollector.verify();
		//
		TestStatus t2 = new TestStatus(new HgLookup().detect("/temp/hg/hg4j-merging/hg4j"));
		t2.testDirstateParentOtherThanTip(238);
		t2.errorCollector.verify();
		TestStatus t3 = new TestStatus(new HgLookup().detect("/temp/hg/cpython"));
		t3.testDirstateParentOtherThanTip(-1);
		t3.errorCollector.verify();
	}
	
	public TestStatus() throws Exception {
		this(new HgLookup().detectFromWorkingDir());
	}

	private TestStatus(HgRepository hgRepo) {
		repo = hgRepo;
		Assume.assumeTrue(!repo.isInvalid());
		statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, hgRepo.getWorkingDir());
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

	/**
	 * hg up --rev <earlier rev>; hg status
	 * 
	 * To check if HgWorkingCopyStatusCollector respects actual working copy parent (takes from dirstate)
	 * and if status is calculated correctly 
	 */
	@Test
	@Ignore("modifies test repository, needs careful configuration")
	public void testDirstateParentOtherThanTip(int revToUpdate) throws Exception {
		final HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(repo);
		statusParser.reset();
		try {
			if (revToUpdate != -1) {
				// there are repositories (like cpython) where WC is not tip-based, and no need to
				// accomplish that artificially 
				eh.run("hg", "up", "--rev", String.valueOf(revToUpdate));
			}
			//
			eh.run("hg", "status", "-A");
			HgStatusCollector.Record r = wcc.status(HgRepository.TIP);
			report("hg status -A", r, statusParser);
			//
			statusParser.reset();
			int revision = 3;
			eh.run("hg", "status", "-A", "--rev", String.valueOf(revision));
			r = wcc.status(revision);
			report("status -A --rev " + revision, r, statusParser);
		} finally {
			if (revToUpdate != -1) {
				// bring the repository to the tip just in case anyone else is using it afterwards
				eh.run("hg", "up");
			}
		}
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
		private final Map<Kind, List<Path>> kind2names = new TreeMap<Kind, List<Path>>();
		private final Map<Path, List<Kind>> name2kinds = new TreeMap<Path, List<Kind>>();

		public void handleStatus(HgStatus s) {
			List<Path> l = kind2names.get(s.getKind());
			if (l == null) {
				kind2names.put(s.getKind(), l = new LinkedList<Path>());
			}
			l.add(s.getPath());
			//
			List<Kind> k = name2kinds.get(s.getPath());
			if (k == null) {
				name2kinds.put(s.getPath(), k = new LinkedList<Kind>());
			}
			k.add(s.getKind());
		}
		
		public List<Path> get(Kind k) {
			List<Path> rv = kind2names.get(k);
			return rv == null ? Collections.<Path>emptyList() : rv;
		}
		
		public List<Kind> get(Path p) {
			List<Kind> rv = name2kinds.get(p);
			return rv == null ? Collections.<Kind>emptyList() : rv;
		}
	}

	/*
	 * status-1/dir/file5 was added in rev 8, scheduled (hg remove file5) for removal, but not yet committed
	 * Erroneously reported extra REMOVED file (the one added and removed in between). Shall not
	 */
	@Test
	public void testRemovedAgainstBaseWithoutIt() throws Exception {
		// check very end of WCStatusCollector, foreach left knownEntry, collect == null || baseRevFiles.contains()
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		StatusCollector sc = new StatusCollector();
		cmd.all().base(7).execute(sc);
		Path file5 = Path.create("dir/file5");
		// shall not be listed at all
		assertTrue(sc.get(file5).isEmpty());
	}
	
	/*
	 * status-1/file2 is tracked, but later .hgignore got entry to ignore it, file2 got modified
	 * HG doesn't respect .hgignore for tracked files.
	 * Now reported as ignored and missing(?!).
	 * Shall be reported as modified.
	 */
	@Test
	public void testTrackedModifiedIgnored() throws Exception {
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		StatusCollector sc = new StatusCollector();
		cmd.all().execute(sc);
		final Path file2 = Path.create("file2");
		assertTrue(sc.get(file2).contains(Modified));
		assertTrue(sc.get(file2).size() == 1);
	}

	/*
	 * status/dir/file4, added in rev 3, has been scheduled for removal (hg remove -Af file4), but still there in the WC.
	 * Shall be reported as Removed, when comparing against rev 3 
	 * (despite both rev 3 and WC's parent has file4,  there are different paths in the code for wc against parent and wc against rev)
	 */
	@Test
	public void testMarkedRemovedButStillInWC() throws Exception {
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		StatusCollector sc = new StatusCollector();
		cmd.all().execute(sc);
		Path file4 = Path.create("dir/file4");
		assertTrue(sc.get(file4).contains(Removed));
		assertTrue(sc.get(file4).size() == 1);
		//
		// different code path (collect != null)
		cmd.base(3).execute(sc = new StatusCollector());
		assertTrue(sc.get(file4).contains(Removed));
		assertTrue(sc.get(file4).size() == 1);
		//
		// wasn't there in rev 2, shall not be reported at all
		cmd.base(2).execute(sc = new StatusCollector());
		assertTrue(sc.get(file4).isEmpty());
	}

	/*
	 * status-1/dir/file3 tracked, listed in .hgignore since rev 4, removed (hg remove file3)  from repo and WC 
	 * (but entry in .hgignore left) in revision 5, and new file3 got created in WC.
	 * Shall be reported as ignored when comparing against WC's parent,
	 * and both ignored and removed when comparing against revision 3 
	 */
	@Test
	public void testRemovedIgnoredInWC() throws Exception {
		// check branch !known, ignored
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		StatusCollector sc = new StatusCollector();
		cmd.all().execute(sc);
		final Path file3 = Path.create("dir/file3");
		assertTrue(sc.get(file3).contains(Ignored));
		assertTrue(sc.get(file3).size() == 1);
		//
		cmd.base(3).execute(sc = new StatusCollector());
		assertTrue(sc.get(file3).contains(Ignored));
		assertTrue(sc.get(file3).contains(Removed));
		assertTrue(sc.get(file3).size() == 2);
		//
		cmd.base(5).execute(sc = new StatusCollector());
		assertTrue(sc.get(file3).contains(Ignored));
		assertTrue(sc.get(file3).size() == 1);
		//
		cmd.base(0).execute(sc = new StatusCollector());
		assertTrue(sc.get(file3).contains(Ignored));
		assertTrue(sc.get(file3).size() == 1);

	}

	/*
	 * status/file1 was removed in cset 2. New file with the same name in the WC.
	 * Shall report 2 statuses (as cmdline hg does): unknown and removed when comparing against that revision. 
	 */
	@Test
	public void testNewFileWithSameNameAsDeletedOld() throws Exception {
		// check branch !known, !ignored (=> unknown)
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		StatusCollector sc = new StatusCollector();
		cmd.base(1);
		cmd.all().execute(sc);
		final Path file1 = Path.create("file1");
		assertTrue(sc.get(file1).contains(Unknown));
		assertTrue(sc.get(file1).contains(Removed));
		assertTrue(sc.get(file1).size() == 2);
		// 
		// no file1 in rev 2, shall be reported as unknown only
		cmd.base(2).execute(sc = new StatusCollector());
		assertTrue(sc.get(file1).contains(Unknown));
		assertTrue(sc.get(file1).size() == 1);
	}
	
	@Test
	public void testSubTreeStatus() throws Exception {
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		StatusCollector sc = new StatusCollector();
		cmd.match(new PathGlobMatcher("*"));
		cmd.all().execute(sc);
		/*
		 * C .hgignore
		 * ? file1
		 * M file2
		 * C readme
		 */
		final Path file1 = Path.create("file1");
		assertTrue(sc.get(file1).contains(Unknown));
		assertTrue(sc.get(file1).size() == 1);
		assertTrue(sc.get(Removed).isEmpty());
		assertTrue(sc.get(Clean).size() == 2);
		assertTrue(sc.get(Modified).size() == 1);
		//
		cmd.match(new PathGlobMatcher("dir/*")).execute(sc = new StatusCollector());
		/*
		 * I dir/file3
		 * R dir/file4
		 * R dir/file5
		 */
		assertTrue(sc.get(Modified).isEmpty());
		assertTrue(sc.get(Added).isEmpty());
		assertTrue(sc.get(Ignored).size() == 1);
		assertTrue(sc.get(Removed).size() == 2);
	}
	
	
	@Test
	public void testSpecificFileStatus() throws Exception {
		repo = Configuration.get().find("status-1");
		// files only
		final Path file2 = Path.create("file2");
		final Path file3 = Path.create("dir/file3");
		HgWorkingCopyStatusCollector sc = HgWorkingCopyStatusCollector.create(repo, file2, file3);
		HgStatusCollector.Record r = new HgStatusCollector.Record();
		sc.walk(TIP, r);
		assertTrue(r.getAdded().isEmpty());
		assertTrue(r.getRemoved().isEmpty());
		assertTrue(r.getUnknown().isEmpty());
		assertTrue(r.getClean().isEmpty());
		assertTrue(r.getMissing().isEmpty());
		assertTrue(r.getCopied().isEmpty());
		assertTrue(r.getIgnored().contains(file3));
		assertTrue(r.getIgnored().size() == 1);
		assertTrue(r.getModified().contains(file2));
		assertTrue(r.getModified().size() == 1);
		// mix files and directories
		final Path readme = Path.create("readme");
		final Path dir = Path.create("dir/");
		sc = HgWorkingCopyStatusCollector.create(repo, readme, dir);
		sc.walk(TIP, r = new HgStatusCollector.Record());
		assertTrue(r.getAdded().isEmpty());
		assertTrue(r.getRemoved().size() == 2);
		for (Path p : r.getRemoved()) {
			assertEquals(p.compareWith(dir), Path.CompareResult.Nested);
		}
		assertTrue(r.getUnknown().isEmpty());
		assertTrue(r.getClean().size() == 1);
		assertTrue(r.getClean().contains(readme));
		assertTrue(r.getMissing().isEmpty());
		assertTrue(r.getCopied().isEmpty());
		assertTrue(r.getIgnored().contains(file3));
		assertTrue(r.getIgnored().size() == 1);
		assertTrue(r.getModified().isEmpty());
	}
	
	@Test
	public void testSameResultDirectPathVsMatcher() throws Exception {
		repo = Configuration.get().find("status-1");
		final Path file3 = Path.create("dir/file3");
		final Path file5 = Path.create("dir/file5");
		
		HgWorkingCopyStatusCollector sc = HgWorkingCopyStatusCollector.create(repo, file3, file5);
		HgStatusCollector.Record r;
		sc.walk(TIP, r = new HgStatusCollector.Record());
		assertTrue(r.getRemoved().contains(file5));
		assertTrue(r.getIgnored().contains(file3));
		//
		// query for the same file, but with
		sc = HgWorkingCopyStatusCollector.create(repo, new PathGlobMatcher(file3.toString(), file5.toString()));
		sc.walk(TIP, r = new HgStatusCollector.Record());
		assertTrue(r.getRemoved().contains(file5));
		assertTrue(r.getIgnored().contains(file3));
	}
	
	@Test
	public void testScopeInHistoricalStatus() throws Exception {
		repo = Configuration.get().find("status-1");
		HgStatusCommand cmd = new HgStatusCommand(repo);
		cmd.base(3).revision(8).all();
		cmd.match(new PathGlobMatcher("dir/*"));
		StatusCollector sc = new StatusCollector();
		cmd.execute(sc);
		final Path file3 = Path.create("dir/file3");
		final Path file4 = Path.create("dir/file4");
		final Path file5 = Path.create("dir/file5");
		//
		assertTrue(sc.get(file3).contains(Removed));
		assertTrue(sc.get(file3).size() == 1);
		assertTrue(sc.get(Removed).size() == 1);
		//
		assertTrue(sc.get(file4).contains(Clean));
		assertTrue(sc.get(file4).size() == 1);
		assertTrue(sc.get(Clean).size() == 1);
		//
		assertTrue(sc.get(file5).contains(Added));
		assertTrue(sc.get(file5).size() == 1);
		assertTrue(sc.get(Added).size() == 1);

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
	 * 
	 * 9.3.2011 (DataAccess instead of byte[] in ReflogStream.Inspector
	 * 'hg status -A',				10 runs:  Native client total 1516 (151 per run), Java client 219 (21)
	 * 'hg status -A --rev 3:80',	10 runs:  Native client total 1875 (187 per run), Java client 3187 (318) (!!! ???)
	 * 'hg log --debug',			10 runs: Native client total 2484 (248 per run), Java client 344 (34)
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
	
	private <T extends Comparable<? super T>> void reportNotEqual(String what, Collection<T> l1, Collection<T> l2) {
//		List<T> diff = difference(l1, l2);
//		errorCollector.checkThat(what, diff, equalTo(Collections.<T>emptyList()));
		ArrayList<T> sl1 = new ArrayList<T>(l1);
		Collections.sort(sl1);
		ArrayList<T> sl2 = new ArrayList<T>(l2);
		Collections.sort(sl2);
		errorCollector.checkThat(what, sl1, equalTo(sl2));
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
