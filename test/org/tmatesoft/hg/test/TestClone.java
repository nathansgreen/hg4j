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

import static org.tmatesoft.hg.internal.RequiresFile.*;

import java.io.File;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCloneCommand;
import org.tmatesoft.hg.core.HgInitCommand;
import org.tmatesoft.hg.internal.RepoInitializer;
import org.tmatesoft.hg.repo.HgRemoteRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestClone {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	public static void main(String[] args) throws Throwable {
		TestClone t = new TestClone();
		t.testSimpleClone();
		t.errorCollector.verify();
	}

	public TestClone() {
	}
	
	@Test
	public void testSimpleClone() throws Exception {
		int x = 0;
		final File tempDir = Configuration.get().getTempDir();
		for (HgRemoteRepository hgRemote : Configuration.get().allRemote()) {
			HgCloneCommand cmd = new HgCloneCommand();
			cmd.source(hgRemote);
			File dest = new File(tempDir, "test-clone-" + x++);
			if (dest.exists()) {
				RepoUtils.rmdir(dest);
			}
			cmd.destination(dest);
			cmd.execute();
			verify(hgRemote, dest);
		}
	}
	
	@Test
	public void testInitEmpty() throws Exception {
		File repoLoc = RepoUtils.createEmptyDir("test-init");
		new HgInitCommand().location(repoLoc).revlogV1().dotencode(false).fncache(false).execute();
		
		int requires = new RepoInitializer().initRequiresFromFile(new File(repoLoc, ".hg")).getRequires();
		errorCollector.assertTrue(0 != (requires & REVLOGV1));
		errorCollector.assertTrue(0 != (requires & STORE));
		errorCollector.assertTrue(0 == (requires & DOTENCODE));
		errorCollector.assertTrue(0 == (requires & FNCACHE));
		errorCollector.assertTrue(0 == (requires & REVLOGV0));
	}

	private void verify(HgRemoteRepository hgRemote, File dest) throws Exception {
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(), dest);
		eh.run("hg", "verify");
		errorCollector.checkThat("Verify", eh.getExitValue(), CoreMatchers.equalTo(0));
		eh.run("hg", "out", hgRemote.getLocation());
		errorCollector.checkThat("Outgoing", eh.getExitValue(), CoreMatchers.equalTo(1));
		eh.run("hg", "in", hgRemote.getLocation());
		errorCollector.checkThat("Incoming", eh.getExitValue(), CoreMatchers.equalTo(1));
	}
}
