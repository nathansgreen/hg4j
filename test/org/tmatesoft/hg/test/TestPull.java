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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgIncomingCommand;
import org.tmatesoft.hg.core.HgPullCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestPull {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	@Test
	public void testPullToEmpty() throws Exception {
		File srcRepoLoc = RepoUtils.cloneRepoToTempLocation("test-annotate", "test-pull2empty-src", false);
		File dstRepoLoc = RepoUtils.initEmptyTempRepo("test-pull2empty-dst");
		HgServer server = new HgServer().start(srcRepoLoc);
		try {
			final HgLookup hgLookup = new HgLookup();
			final HgRemoteRepository srcRemote = hgLookup.detect(server.getURL());
			HgRepository dstRepo = hgLookup.detect(dstRepoLoc);
			HgPullCommand cmd = new HgPullCommand(dstRepo).source(srcRemote);
			cmd.execute();
			final HgRepository srcRepo = hgLookup.detect(srcRepoLoc);
			checkRepositoriesAreSame(srcRepo, dstRepo);
			final List<Nodeid> incoming = new HgIncomingCommand(dstRepo).against(srcRemote).executeLite();
			errorCollector.assertTrue(incoming.toString(), incoming.isEmpty());
			RepoUtils.assertHgVerifyOk(errorCollector, dstRepoLoc);
		} finally {
			server.stop();
		}
	}
	
	// test when pull comes with new file (if AddRevInspector/RevlogStreamWriter is ok with file that doesn't exist 

	private void checkRepositoriesAreSame(HgRepository srcRepo, HgRepository dstRepo) {
		// XXX copy of TestPush#checkRepositoriesAreSame
		errorCollector.assertEquals(srcRepo.getChangelog().getRevisionCount(), dstRepo.getChangelog().getRevisionCount());
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(0), dstRepo.getChangelog().getRevision(0));
		errorCollector.assertEquals(srcRepo.getChangelog().getRevision(TIP), dstRepo.getChangelog().getRevision(TIP));
	}
}
