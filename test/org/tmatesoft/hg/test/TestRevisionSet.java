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

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestRevisionSet {
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	@Test
	public void testRegularSetOperations() {
		Nodeid n1 = Nodeid.fromAscii("c75297c1786734589175c673db40e8ecaa032b09");
		Nodeid n2 = Nodeid.fromAscii("3b7d51ed4c65082f9235e3459e282d7ff723aa97");
		Nodeid n3 = Nodeid.fromAscii("14dac192aa262feb8ff6645a102648498483a188");
		Nodeid n4 = Nodeid.fromAscii("1deea2f332183c947937f6df988c2c6417efc217");
		Nodeid[] nodes = { n1, n2, n3 };
		RevisionSet a = new RevisionSet(nodes);
		Nodeid[] nodes1 = { n3, n4 };
		RevisionSet b = new RevisionSet(nodes1);
		Nodeid[] nodes2 = { n1, n2, n3, n4 };
		RevisionSet union_ab = new RevisionSet(nodes2);
		Nodeid[] nodes3 = { n3 };
		RevisionSet intersect_ab = new RevisionSet(nodes3);
		Nodeid[] nodes4 = { n1, n2 };
		RevisionSet subtract_ab = new RevisionSet(nodes4);
		Nodeid[] nodes5 = { n4 };
		RevisionSet subtract_ba = new RevisionSet(nodes5);
		Nodeid[] nodes6 = { n1, n2, n4 };
		RevisionSet symDiff_ab = new RevisionSet(nodes6);
		
		errorCollector.assertEquals(union_ab, a.union(b));
		errorCollector.assertEquals(union_ab, b.union(a));
		errorCollector.assertEquals(intersect_ab, a.intersect(b));
		errorCollector.assertEquals(intersect_ab, b.intersect(a));
		errorCollector.assertEquals(subtract_ab, a.subtract(b));
		errorCollector.assertEquals(subtract_ba, b.subtract(a));
		errorCollector.assertEquals(symDiff_ab, a.symmetricDifference(b));
		errorCollector.assertEquals(symDiff_ab, b.symmetricDifference(a));
		Nodeid[] nodes7 = { n1, n2, n4 };
		Nodeid[] nodes8 = { n4, n1, n2 };
		errorCollector.assertTrue(new RevisionSet(nodes7).equals(new RevisionSet(nodes8)));
		Nodeid[] nodes9 = {};
		Nodeid[] nodes10 = {};
		errorCollector.assertTrue(new RevisionSet(nodes9).equals(new RevisionSet(nodes10)));
		Nodeid[] nodes11 = { n1 };
		Nodeid[] nodes12 = { n2 };
		errorCollector.assertFalse(new RevisionSet(nodes11).equals(new RevisionSet(nodes12)));
	}
	
	@Test
	public void testRootsAndHeads() throws Exception {
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = RepoUtils.allRevisions(repo);
		HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		parentHelper.init();
		final RevisionSet complete = new RevisionSet(allRevs);
		Nodeid[] nodes = { allRevs[0] };
		// roots
		errorCollector.assertEquals(new RevisionSet(nodes), complete.roots(parentHelper));
		Nodeid[] nodes1 = { allRevs[0], allRevs[1] };
		RevisionSet fromR2 = complete.subtract(new RevisionSet(nodes1));
		Nodeid[] nodes2 = { allRevs[0], allRevs[1], allRevs[2] };
		RevisionSet fromR3 = complete.subtract(new RevisionSet(nodes2));
		Nodeid[] nodes3 = { allRevs[2], allRevs[3] };
		errorCollector.assertEquals(new RevisionSet(nodes3), fromR2.roots(parentHelper));
		Nodeid[] nodes4 = { allRevs[3], allRevs[4], allRevs[5] };
		errorCollector.assertEquals(new RevisionSet(nodes4), fromR3.roots(parentHelper));
		Nodeid[] nodes5 = { allRevs[9], allRevs[7] };
		// heads
		errorCollector.assertEquals(new RevisionSet(nodes5), complete.heads(parentHelper));
		Nodeid[] nodes6 = { allRevs[9], allRevs[8] };
		RevisionSet toR7 = complete.subtract(new RevisionSet(nodes6));
		Nodeid[] nodes7 = { allRevs[7], allRevs[6], allRevs[4] };
		errorCollector.assertEquals(new RevisionSet(nodes7), toR7.heads(parentHelper));
		Nodeid[] nodes8 = { allRevs[5], allRevs[7] };
		RevisionSet withoutNoMergeBranch = toR7.subtract(new RevisionSet(nodes8));
		Nodeid[] nodes9 = { allRevs[6], allRevs[4] };
		errorCollector.assertEquals(new RevisionSet(nodes9), withoutNoMergeBranch.heads(parentHelper));
		errorCollector.assertEquals(complete.heads(parentHelper), complete.heads(parentHelper).heads(parentHelper));
	}
	
	@Test
	public void testAncestorsAndChildren() throws Exception {
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = RepoUtils.allRevisions(repo);
		HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		parentHelper.init();
		final RevisionSet complete = new RevisionSet(allRevs);
		Nodeid[] nodes = {};
		// children
		errorCollector.assertTrue(new RevisionSet(nodes).children(parentHelper).isEmpty());
		Nodeid[] nodes1 = { allRevs[8], allRevs[9] };
		Nodeid[] nodes2 = { allRevs[4] };
		errorCollector.assertEquals(new RevisionSet(nodes1), new RevisionSet(nodes2).children(parentHelper));
		Nodeid[] nodes3 = { allRevs[8], allRevs[9], allRevs[4], allRevs[5], allRevs[7] };
		// default branch and no-merge branch both from r2 
		RevisionSet s1 = new RevisionSet(nodes3);
		Nodeid[] nodes4 = { allRevs[2] };
		errorCollector.assertEquals(s1, new RevisionSet(nodes4).children(parentHelper));
		Nodeid[] nodes5 = { allRevs[0], allRevs[1] };
		// ancestors
		RevisionSet fromR2 = complete.subtract(new RevisionSet(nodes5));
		Nodeid[] nodes6 = { allRevs[9], allRevs[5], allRevs[7], allRevs[8] };
		// no-merge branch and r9 are not in ancestors of r8 (as well as r8 itself)
		RevisionSet s3 = fromR2.subtract(new RevisionSet(nodes6));
		Nodeid[] nodes7 = { allRevs[8] };
		errorCollector.assertEquals(s3, fromR2.ancestors(new RevisionSet(nodes7), parentHelper));
		Nodeid[] nodes8 = { allRevs[5], allRevs[7] };
		// ancestors of no-merge branch
		RevisionSet branchNoMerge = new RevisionSet(nodes8);
		Nodeid[] nodes9 = { allRevs[0], allRevs[1], allRevs[2] };
		errorCollector.assertEquals(new RevisionSet(nodes9), complete.ancestors(branchNoMerge, parentHelper));
		Nodeid[] nodes10 = { allRevs[2] };
		errorCollector.assertEquals(new RevisionSet(nodes10), fromR2.ancestors(branchNoMerge, parentHelper));
	}
}
