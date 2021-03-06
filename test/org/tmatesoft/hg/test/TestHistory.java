/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.tmatesoft.hg.core.HgIterateDirection.NewToOld;
import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgChangesetTreeHandler;
import org.tmatesoft.hg.core.HgFileRenameHandlerMixin;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgLogCommand.CollectHandler;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.AdapterPlug;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.test.LogOutputParser.Record;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestHistory {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	private final ExecHelper eh;
	private LogOutputParser changelogParser;
	
	public static void main(String[] args) throws Throwable {
		TestHistory th = new TestHistory(new HgLookup().detectFromWorkingDir());
		th.testCompleteLog();
		th.testFollowHistory();
		th.errorCollector.verify();
//		th.testPerformance();
		th.testOriginalTestLogRepo();
		th.testUsernames();
		th.testBranches();
		//
		th.errorCollector.verify();
	}
	
	public TestHistory() {
		eh = new ExecHelper(changelogParser = new LogOutputParser(true), null);
	}

	private TestHistory(HgRepository hgRepo) {
		this();
		repo = hgRepo;
		eh.cwd(repo.getWorkingDir());
	}
	
	@Test
	public void testCompleteLog() throws Exception {
		if (repo == null) {
			repo = Configuration.get().own();
			eh.cwd(repo.getWorkingDir());
		}
		changelogParser.reset();
		eh.run("hg", "log", "--debug");
		List<HgChangeset> r = new HgLogCommand(repo).execute();
		report("hg log - COMPLETE REPO HISTORY", r, true);
		
		r = new HgLogCommand(repo).order(NewToOld).execute();
		report("hg log - COMPLETE REPO HISTORY, FROM NEW TO OLD", r, false);
	}
	
	@Test
	public void testFollowHistory() throws Exception {
		if (repo == null) {
			repo = Configuration.get().own();
			eh.cwd(repo.getWorkingDir());
		}
		final Path f = Path.create("cmdline/org/tmatesoft/hg/console/Remote.java");
		assertTrue(repo.getFileNode(f).exists());
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "--follow", f.toString());
		
		CollectWithRenameHandler h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(f, true).execute(h);
		errorCollector.assertEquals(1, h.rh.renames.size());
		HgFileRevision from = h.rh.renames.get(0).first();
		boolean fromMatched = "src/com/tmate/hgkit/console/Remote.java".equals(from.getPath().toString());
		String what = "hg log - FOLLOW FILE HISTORY";
		errorCollector.checkThat(what + "#copyReported ", h.rh.copyReported, is(true));
		errorCollector.checkThat(what + "#copyFromMatched", fromMatched, is(true));
		//
		// cmdline always gives in changesets in order from newest (bigger rev number) to oldest.
		// LogCommand does other way round, from oldest to newest, follewed by revisions of copy source, if any
		// (apparently older than oldest of the copy target). Hence need to sort Java results according to rev numbers
		final LinkedList<HgChangeset> sorted = new LinkedList<HgChangeset>(h.getChanges());
		Collections.sort(sorted, new Comparator<HgChangeset>() {
			public int compare(HgChangeset cs1, HgChangeset cs2) {
				return cs1.getRevisionIndex() < cs2.getRevisionIndex() ? 1 : -1;
			}
		});
		report(what, sorted, false);
	}
	
	@Test
	public void testChangesetTree() throws Exception {
		repo = Configuration.get().find("branches-1");
		final String fname = "file1";
		assertTrue("[sanity]", repo.getFileNode(fname).exists());
		eh.run("hg", "log", "--debug", fname, "--cwd", repo.getLocation());
		
		TreeCollectHandler h = new TreeCollectHandler(false);
		new HgLogCommand(repo).file(fname, false).execute(h);
		// since we use TreeCollectHandler with natural order (older to newer), shall reverse console result in report()
		report("execute with HgChangesetTreeHandler(follow == false)", h.getResult(), true);
	}
	
	/**
	 * Few tests  to check newly introduced followAncestry parameter to HgLogCommand:
	 * followRename: true,	followAncestry: false
	 * followRename: false,	followAncestry: true
	 * followRename: true,	followAncestry: true
	 * Perhaps, shall be merged with {@link #testFollowHistory()}
	 */
	@Test
	public void testFollowRenamesNotAncestry() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname1 = "file1_a";
		final String fname2 = "file1_b";
		assertTrue("[sanity]", repo.getFileNode(fname2).exists());
		// no --follow, but two names we know have been the same file (fname1 renamed to fname2)
		// sequentially gives follow rename semantics without ancestry
		eh.run("hg", "log", "--debug", fname2, fname1, "--cwd", repo.getLocation());
		
		CollectWithRenameHandler h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(fname2, true, false).execute(h);
		errorCollector.assertEquals(1, h.rh.renames.size());
		Pair<HgFileRevision, HgFileRevision> rename = h.rh.renames.get(0);
		errorCollector.assertEquals(fname1, rename.first().getPath().toString());
		errorCollector.assertEquals(fname2, rename.second().getPath().toString());
		// Ensure rename info came in the right moment
		errorCollector.assertEquals(1, h.lastChangesetReportedAtRename.size());
		// Command iterates old to new, rename comes after last fname1 revision. Since we don't follow
		// ancestry, it's the very last revision in fname1 history
		String lastRevOfFname1 = "369c0882d477c11424a62eb4b791e86d1d4b6769";
		errorCollector.assertEquals(lastRevOfFname1, h.lastChangesetReportedAtRename.get(0).getNodeid().toString());
		report("HgChangesetHandler(renames: true, ancestry:false)", h.getChanges(), true);
		//
		// Direction
		h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(fname2, true, false).order(NewToOld).execute(h);
		// Identical rename shall be reported, at the same moment 
		errorCollector.assertEquals(1, h.rh.renames.size());
		rename = h.rh.renames.get(0);
		errorCollector.assertEquals(fname1, rename.first().getPath().toString());
		errorCollector.assertEquals(fname2, rename.second().getPath().toString());
		errorCollector.assertEquals(1, h.lastChangesetReportedAtRename.size());
		// new to old, recently reported would be the very first revision fname2 pops up
		String firstRevOfFname2 = "27e7a69373b74d42e75f3211e56510ff17d01370";
		errorCollector.assertEquals(firstRevOfFname2, h.lastChangesetReportedAtRename.get(0).getNodeid().toString());
		report("HgChangesetHandler(renames: true, ancestry:false)", h.getChanges(), false);
		//
		// TreeChangeHandler - in #testChangesetTreeFollowRenamesNotAncestry
	}
	
	@Test
	public void testChangesetTreeFollowRenamesNotAncestry() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname1 = "file1_a";
		final String fname2 = "file1_b";
		assertTrue("[sanity]", repo.getFileNode(fname2).exists());
		// no --follow, but two names we know have been the same file (fname1 renamed to fname2)
		// sequentially gives follow rename semantics without ancestry
		eh.run("hg", "log", "--debug", fname2, fname1, "--cwd", repo.getLocation());
		
		TreeCollectHandler h = new TreeCollectHandler(true);
		RenameCollector rh = new RenameCollector(h);
		// can't check that prev revision is in parent because there are forks in
		// file history (e.g. rev2 and rev3 (that comes next) both have rev0 as their parent
		// and followAncestry is false
		// h.checkPrevInParents = true; 
		new HgLogCommand(repo).file(fname2, true, false).execute(h);
		errorCollector.assertEquals(1, rh.renames.size());
		Pair<HgFileRevision, HgFileRevision> rename = rh.renames.get(0);
		errorCollector.assertEquals(fname1, rename.first().getPath().toString());
		errorCollector.assertEquals(fname2, rename.second().getPath().toString());
		report("HgChangesetTreeHandler(renames: true, ancestry:false)", h.getResult(), false);
		
		// Direction
		h = new TreeCollectHandler(false);
		rh = new RenameCollector(h);
		// h.checkPrevInChildren = true; see above
		new HgLogCommand(repo).file(fname2, true, false).order(NewToOld).execute(h);
		errorCollector.assertEquals(1, rh.renames.size());
		rename = rh.renames.get(0);
		errorCollector.assertEquals(fname1, rename.first().getPath().toString());
		errorCollector.assertEquals(fname2, rename.second().getPath().toString());
		report("HgChangesetTreeHandler(renames: true, ancestry:false)", h.getResult(), false);
	}
		
	@Test
	public void testFollowAncestryNotRenames() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname2 = "file1_b";
		assertTrue("[sanity]", repo.getFileNode(fname2).exists());
		final List<Record> fname2Follow = getAncestryWithoutRenamesFromCmdline(fname2);
		
		CollectWithRenameHandler h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(fname2, false, true).execute(h);
		// renames are reported regardless of followRenames parameter, but 
		// solely based on HgFileRenameHandlerMixin
		errorCollector.assertEquals(1, h.rh.renames.size());
		report("HgChangesetHandler(renames: false, ancestry:true)", h.getChanges(), fname2Follow, true, errorCollector);
		//
		// Direction
		h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(fname2, false, true).order(NewToOld).execute(h);
		report("HgChangesetHandler(renames: false, ancestry:true)", h.getChanges(), fname2Follow, false/*!!!*/, errorCollector);
		//
		// TreeChangeHandler - in #testChangesetTreeFollowAncestryNotRenames
	}

	@Test
	public void testChangesetTreeFollowAncestryNotRenames() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname2 = "file1_b";
		final List<Record> fname2Follow = getAncestryWithoutRenamesFromCmdline(fname2);
		
		TreeCollectHandler h = new TreeCollectHandler(false);
		h.checkPrevInParents = true;
		new HgLogCommand(repo).file(fname2, false, true).execute(h);
		report("HgChangesetTreeHandler(renames: false, ancestry:true)", h.getResult(), fname2Follow, true, errorCollector);
		
		// Direction
		h = new TreeCollectHandler(false);
		h.checkPrevInChildren = true;
		new HgLogCommand(repo).file(fname2, false, true).order(NewToOld).execute(h);
		report("HgChangesetTreeHandler(renames: false, ancestry:true)", h.getResult(), fname2Follow, false, errorCollector);
	}

	
	private List<Record> getAncestryWithoutRenamesFromCmdline(String fname2) throws Exception {
		// to get "followed" history of fname2 only (without fname1 origin),
		// get the complete history and keep there only elements that match fname2 own history 
		eh.run("hg", "log", "--debug", "--follow", fname2, "--cwd", repo.getLocation());
		final List<Record> fname2Follow = new LinkedList<LogOutputParser.Record>(changelogParser.getResult());
		changelogParser.reset();
		eh.run("hg", "log", "--debug", fname2, "--cwd", repo.getLocation());
		// fname2Follow.retainAll(changelogParser.getResult());
		for (Iterator<Record> it = fname2Follow.iterator(); it.hasNext();) {
			Record r = it.next();
			boolean belongsToSoleFname2History = false;
			for (Record d : changelogParser.getResult()) {
				if (d.changesetIndex == r.changesetIndex) {
					assert d.changesetNodeid.equals(r.changesetNodeid) : "[sanity]";
					belongsToSoleFname2History = true;
					break;
				}
			}
			if (!belongsToSoleFname2History) {
				it.remove();
			}
		}
		return fname2Follow;
	}

	/**
	 * output identical to that of "hg log --follow"
	 */
	@Test
	public void testFollowBothRenameAndAncestry() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname1 = "file1_a";
		final String fname2 = "file1_b";
		assertTrue("[sanity]", repo.getFileNode(fname2).exists());
		eh.run("hg", "log", "--debug", "--follow", fname2, "--cwd", repo.getLocation());
		
		CollectWithRenameHandler h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(fname2, true, true).execute(h);
		errorCollector.assertEquals(1, h.rh.renames.size());
		Pair<HgFileRevision, HgFileRevision> rename = h.rh.renames.get(0);
		errorCollector.assertEquals(fname1, rename.first().getPath().toString());
		errorCollector.assertEquals(fname2, rename.second().getPath().toString());
		// Ensure rename info came in the right moment
		errorCollector.assertEquals(1, h.lastChangesetReportedAtRename.size());
		String fname1BranchRevision = "6e668ff2940acb250c8627843f8116166fe5d5cd";
		errorCollector.assertEquals(fname1BranchRevision, h.lastChangesetReportedAtRename.get(0).getNodeid().toString());
		// finally, match output
		report("HgChangesetHandler(renames: true, ancestry:true)", h.getChanges(), true);
		//
		// Switch direction and compare, order shall match that from console
		h = new CollectWithRenameHandler();
		new HgLogCommand(repo).file(fname2, true, true).order(NewToOld).execute(h);
		// Identical rename event shall be reported
		errorCollector.assertEquals(1, h.rh.renames.size());
		rename = h.rh.renames.get(0);
		errorCollector.assertEquals(fname1, rename.first().getPath().toString());
		errorCollector.assertEquals(fname2, rename.second().getPath().toString());
		// new to old, recently reported would be the very first revision fname2 pops up
		String firstRevOfFname2 = "27e7a69373b74d42e75f3211e56510ff17d01370";
		errorCollector.assertEquals(firstRevOfFname2, h.lastChangesetReportedAtRename.get(0).getNodeid().toString());
		report("HgChangesetHandler(renames: true, ancestry:true)", h.getChanges(), false /*do not reorder console results !!!*/);
		//
		// TreeChangeHandler in #testChangesetTreeFollowRenameAndAncestry
	}
	
	@Test
	public void testChangesetTreeFollowRenameAndAncestry() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname = "file1_b";
		assertTrue("[sanity]", repo.getFileNode(fname).exists());
		eh.run("hg", "log", "--debug", "--follow", fname, "--cwd", repo.getLocation());

		TreeCollectHandler h = new TreeCollectHandler(true);
		RenameCollector rh = new RenameCollector(h);
		h.checkPrevInParents = true;
		new HgLogCommand(repo).file(fname, true, true).execute(h);

		assertEquals(1, h.getAdapterUse(HgFileRenameHandlerMixin.class));
		
		report("execute with HgChangesetTreeHandler(follow == true)", h.getResult(), false);
		
		assertEquals(1, rh.renames.size());
		assertEquals(Path.create(fname), rh.renames.get(0).second().getPath());
	}
	
	/**
	 * Ensure {@link HgFileRenameHandlerMixin} is always notified, even
	 * if followRename is false.
	 * Shall check: 
	 *  both {@link HgLogCommand#execute(HgChangesetHandler)} and {@link HgLogCommand#execute(HgChangesetTreeHandler)}
	 *  and for both iteration directions in each case
	 */
	@Test
	public void testRenameHandlerNotifiedEvenIfNotFollowRename() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname1 = "file1_a";
		final String fname2 = "file1_b";
		final String fnameNoRename = "file2";
		assertTrue("[sanity]", repo.getFileNode(fnameNoRename).exists());
		
		// first, check that file without renames doesn't report any accidentally
		CollectWithRenameHandler h1 = new CollectWithRenameHandler();
		HgLogCommand cmd = new HgLogCommand(repo).file(fnameNoRename, false, false);
		cmd.execute(h1);
		errorCollector.assertEquals(0, h1.rh.renames.size());
		TreeCollectHandler h2 = new TreeCollectHandler(false);
		RenameCollector rh = new RenameCollector(h2);
		cmd.execute(h2);
		errorCollector.assertEquals(0, rh.renames.size());
		
		// check default iterate direction
		cmd = new HgLogCommand(repo).file(fname2, false, false);
		cmd.execute(h1 = new CollectWithRenameHandler());
		errorCollector.assertEquals(1, h1.rh.renames.size());
		assertRename(fname1, fname2, h1.rh.renames.get(0));
		
		h2 = new TreeCollectHandler(false);
		rh = new RenameCollector(h2);
		cmd.execute(h2);
		errorCollector.assertEquals(1, rh.renames.size());
		assertRename(fname1, fname2, rh.renames.get(0));
		
		eh.run("hg", "log", "--debug", fname2, "--cwd", repo.getLocation());
		report("HgChangesetHandler+RenameHandler with followRenames = false, default iteration order", h1.getChanges(), true);
		report("HgChangesetTreeHandler+RenameHandler with followRenames = false, default iteration order", h2.getResult(), true);
		
		//
		// Now, check that iteration in opposite direction (new to old)
		// still reports renames (and correct revisions, too)
		cmd.order(HgIterateDirection.NewToOld);
		cmd.execute(h1 = new CollectWithRenameHandler());
		errorCollector.assertEquals(1, h1.rh.renames.size());
		assertRename(fname1, fname2, h1.rh.renames.get(0));
		h2 = new TreeCollectHandler(false);
		rh = new RenameCollector(h2);
		cmd.execute(h2);
		errorCollector.assertEquals(1, rh.renames.size());
		assertRename(fname1, fname2, rh.renames.get(0));
		report("HgChangesetHandler+RenameHandler with followRenames = false, new2old iteration order", h1.getChanges(), false);
		report("HgChangesetTreeHandler+RenameHandler with followRenames = false, new2old iteration order", h2.getResult(), false);
	}

	@Test
	public void testFollowMultipleRenames() throws Exception {
		repo = Configuration.get().find("log-renames");
		String fname = "a";
		eh.run("hg", "log", "--debug", "--follow", fname, "--cwd", repo.getLocation());
		HgLogCommand cmd = new HgLogCommand(repo);
		cmd.file(fname, true, true);
		CollectWithRenameHandler h1;
		//
		cmd.order(OldToNew).execute(h1 = new CollectWithRenameHandler());
		errorCollector.assertEquals(2, h1.rh.renames.size());
		report("Follow a->c->b, old2new:", h1.getChanges(), true);
		//
		cmd.order(NewToOld).execute(h1 = new CollectWithRenameHandler());
		errorCollector.assertEquals(2, h1.rh.renames.size());
		report("Follow a->c->b, new2old:", h1.getChanges(), false);
		//
		//
		TreeCollectHandler h2 = new TreeCollectHandler(false);
		RenameCollector rh = new RenameCollector(h2);
		cmd.order(OldToNew).execute(h2);
		errorCollector.assertEquals(2, rh.renames.size());
		report("Tree. Follow a->c->b, old2new:", h2.getResult(), true);
		//
		h2 = new TreeCollectHandler(false);
		rh = new RenameCollector(h2);
		cmd.order(NewToOld).execute(h2);
		errorCollector.assertEquals(2, rh.renames.size());
		report("Tree. Follow a->c->b, new2old:", h2.getResult(), false);
	}
	
	private void assertRename(String fnameFrom, String fnameTo, Pair<HgFileRevision, HgFileRevision> rename) {
		errorCollector.assertEquals(fnameFrom, rename.first().getPath().toString());
		errorCollector.assertEquals(fnameTo, rename.second().getPath().toString());
	}

	/**
	 * @see TestAuxUtilities#testChangelogCancelSupport()
	 */
	@Test
	public void testLogCommandCancelSupport() throws Exception {
		repo  = Configuration.get().find("branches-1"); // any repo with more revisions
		class BaseCancel extends TestAuxUtilities.CancelAtValue implements HgChangesetHandler {
			BaseCancel(int limit) {
				super(limit);
			}
			public void cset(HgChangeset changeset) throws HgCallbackTargetException {
				nextValue(changeset.getRevisionIndex());
			}
		};
		class ImplementsCancel extends BaseCancel implements CancelSupport {
			ImplementsCancel(int limit) {
				super(limit);
			}
			public void checkCancelled() throws CancelledException {
				cancelImpl.checkCancelled();
			}
		};
		class AdaptsToCancel extends BaseCancel implements Adaptable {
			AdaptsToCancel(int limit) {
				super(limit);
			}
			public <T> T getAdapter(Class<T> adapterClass) {
				if (adapterClass == CancelSupport.class) {
					return adapterClass.cast(cancelImpl);
				}
				return null;
			}
		}

		BaseCancel insp = new ImplementsCancel(3);
		try {
			new HgLogCommand(repo).execute(insp);
			errorCollector.fail("CancelSupport as implemented iface");
		} catch (CancelledException ex) {
			errorCollector.assertEquals("CancelSupport as implemented iface", insp.stopValue, insp.lastSeen);
		}
		insp = new AdaptsToCancel(5);
		try {
			new HgLogCommand(repo).execute(insp);
			errorCollector.fail("Adaptable to CancelSupport");
		} catch (CancelledException ex) { 
			errorCollector.assertEquals("Adaptable to CancelSupport", insp.stopValue, insp.lastSeen);
		}
		insp = new BaseCancel(9);
		try {
			new HgLogCommand(repo).set(insp.cancelImpl).execute(insp);
			errorCollector.fail("cmd#set(CancelSupport)");
		} catch (CancelledException e) {
			errorCollector.assertEquals("cmd#set(CancelSupport)", insp.stopValue, insp.lastSeen);
		}
	}

	private void report(String what, List<HgChangeset> r, boolean reverseConsoleResult) {
		final List<Record> consoleResult = changelogParser.getResult();
		report(what, r, consoleResult, reverseConsoleResult, errorCollector);
	}
	
	static void report(String what, List<HgChangeset> hg4jResult, List<Record> consoleResult, boolean reverseConsoleResult, ErrorCollectorExt errorCollector) {
		consoleResult = new ArrayList<Record>(consoleResult); // need a copy in case callee would use result again
		if (reverseConsoleResult) {
			Collections.reverse(consoleResult);
		}
		errorCollector.checkThat(what + ". Number of changeset reported didn't match", hg4jResult.size(), equalTo(consoleResult.size()));
		Iterator<Record> consoleResultItr = consoleResult.iterator();
		for (HgChangeset cs : hg4jResult) {
			if (!consoleResultItr.hasNext()) {
				errorCollector.addError(new AssertionError("Ran out of console results while there are still hg4j results"));
				break;
			}
			Record cr = consoleResultItr.next();
			// flags, not separate checkThat() because when lists are large, and do not match,
			// number of failures may slow down test process significantly
			int x = cs.getRevisionIndex() == cr.changesetIndex ? 0x1 : 0;
			x |= cs.getDate().toString().equals(cr.date) ? 0x2 : 0;
			x |= cs.getNodeid().toString().equals(cr.changesetNodeid) ? 0x4 : 0;
			x |= cs.getUser().equals(cr.user) ? 0x8 : 0;
			// need to do trim() on comment because command-line template does, and there are
			// repositories that have couple of newlines in the end of the comment (e.g. hello sample repo from the book) 
			x |= cs.getComment().trim().equals(cr.description) ? 0x10 : 0;
			errorCollector.checkThat(String.format(what + ". Mismatch (0x%x) in %d hg4j rev comparing to %d cmdline's.", x, cs.getRevisionIndex(), cr.changesetIndex), x, equalTo(0x1f));
			consoleResultItr.remove();
		}
		errorCollector.checkThat(what + ". Unprocessed results in console left (insufficient from hg4j)", consoleResultItr.hasNext(), equalTo(false));
	}

	public void testPerformance() throws Exception {
		final int runs = 10;
		final long start1 = System.currentTimeMillis();
		for (int i = 0; i < runs; i++) {
			changelogParser.reset();
			eh.run("hg", "log", "--debug");
		}
		final long start2 = System.currentTimeMillis();
		for (int i = 0; i < runs; i++) {
			new HgLogCommand(repo).execute();
		}
		final long end = System.currentTimeMillis();
		System.out.printf("'hg log --debug', %d runs: Native client total %d (%d per run), Java client %d (%d)\n", runs, start2-start1, (start2-start1)/runs, end-start2, (end-start2)/runs);
	}

	@Test
	public void testOriginalTestLogRepo() throws Exception {
		// tests fro mercurial distribution, test-log.t
		repo = Configuration.get().find("log-1");
		HgLogCommand cmd = new HgLogCommand(repo);
		// funny enough, but hg log -vf a -R c:\temp\hg\test-log\a doesn't work, while --cwd <same> works fine
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "a", "--cwd", repo.getLocation());
		report("log a", cmd.file("a", false).execute(), true);
		//
		changelogParser.reset();
		// fails with Mercurial 2.2.1, @see http://selenic.com/pipermail/mercurial-devel/2012-February/038249.html
		// and http://www.selenic.com/hg/rev/60101427d618?rev=
		// fix for the test (replacement) is available below  
//		eh.run("hg", "log", "--debug", "-f", "a", "--cwd", repo.getLocation());
//		List<HgChangeset> r = cmd.file("a", true).execute();
//		report("log -f a", r, true);

		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-f", "e", "--cwd", repo.getLocation());
		report("log -f e", cmd.file("e", true).execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "dir/b", "--cwd", repo.getLocation());
		report("log dir/b", cmd.file("dir/b", false).execute(), true);
		//
		changelogParser.reset();
//		
//		Commented out for the same reason as above hg log -f a - newly introduced error message in Mercurial 2.2 
//		when files are not part of the parent revision
//		eh.run("hg", "log", "--debug", "-f", "dir/b", "--cwd", repo.getLocation());
//		report("log -f dir/b", cmd.file("dir/b", true).execute(), false /*#1, below*/);
		/*
		 * #1: false works because presently commands dispatches history of the queried file, and then history
		 * of it's origin. With history comprising of renames only, this effectively gives reversed (newest to oldest) 
		 * order of revisions. 
		 */

		// commented tests from above updated to work in 2.2 - update repo to revision where files are present
		eh.run("hg", "update", "-q", "-r", "2", "--cwd", repo.getLocation());
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-f", "a", "--cwd", repo.getLocation());
		List<HgChangeset> r = cmd.file("a", true).execute();
		report("log -f a", r, true);
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-f", "dir/b", "--cwd", repo.getLocation());
		report("log -f dir/b", cmd.file("dir/b", true).execute(), true);
		//
		// get repo back into clear state, up to the tip
		eh.run("hg", "update", "-q", "--cwd", repo.getLocation());
	}

	@Test
	public void testUsernames() throws Exception {
		repo = Configuration.get().find("log-users");
		final String user1 = "User One <user1@example.org>";
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-u", user1, "--cwd", repo.getLocation());
		report("log -u " + user1, new HgLogCommand(repo).user(user1).execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-u", "user1", "-u", "user2", "--cwd", repo.getLocation());
		report("log -u user1 -u user2", new HgLogCommand(repo).user("user1").user("user2").execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-u", "user3", "--cwd", repo.getLocation());
		report("log -u user3", new HgLogCommand(repo).user("user3").execute(), true);
	}

	@Test
	public void testBranches() throws Exception {
		repo = Configuration.get().find("log-branches");
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-b", "default", "--cwd", repo.getLocation());
		report("log -b default" , new HgLogCommand(repo).branch("default").execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-b", "test", "--cwd", repo.getLocation());
		report("log -b test" , new HgLogCommand(repo).branch("test").execute(), true);
		//
		assertTrue("log -b dummy shall yeild empty result", new HgLogCommand(repo).branch("dummy").execute().isEmpty());
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-b", "default", "-b", "test", "--cwd", repo.getLocation());
		report("log -b default -b test" , new HgLogCommand(repo).branch("default").branch("test").execute(), true);
	}

	////
	
	private final class TreeCollectHandler extends AdapterPlug implements HgChangesetTreeHandler {
		private final LinkedList<HgChangeset> cmdResult = new LinkedList<HgChangeset>();
		private final boolean reverseResult;
		boolean checkPrevInChildren = false; // true when iterating new to old
		boolean checkPrevInParents = false; // true when iterating old to new
		
		public TreeCollectHandler(boolean _reverseResult) {
			this.reverseResult = _reverseResult;
		}

		public List<HgChangeset> getResult() {
			return cmdResult;
		}
		

		public void treeElement(TreeElement entry) throws HgCallbackTargetException, HgRuntimeException {
			// check consistency
			Nodeid cset = entry.changeset().getNodeid();
			errorCollector.assertEquals(entry.changesetRevision(), cset);
			Pair<HgChangeset, HgChangeset> p = entry.parents();
			Pair<HgChangeset, HgChangeset> parents_a = p;
			Pair<Nodeid, Nodeid> parents_b = entry.parentRevisions();
			if (parents_b.first().isNull()) {
				errorCollector.assertTrue(parents_a.first() == null);
			} else {
				errorCollector.assertEquals(parents_b.first(), parents_a.first().getNodeid());
			}
			if (parents_b.second().isNull()) {
				errorCollector.assertTrue(parents_a.second() == null);
			} else {
				errorCollector.assertEquals(parents_b.second(), parents_a.second().getNodeid());
			}
			//
			if (checkPrevInChildren && !cmdResult.isEmpty()) {
				HgChangeset prevChangeset = reverseResult ? cmdResult.getFirst() : cmdResult.getLast();
				String msg = String.format("No parent-child bind between revisions %d and %d", prevChangeset.getRevisionIndex(), entry.changeset().getRevisionIndex());
				errorCollector.assertTrue(msg, entry.children().contains(prevChangeset));
			}
			if (checkPrevInParents && !cmdResult.isEmpty()) {
				HgChangeset prevChangeset = reverseResult ? cmdResult.getFirst() : cmdResult.getLast();
				String msg = String.format("No parent-child bind between revisions %d and %d", prevChangeset.getRevisionIndex(), entry.changeset().getRevisionIndex());
				errorCollector.assertTrue(msg, p.first() == prevChangeset || p.second() == prevChangeset);
			}
			//
			if (reverseResult) {
				cmdResult.addFirst(entry.changeset());
			} else {
				cmdResult.addLast(entry.changeset());
			}
		}
	}

	private static class CollectWithRenameHandler extends CollectHandler implements HgChangesetHandler.WithCopyHistory {
		public final RenameCollector rh = new RenameCollector();
		public List<HgChangeset> lastChangesetReportedAtRename = new LinkedList<HgChangeset>();

		public void copy(HgFileRevision from, HgFileRevision to) throws HgCallbackTargetException {
			Assert.assertTrue("Renames couldn't be reported prior to any change", getChanges().size() > 0);
			HgChangeset lastKnown = getChanges().get(getChanges().size() - 1);
			lastChangesetReportedAtRename.add(lastKnown);
			rh.copy(from, to);
		}
	};
	
	private static class RenameCollector implements HgFileRenameHandlerMixin {
		public boolean copyReported = false;
		public List<Pair<HgFileRevision, HgFileRevision>> renames = new LinkedList<Pair<HgFileRevision,HgFileRevision>>();
		
		public RenameCollector() {
		}
		
		public RenameCollector(AdapterPlug ap) {
			ap.attachAdapter(HgFileRenameHandlerMixin.class, this);
		}
		
		public void copy(HgFileRevision from, HgFileRevision to) {
			copyReported = true;
			renames.add(new Pair<HgFileRevision, HgFileRevision>(from, to));
		}
	}
}
