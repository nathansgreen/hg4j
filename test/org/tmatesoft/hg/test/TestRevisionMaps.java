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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRevisionMap;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestRevisionMaps {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testParentChildMap() throws HgException {
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = RepoUtils.allRevisions(repo);
		HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		parentHelper.init();
		errorCollector.assertEquals(Arrays.asList(allRevs), parentHelper.all());
		for (Nodeid n : allRevs) {
			errorCollector.assertTrue(parentHelper.knownNode(n));
			// parents
			final Nodeid p1 = parentHelper.safeFirstParent(n);
			final Nodeid p2 = parentHelper.safeSecondParent(n);
			errorCollector.assertFalse(p1 == null);
			errorCollector.assertFalse(p2 == null);
			errorCollector.assertEquals(p1.isNull() ? null : p1, parentHelper.firstParent(n));
			errorCollector.assertEquals(p2.isNull() ? null : p2, parentHelper.secondParent(n));
			HashSet<Nodeid> parents = new HashSet<Nodeid>();
			boolean modified = parentHelper.appendParentsOf(n, parents);
			errorCollector.assertEquals(p1.isNull() && p2.isNull(), !modified);
			HashSet<Nodeid> cp = new HashSet<Nodeid>();
			cp.add(parentHelper.firstParent(n));
			cp.add(parentHelper.secondParent(n));
			cp.remove(null);
			errorCollector.assertEquals(cp, parents);
			modified = parentHelper.appendParentsOf(n, parents);
			errorCollector.assertFalse(modified);
			//
			// isChild, hasChildren, childrenOf, directChildren
			if (!p1.isNull()) {
				errorCollector.assertTrue(parentHelper.isChild(p1, n));
				errorCollector.assertTrue(parentHelper.hasChildren(p1));
				errorCollector.assertTrue(parentHelper.childrenOf(Collections.singleton(p1)).contains(n));
				errorCollector.assertTrue(parentHelper.directChildren(p1).contains(n));
			}
			if (!p2.isNull()) {
				errorCollector.assertTrue(parentHelper.isChild(p2, n));
				errorCollector.assertTrue(parentHelper.hasChildren(p2));
				errorCollector.assertTrue(parentHelper.childrenOf(Collections.singleton(p2)).contains(n));
				errorCollector.assertTrue(parentHelper.directChildren(p2).contains(n));
			}
			errorCollector.assertFalse(parentHelper.isChild(n, p1));
			errorCollector.assertFalse(parentHelper.isChild(n, p2));
			//
			
		}
		// heads
		errorCollector.assertEquals(Arrays.asList(allRevs[7], allRevs[9]), new ArrayList<Nodeid>(parentHelper.heads()));
		// isChild
		errorCollector.assertTrue(parentHelper.isChild(allRevs[1], allRevs[9]));
		errorCollector.assertTrue(parentHelper.isChild(allRevs[0], allRevs[7]));
		errorCollector.assertFalse(parentHelper.isChild(allRevs[4], allRevs[7]));
		errorCollector.assertFalse(parentHelper.isChild(allRevs[2], allRevs[6]));
		// childrenOf
		errorCollector.assertEquals(Arrays.asList(allRevs[7]), parentHelper.childrenOf(Collections.singleton(allRevs[5])));
		errorCollector.assertEquals(Arrays.asList(allRevs[8], allRevs[9]), parentHelper.childrenOf(Arrays.asList(allRevs[4], allRevs[6])));
		errorCollector.assertEquals(Arrays.asList(allRevs[6], allRevs[8], allRevs[9]), parentHelper.childrenOf(Collections.singleton(allRevs[3])));
		// directChildren
		errorCollector.assertEquals(Arrays.asList(allRevs[2], allRevs[3]), parentHelper.directChildren(allRevs[1]));
		errorCollector.assertEquals(Arrays.asList(allRevs[8]), parentHelper.directChildren(allRevs[6]));
		errorCollector.assertEquals(Collections.emptyList(), parentHelper.directChildren(allRevs[7]));
		// ancestors on the same line
		errorCollector.assertEquals(allRevs[4], parentHelper.ancestor(allRevs[4], allRevs[4]));
		errorCollector.assertEquals(allRevs[8], parentHelper.ancestor(allRevs[8], allRevs[9]));
		errorCollector.assertEquals(allRevs[1], parentHelper.ancestor(allRevs[9], allRevs[1]));
		errorCollector.assertEquals(allRevs[5], parentHelper.ancestor(allRevs[5], allRevs[7]));
		// ancestor
		errorCollector.assertEquals(allRevs[1], parentHelper.ancestor(allRevs[2], allRevs[3]));
		errorCollector.assertEquals(allRevs[1], parentHelper.ancestor(allRevs[4], allRevs[6]));
		errorCollector.assertEquals(allRevs[2], parentHelper.ancestor(allRevs[9], allRevs[7]));
		errorCollector.assertEquals(allRevs[2], parentHelper.ancestor(allRevs[4], allRevs[7]));
	}

	@Test
	public void testRevisionMap() throws HgException {
		// XXX this test may benefit from external huge repository
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = RepoUtils.allRevisions(repo);
		final HgChangelog clog = repo.getChangelog();
		final HgRevisionMap<HgChangelog> rmap = new HgRevisionMap<HgChangelog>(clog).init();
		doTestRevisionMap(allRevs, rmap);
	}

	@Test
	public void testRevisionMapFromParentChildMap() throws HgException {
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = RepoUtils.allRevisions(repo);
		HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		parentHelper.init();
		doTestRevisionMap(allRevs, parentHelper.getRevisionMap());
	}

	private void doTestRevisionMap(Nodeid[] allRevs, HgRevisionMap<HgChangelog> rmap) {
		for (int i = 0; i < allRevs.length; i++) {
			errorCollector.assertEquals(i, rmap.revisionIndex(allRevs[i]));
			errorCollector.assertEquals(allRevs[i], rmap.revision(i));
		}
	}
}
