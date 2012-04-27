/*
 * Copyright (c) 2012 TMate Software Ltd
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
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.util.List;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgStatusCommand;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgSubrepoLocation;
import org.tmatesoft.hg.repo.HgSubrepoLocation.Kind;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestSubrepo {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	private StatusOutputParser statusParser;
	private ExecHelper eh;
	
	/*
	 * Layout of status-subrepo:
	 * first/					regular subrepo
	 * dir/second/				subrepo nested under a tracked folder
	 * third/					subrepo with another one
	 * third/fourth				2nd level of subrepo nesting (registered in third/.hgsub)
	 * third/fourth/file4_1		A, added file
	 * third/fourth/file4_2		?, untracked file
	 * fifth/					nested repository not yet registered in .hgsub
	 * fifth/file5				untracked file
	 * 
	 * Curiously, fifth/ shall not be reported (neither 'hg status -AS' nor '-A' don't report
	 * anything for it, no '?' for the file5 in particular. Once fifth/.hg/ is removed,
	 * file5 gets its ? as one would expect)
	 */

	@Test
	public void testAccessAPI() throws Exception {
		repo = Configuration.get().find("status-subrepo");
		List<HgSubrepoLocation> subrepositories = repo.getSubrepositories();
		assertEquals(3, subrepositories.size());
		checkHgSubrepo(Path.create("first/"), true, repo, subrepositories.get(0));
		checkHgSubrepo(Path.create("dir/second/"), true, repo, subrepositories.get(1));
		checkHgSubrepo(Path.create("third/"), false, repo, subrepositories.get(2));
	}
	
	private void checkHgSubrepo(Path expectedLocation, boolean isCommitted, HgRepository topRepo, HgSubrepoLocation l) throws Exception {
		errorCollector.assertEquals(expectedLocation, l.getLocation());
		errorCollector.assertEquals(Kind.Hg, l.getType());
		if (isCommitted) {
			errorCollector.assertTrue(l.isCommitted());
			errorCollector.assertTrue(l.getRevision() != null);
			errorCollector.assertTrue(!l.getRevision().isNull());
		} else {
			errorCollector.assertTrue(!l.isCommitted());
			errorCollector.assertTrue(l.getRevision() == null);
		}
		errorCollector.assertEquals(topRepo, l.getOwner());
		HgRepository r = l.getRepo();
		String expectedSubRepoLoc = new File(topRepo.getLocation(), expectedLocation.toString()).toString();
		errorCollector.assertEquals(expectedSubRepoLoc, r.getLocation());
		errorCollector.assertTrue(r.getChangelog().getRevisionCount() > 0);
		if (isCommitted) {
			errorCollector.assertEquals(r.getChangelog().getRevision(TIP), l.getRevision());
		}
	}

	@Test
	@Ignore("StatusCommand doesn't suport subrepositories yet")
	public void testStatusCommand() throws Exception {
		repo = Configuration.get().find("status-subrepo");
		statusParser = new StatusOutputParser();
		eh = new ExecHelper(statusParser, repo.getWorkingDir());
		TestStatus.StatusReporter sr = new TestStatus.StatusReporter(errorCollector, statusParser);
		HgStatusCommand cmd = new HgStatusCommand(repo).all();
		TestStatus.StatusCollector sc;

		eh.run("hg", "status", "-A", "-S");
		cmd.subrepo(true);
		cmd.execute(sc = new TestStatus.StatusCollector());
		sr.report("status -A -S", sc);
		
		eh.run("hg", "status", "-A", "-S");
		cmd.subrepo(false);
		cmd.execute(sc = new TestStatus.StatusCollector());
		sr.report("status -A", sc);
		
	}
	
}
