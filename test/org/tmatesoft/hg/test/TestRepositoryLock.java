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

import java.io.File;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryLock;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestRepositoryLock {

	@Test
	public void testWorkingDirLock() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-wc-lock", false);
		// turn off lock timeout, to fail fast
		File hgrc = new File(repoLoc, ".hg/hgrc");
		RepoUtils.createFile(hgrc, "[ui]\ntimeout=0\n"); // or 1
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(true), repoLoc);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		final HgRepositoryLock wdLock = hgRepo.getWorkingDirLock();
		try {
			wdLock.acquire();
			eh.run("hg", "tag", "tag-aaa");
			Assert.assertNotSame(0 /*returns 0 on success*/, eh.getExitValue());
		} finally {
			wdLock.release();
		}
	}
}
