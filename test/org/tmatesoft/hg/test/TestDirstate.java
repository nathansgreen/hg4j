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

import static java.lang.Character.*;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDirstate;
import org.tmatesoft.hg.repo.HgDirstate.EntryKind;
import org.tmatesoft.hg.repo.HgDirstate.Record;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestDirstate {
	private HgRepository repo;

	@Test
	public void testParents() throws Exception {
		repo = Configuration.get().find("log-branches");
		final Pair<Nodeid, Nodeid> wcParents = repo.getWorkingCopyParents();
		Assert.assertEquals("5f24ef64e9dfb1540db524f88cb5c3d265e1a3b5", wcParents.first().toString());
		Assert.assertTrue(wcParents.second().isNull());
		//
		// TODO same static and non-static
	}

	@Test
	public void testParentsEmptyRepo() throws Exception {
		// check contract return values for empty/nonexistent dirstate
		repo = new HgLookup().detect(TestIncoming.initEmptyTempRepo("testParentsEmptyRepo"));
		final Pair<Nodeid, Nodeid> wcParents = repo.getWorkingCopyParents();
		Assert.assertTrue(wcParents.first().isNull());
		Assert.assertTrue(wcParents.second().isNull());
	}

	@Test
	public void testBranchName() throws Exception {
		repo = Configuration.get().find("log-branches");
		Assert.assertEquals("test", repo.getWorkingCopyBranchName());
		repo = Configuration.get().own();
		OutputParser.Stub output = new OutputParser.Stub();
		ExecHelper eh = new ExecHelper(output, repo.getWorkingDir());
		eh.run("hg", "branch");
		String branchName = output.result().toString().trim();
		Assert.assertEquals(branchName, repo.getWorkingCopyBranchName());
	}

	@Test
	public void testMixedNameCaseHandling() throws Exception {
		// General idea: to check cases like
		// 1. dirstate: /a/b/c, FileIterator: /a/B/C
		// 2. dirstate: /a/B/C, FileIterator: /a/b/c
		// 2. dirstate: /a/B/C, FileIterator: /A/b/C
		repo = Configuration.get().find("mixed-case");
		// Windows, case-insensitive file system
		final HgInternals testAccess = new HgInternals(repo);
		HgDirstate dirstate = testAccess.createDirstate(false);
		final TreeSet<Path> entries = new TreeSet<Path>();
		dirstate.walk(new HgDirstate.Inspector() {
			
			public boolean next(EntryKind kind, Record entry) {
				entries.add(entry.name());
				return true;
			}
		});
		Path[] expected = new Path[] {
			Path.create("a/low/low"),
			Path.create("a/low/UP"),
			Path.create("a/UP/low"),
			Path.create("a/UP/UP"),
		};
		Path[] allLower = new Path[expected.length];
		Path[] allUpper = new Path[expected.length];
		Path[] mixedNonMatching = new Path[expected.length];
		for (int i = 0; i < expected.length; i++) {
			assertTrue("prereq", entries.contains(expected[i]));
			final String s = expected[i].toString();
			allLower[i] = Path.create(s.toLowerCase());
			allUpper[i] = Path.create(s.toUpperCase());
			char[] ss = s.toCharArray();
			for (int j = 0; j < ss.length; j++) {
				if (isLetter(ss[j])) {
					ss[j] = isLowerCase(ss[j]) ? toUpperCase(ss[j]) : toLowerCase(ss[j]);
				}
			}
			Path mixed = Path.create(new String(ss));
			mixedNonMatching[i] = mixed;
		}
		// prereq
		checkKnownInDirstate(testAccess, dirstate, expected, expected);
		// all upper
		checkKnownInDirstate(testAccess, dirstate, allUpper, expected);
		// all lower
		checkKnownInDirstate(testAccess, dirstate, allLower, expected);
		// mixed
		checkKnownInDirstate(testAccess, dirstate, mixedNonMatching, expected);
		//
		// check that in case-sensitive file system mangled names do not match 
		dirstate = testAccess.createDirstate(true);
		// ensure read
		dirstate.walk(new HgDirstate.Inspector() {
			public boolean next(EntryKind kind, Record entry) {
				return false;
			}
		});
		Path[] known = testAccess.checkKnown(dirstate, mixedNonMatching);
		for (int i = 0; i < known.length; i++) {
			if (known[i] != null) {
				fail(expected[i] + " in case-sensitive dirstate matched " + known[i]);
			}
		}
		
	}

	private static void checkKnownInDirstate(HgInternals testAccess, HgDirstate dirstate, Path[] toCheck, Path[] expected) {
		Path[] known = testAccess.checkKnown(dirstate, toCheck);
		for (int i = 0; i < expected.length; i++) {
			assertTrue(expected[i].equals(known[i]));
		}
	}
}
