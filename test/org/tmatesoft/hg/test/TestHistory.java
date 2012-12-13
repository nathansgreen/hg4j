/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgChangesetTreeHandler;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgLogCommand.CollectHandler;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.test.LogOutputParser.Record;
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
		TestHistory th = new TestHistory();
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
	
	public TestHistory() throws Exception {
		this(new HgLookup().detectFromWorkingDir());
//		this(new HgLookup().detect("\\temp\\hg\\hello"));
	}

	private TestHistory(HgRepository hgRepo) {
		repo = hgRepo;
		eh = new ExecHelper(changelogParser = new LogOutputParser(true), repo.getWorkingDir());
		
	}

	@Test
	public void testCompleteLog() throws Exception {
		changelogParser.reset();
		eh.run("hg", "log", "--debug");
		List<HgChangeset> r = new HgLogCommand(repo).execute();
		report("hg log - COMPLETE REPO HISTORY", r, true); 
	}
	
	@Test
	public void testFollowHistory() throws Exception {
		final Path f = Path.create("cmdline/org/tmatesoft/hg/console/Remote.java");
		assertTrue(repo.getFileNode(f).exists());
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "--follow", f.toString());
		
		class H extends CollectHandler implements HgChangesetHandler.WithCopyHistory {
			boolean copyReported = false;
			boolean fromMatched = false;
			public void copy(HgFileRevision from, HgFileRevision to) {
				copyReported = true;
				fromMatched = "src/com/tmate/hgkit/console/Remote.java".equals(from.getPath().toString());
			}
		};
		H h = new H();
		new HgLogCommand(repo).file(f, true).execute(h);
		String what = "hg log - FOLLOW FILE HISTORY";
		errorCollector.checkThat(what + "#copyReported ", h.copyReported, is(true));
		errorCollector.checkThat(what + "#copyFromMatched", h.fromMatched, is(true));
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
	
	@Test
	public void testChangesetTreeFollowRename() throws Exception {
		repo = Configuration.get().find("log-follow");
		final String fname = "file1_b";
		assertTrue("[sanity]", repo.getFileNode(fname).exists());
		eh.run("hg", "log", "--debug", "--follow", fname, "--cwd", repo.getLocation());
		
		TreeCollectHandler h = new TreeCollectHandler(true);
		h.checkPrevInParents = true;
		new HgLogCommand(repo).file(fname, true).execute(h);
		
		report("execute with HgChangesetTreeHandler(follow == true)", h.getResult(), false);
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
		errorCollector.checkThat(what + ". Number of changeset reported didn't match", consoleResult.size(), equalTo(hg4jResult.size()));
		Iterator<Record> consoleResultItr = consoleResult.iterator();
		for (HgChangeset cs : hg4jResult) {
			if (!consoleResultItr.hasNext()) {
				errorCollector.addError(new AssertionError("Ran out of console results while there are still hg4j results"));
				break;
			}
			Record cr = consoleResultItr.next();
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
		report("log -f e", cmd.file("e", true).execute(), false /*#1, below*/);
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
		report("log -f dir/b", cmd.file("dir/b", true).execute(), false /*#1, below*/);
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
	
	private final class TreeCollectHandler implements HgChangesetTreeHandler {
		private final LinkedList<HgChangeset> cmdResult = new LinkedList<HgChangeset>();
		private final boolean reverseResult;
		boolean checkPrevInChildren = false;
		boolean checkPrevInParents = false;
		
		public TreeCollectHandler(boolean _reverseResult) {
			this.reverseResult = _reverseResult;
		}

		public List<HgChangeset> getResult() {
			return cmdResult;
		}
		

		public void treeElement(TreeElement entry) throws HgCallbackTargetException {
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
				String msg = String.format("No parent-child bind betwee revisions %d and %d", prevChangeset.getRevisionIndex(), entry.changeset().getRevisionIndex());
				errorCollector.assertTrue(msg, entry.children().contains(prevChangeset));
			}
			if (checkPrevInParents && !cmdResult.isEmpty()) {
				HgChangeset prevChangeset = reverseResult ? cmdResult.getFirst() : cmdResult.getLast();
				String msg = String.format("No parent-child bind betwee revisions %d and %d", prevChangeset.getRevisionIndex(), entry.changeset().getRevisionIndex());
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
}
