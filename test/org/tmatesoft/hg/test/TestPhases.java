/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

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
	
	@Test
	public void testAllSecretAndDraft() throws Exception {
		HgRepository repo = Configuration.get().find("test-phases");
		Internals implRepo = HgInternals.getImplementationRepo(repo);
		HgPhase[] expected = readPhases(repo);
		ArrayList<Nodeid> secret = new ArrayList<Nodeid>();
		ArrayList<Nodeid> draft = new ArrayList<Nodeid>();
		ArrayList<Nodeid> pub = new ArrayList<Nodeid>();
		for (int i = 0; i < expected.length; i++) {
			Nodeid n = repo.getChangelog().getRevision(i);
			switch (expected[i]) {
			case Secret : secret.add(n); break; 
			case Draft : draft.add(n); break;
			case Public : pub.add(n); break;
			default : throw new IllegalStateException();
			}
		}
		final RevisionSet rsSecret = new RevisionSet(secret);
		final RevisionSet rsDraft = new RevisionSet(draft);
		assertFalse("[sanity]", rsSecret.isEmpty());
		assertFalse("[sanity]", rsDraft.isEmpty());
		HgParentChildMap<HgChangelog> pw = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		pw.init();
		PhasesHelper ph1 = new PhasesHelper(implRepo, null);
		PhasesHelper ph2 = new PhasesHelper(implRepo, pw);
		RevisionSet s1 = ph1.allSecret().symmetricDifference(rsSecret);
		RevisionSet s2 = ph2.allSecret().symmetricDifference(rsSecret);
		errorCollector.assertTrue("Secret,no ParentChildMap:" + s1.toString(), s1.isEmpty());
		errorCollector.assertTrue("Secret, with ParentChildMap:" + s2.toString(), s2.isEmpty());
		RevisionSet s3 = ph1.allDraft().symmetricDifference(rsDraft);
		RevisionSet s4 = ph2.allDraft().symmetricDifference(rsDraft);
		errorCollector.assertTrue("Draft,no ParentChildMap:" + s3.toString(), s3.isEmpty());
		errorCollector.assertTrue("Draft, with ParentChildMap:" + s4.toString(), s4.isEmpty());
	}

	private HgPhase[] initAndCheck(PhasesHelper ph, HgPhase[] expected) throws HgRuntimeException {
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
		assertEquals("Perhaps, older Mercurial version, with no hg phase command support?", 0, eh.getExitValue());
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
