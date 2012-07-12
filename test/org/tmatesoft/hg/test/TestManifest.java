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
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.*;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgManifestHandler;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgManifestCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.util.Path;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestManifest {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private final HgRepository repo;
	private ManifestOutputParser manifestParser;
	private ExecHelper eh;
	final LinkedList<HgFileRevision> revisions = new LinkedList<HgFileRevision>();
	private HgManifestHandler handler  = new HgManifestHandler() {
		
		public void file(HgFileRevision fileRevision) {
			revisions.add(fileRevision);
		}
		
		public void end(Nodeid manifestRevision) {}
		public void dir(Path p) {}
		public void begin(Nodeid manifestRevision) {}
	};

	public static void main(String[] args) throws Throwable {
		TestManifest tm = new TestManifest();
		tm.testTip();
		tm.testFirstRevision();
		tm.testRevisionInTheMiddle();
		tm.testWalkFileRevisions();
		tm.errorCollector.verify();
	}
	
	public TestManifest() throws Exception {
		this(new HgLookup().detectFromWorkingDir());
	}

	private TestManifest(HgRepository hgRepo) {
		repo = hgRepo;
		assertTrue(!repo.isInvalid());
		eh = new ExecHelper(manifestParser = new ManifestOutputParser(), repo.getWorkingDir());
	}

	@Test
	public void testTip() throws Exception {
		testRevision(TIP);
	}

	@Test
	public void testFirstRevision() throws Exception {
		testRevision(0);
	}
	
	@Test
	public void testRevisionInTheMiddle() throws Exception {
		int rev = repo.getManifest().getRevisionCount() / 2;
		if (rev == 0) {
			throw new IllegalStateException("Need manifest with few revisions");
		}
		testRevision(rev);
	}
	
	@Test
	public void testWalkFileRevisions() throws Exception {
		//  hg --debug manifest --rev 150 | grep cmdline/org/tmatesoft/hg/console/Main.java
		String fname = "cmdline/org/tmatesoft/hg/console/Main.java";
		Pattern ptrn = Pattern.compile("(\\w+)\\s+\\d{3}\\s+\\Q" + fname + "\\E$", Pattern.MULTILINE);
		OutputParser.Stub output = new OutputParser.Stub();
		ExecHelper eh = new ExecHelper(output, repo.getWorkingDir());
		int[] revisions = new int[] { 100, 150, 200, 210, 300 };
		final IntMap<Nodeid> expected = new IntMap<Nodeid>(10);
		for (int r : revisions) {
			eh.run("hg", "--debug", "manifest", "--rev", Integer.toString(r));
			for (String l : output.lines(ptrn, 1)) {
				assertFalse(expected.containsKey(r));
				expected.put(r, Nodeid.fromAscii(l));
			}
			if (!expected.containsKey(r)) {
				expected.put(r, Nodeid.NULL);
			}
		}
		assertEquals(revisions.length, expected.size());
		final Path path = Path.create(fname);
		repo.getManifest().walkFileRevisions(path, new HgManifest.Inspector() {

			private Nodeid expectedNodeid;
			private int clogRev;

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				clogRev = changelogRevision;
				assertTrue(expected.containsKey(changelogRevision));
				expectedNodeid = expected.get(changelogRevision);
				// we walk revisions of specific file, hence can't get into manifest without
				// that file, expectedNodeid should have been reported by `hg manifest`:
				assertTrue(!expectedNodeid.isNull());
				return true;
			}

			public boolean next(Nodeid nid, Path fname, Flags flags) {
				assertEquals(path, fname); // only selected path shall pass
				assertEquals(expectedNodeid, nid);
				expected.remove(clogRev);
				return true;
			}
			
			public boolean end(int manifestRevision) {
				return true;
			}
			
		}, revisions);
		for (int r : revisions) {
			if (expected.containsKey(r)) {
				// Each  manifest entry reported by `hg manifest` shall be 
				// reported (and removed) by walkFileRevisions(), too. Only null nodeids
				// (revisions where the file was missing) are left 
				assertTrue(expected.get(r).isNull());
			}
		}
	}


	private void testRevision(int rev) throws Exception {
		manifestParser.reset();
		eh.run("hg", "manifest", "--debug", "--rev", String.valueOf(rev == TIP ? -1 : rev));
		revisions.clear();
		new HgManifestCommand(repo).changeset(rev).execute(handler);
		report("manifest " + (rev == TIP ? "TIP:" : "--rev " + rev));
	}

	private void report(String what) throws Exception {
		final Map<Path, Nodeid> cmdLineResult = new LinkedHashMap<Path, Nodeid>(manifestParser.getResult());
		for (HgFileRevision fr : revisions) {
			Nodeid nid = cmdLineResult.remove(fr.getPath());
			errorCollector.checkThat("Extra " + fr.getPath() + " in Java result", nid, notNullValue());
			if (nid != null) {
				errorCollector.checkThat("Non-matching nodeid:" + nid, nid, equalTo(fr.getRevision()));
			}
		}
		errorCollector.checkThat("Non-matched entries from command line:", cmdLineResult, equalTo(Collections.<Path,Nodeid>emptyMap()));
	}
}
