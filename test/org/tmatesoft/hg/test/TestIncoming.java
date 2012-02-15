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
import static org.tmatesoft.hg.internal.RequiresFile.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgIncomingCommand;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestIncoming {
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	public static void main(String[] args) throws Throwable {
		Configuration.get().remoteServers("http://localhost:8000/");
		TestIncoming t = new TestIncoming();
		t.testSimple();
		t.errorCollector.verify();
	}

	public TestIncoming() {
//		Configuration.get().remoteServers("http://localhost:8000/");
	}

	@Test
	public void testSimple() throws Exception {
		int x = 0;
		HgLookup lookup = new HgLookup();
		for (HgRemoteRepository hgRemote : Configuration.get().allRemote()) {
			File dest = initEmptyTempRepo("test-incoming-" + x++);
			HgRepository localRepo = lookup.detect(dest);
			// Idea:
			// hg in, hg4j in, compare
			// hg pull total/2
			// hg in, hg4j in, compare
			List<Nodeid> incoming = runAndCompareIncoming(localRepo, hgRemote);
			Assert.assertTrue("Need remote repository of reasonable size to test incoming command for partially filled case", incoming.size() >= 5);
			//
			Nodeid median = incoming.get(incoming.size() / 2); 
			System.out.println("About to pull up to revision " + median.shortNotation());
			new ExecHelper(new OutputParser.Stub(), dest).run("hg", "pull", "-r", median.toString(), hgRemote.getLocation());
			//
			// shall re-read repository to pull up new changes 
			localRepo = lookup.detect(dest);
			runAndCompareIncoming(localRepo, hgRemote);
		}
	}
	
	private List<Nodeid> runAndCompareIncoming(HgRepository localRepo, HgRemoteRepository hgRemote) throws Exception {
		// need new command instance as subsequence exec[Lite|Full] on the same command would yield same result,
		// regardless of the pull in between.
		HgIncomingCommand cmd = new HgIncomingCommand(localRepo);
		cmd.against(hgRemote);
		HgLogCommand.CollectHandler collector = new HgLogCommand.CollectHandler();
		LogOutputParser outParser = new LogOutputParser(true);
		ExecHelper eh = new ExecHelper(outParser, localRepo.getWorkingDir());
		cmd.executeFull(collector);
		eh.run("hg", "incoming", "--debug", hgRemote.getLocation());
		List<Nodeid> liteResult = cmd.executeLite();
		report(collector, outParser, liteResult, errorCollector);
		return liteResult;
	}
	
	static void report(HgLogCommand.CollectHandler collector, LogOutputParser outParser, List<Nodeid> liteResult, ErrorCollectorExt errorCollector) {
		TestHistory.report("hg vs execFull", collector.getChanges(), outParser.getResult(), false, errorCollector);
		//
		ArrayList<Nodeid> expected = new ArrayList<Nodeid>(outParser.getResult().size());
		for (LogOutputParser.Record r : outParser.getResult()) {
			Nodeid nid = Nodeid.fromAscii(r.changesetNodeid);
			expected.add(nid);
		}
		checkNodeids("hg vs execLite:", liteResult, expected, errorCollector);
		//
		expected = new ArrayList<Nodeid>(outParser.getResult().size());
		for (HgChangeset cs : collector.getChanges()) {
			expected.add(cs.getNodeid());
		}
		checkNodeids("execFull vs execLite:", liteResult, expected, errorCollector);
	}
	
	static void checkNodeids(String what, List<Nodeid> liteResult, List<Nodeid> expected, ErrorCollectorExt errorCollector) {
		HashSet<Nodeid> set = new HashSet<Nodeid>(liteResult);
		for (Nodeid nid : expected) {
			boolean removed = set.remove(nid);
			errorCollector.checkThat(what + " Missing " +  nid.shortNotation() + " in HgIncomingCommand.execLite result", removed, equalTo(true));
		}
		errorCollector.checkThat(what + " Superfluous cset reported by HgIncomingCommand.execLite", set.isEmpty(), equalTo(true));
	}
	
	static File createEmptyDir(String dirName) throws IOException {
		File dest = new File(Configuration.get().getTempDir(), dirName);
		if (dest.exists()) {
			TestClone.rmdir(dest);
		}
		dest.mkdirs();
		return dest;
	}

	static File initEmptyTempRepo(String dirName) throws IOException {
		File dest = createEmptyDir(dirName);
		Internals implHelper = new Internals(new BasicSessionContext(null, null, null));
		implHelper.setStorageConfig(1, STORE | FNCACHE | DOTENCODE);
		implHelper.initEmptyRepository(new File(dest, ".hg"));
		return dest;
	}
}
