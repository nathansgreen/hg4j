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

import static org.junit.Assert.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * {hg4j.tests.repos}/test-phases/
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestPhases {
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testHelperNoParentChildMap() throws Exception {
		HgRepository repo = Configuration.get().find("test-phases");
		HgPhase[] expected = readPhases(repo);
		final long start = System.nanoTime();
		PhasesHelper ph = new PhasesHelper(HgInternals.getImplementationRepo(repo), null);
		initAndCheck(ph, expected);
		final long end = System.nanoTime();
		// μ == \u03bc
		System.out.printf("Without ParentWalker (simulates log command for single file): %,d μs\n", (end - start)/1000);
	}
	
	@Test
	public void testHelperWithParentChildMap() throws Exception {
		HgRepository repo = Configuration.get().find("test-phases");
		HgPhase[] expected = readPhases(repo);
		final long start1 = System.nanoTime();
		HgParentChildMap<HgChangelog> pw = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		pw.init();
		final long start2 = System.nanoTime();
		PhasesHelper ph = new PhasesHelper(HgInternals.getImplementationRepo(repo), pw);
		initAndCheck(ph, expected);
		final long end = System.nanoTime();
		System.out.printf("With ParentWalker(simulates log command for whole repo): %,d μs (pw init: %,d ns)\n", (end - start1)/1000, start2 - start1);
	}

	private HgPhase[] initAndCheck(PhasesHelper ph, HgPhase[] expected) {
		HgChangelog clog = ph.getRepo().getChangelog();
		HgPhase[] result = new HgPhase[clog.getRevisionCount()];
		for (int i = 0, l = clog.getLastRevision(); i <= l; i++) {
			result[i] = ph.getPhase(i, null);
		}
		assertEquals(expected.length, result.length);
		for (int i = 0; i < result.length; i++) {
			errorCollector.assertTrue(result[i] == expected[i]);
		}
		return result;
	}
	
	private static HgPhase[] readPhases(HgRepository repo) throws Exception {
		HgPhase[] result = new HgPhase[repo.getChangelog().getRevisionCount()];
		OutputParser.Stub output = new OutputParser.Stub();
		ExecHelper eh = new ExecHelper(output, repo.getWorkingDir());
		eh.run("hg", "phase", "-r", "0:-1");
		Matcher m = Pattern.compile("(\\d+): (\\w+)$", Pattern.MULTILINE).matcher(output.result());
		int i = 0;
		while (m.find()) {
			int x = Integer.parseInt(m.group(1));
			assert x == i;
			HgPhase v = HgPhase.parse(m.group(2));
			result[x] = v;
			i++;
		}
		return result;
	}

	public static void main(String[] args) throws Exception {
		HgRepository repo = new HgLookup().detect(System.getProperty("user.home") + "/hg/test-phases/");
		HgPhase[] v = readPhases(repo);
		printPhases(v);
	}

	private static void printPhases(HgPhase[] phase) {
		for (int i = 0; i < phase.length; i++) {
			System.out.printf("rev:%3d, phase:%s\n", i, phase[i]);
		}
	}

}
