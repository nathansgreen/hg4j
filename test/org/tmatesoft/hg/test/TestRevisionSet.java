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

import java.util.Arrays;

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
		RevisionSet a = rs(n1, n2, n3);
		RevisionSet b = rs(n3, n4);
		RevisionSet union_ab = rs(n1, n2, n3, n4);
		RevisionSet intersect_ab = rs(n3);
		RevisionSet subtract_ab = rs(n1, n2);
		RevisionSet subtract_ba = rs(n4);
		RevisionSet symDiff_ab = rs(n1, n2, n4);
		
		errorCollector.assertEquals(union_ab, a.union(b));
		errorCollector.assertEquals(union_ab, b.union(a));
		errorCollector.assertEquals(intersect_ab, a.intersect(b));
		errorCollector.assertEquals(intersect_ab, b.intersect(a));
		errorCollector.assertEquals(subtract_ab, a.subtract(b));
		errorCollector.assertEquals(subtract_ba, b.subtract(a));
		errorCollector.assertEquals(symDiff_ab, a.symmetricDifference(b));
		errorCollector.assertEquals(symDiff_ab, b.symmetricDifference(a));
		errorCollector.assertTrue(rs(n1, n2, n4).equals(rs(n4, n1, n2)));
		errorCollector.assertTrue(rs().equals(rs()));
		errorCollector.assertFalse(rs(n1).equals(rs(n2)));
	}
	
	@Test
	public void testRootsAndHeads() throws Exception {
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = allRevisions(repo);
		HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		parentHelper.init();
		final RevisionSet complete = rs(allRevs);
		// roots
		errorCollector.assertEquals(rs(allRevs[0]), complete.roots(parentHelper));
		RevisionSet fromR2 = complete.subtract(rs(allRevs[0], allRevs[1]));
		RevisionSet fromR3 = complete.subtract(rs(allRevs[0], allRevs[1], allRevs[2]));
		errorCollector.assertEquals(rs(allRevs[2], allRevs[3]), fromR2.roots(parentHelper));
		errorCollector.assertEquals(rs(allRevs[3], allRevs[4], allRevs[5]), fromR3.roots(parentHelper));
		// heads
		errorCollector.assertEquals(rs(allRevs[9], allRevs[7]), complete.heads(parentHelper));
		RevisionSet toR7 = complete.subtract(rs(allRevs[9], allRevs[8]));
		errorCollector.assertEquals(rs(allRevs[7], allRevs[6], allRevs[4]), toR7.heads(parentHelper));
		RevisionSet withoutNoMergeBranch = toR7.subtract(rs(allRevs[5], allRevs[7]));
		errorCollector.assertEquals(rs(allRevs[6], allRevs[4]), withoutNoMergeBranch.heads(parentHelper));
		errorCollector.assertEquals(complete.heads(parentHelper), complete.heads(parentHelper).heads(parentHelper));
	}
	
	@Test
	public void testAncestorsAndChildren() throws Exception {
		final HgRepository repo = Configuration.get().find("test-annotate");
		Nodeid[] allRevs = allRevisions(repo);
		HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		parentHelper.init();
		final RevisionSet complete = rs(allRevs);
		// children
		errorCollector.assertTrue(rs().children(parentHelper).isEmpty());
		errorCollector.assertEquals(rs(allRevs[8], allRevs[9]), rs(allRevs[4]).children(parentHelper));
		// default branch and no-merge branch both from r2 
		RevisionSet s1 = rs(allRevs[8], allRevs[9], allRevs[4], allRevs[5], allRevs[7]);
		errorCollector.assertEquals(s1, rs(allRevs[2]).children(parentHelper));
		// ancestors
		RevisionSet fromR2 = complete.subtract(rs(allRevs[0], allRevs[1]));
		// no-merge branch and r9 are not in ancestors of r8 (as well as r8 itself)
		RevisionSet s3 = fromR2.subtract(rs(allRevs[9], allRevs[5], allRevs[7], allRevs[8]));
		errorCollector.assertEquals(s3, fromR2.ancestors(rs(allRevs[8]), parentHelper));
		// ancestors of no-merge branch
		RevisionSet branchNoMerge = rs(allRevs[5], allRevs[7]);
		errorCollector.assertEquals(rs(allRevs[0], allRevs[1], allRevs[2]), complete.ancestors(branchNoMerge, parentHelper));
		errorCollector.assertEquals(rs(allRevs[2]), fromR2.ancestors(branchNoMerge, parentHelper));
	}
	
	private static Nodeid[] allRevisions(HgRepository repo) {
		Nodeid[] allRevs = new Nodeid[repo.getChangelog().getRevisionCount()];
		for (int i = 0; i < allRevs.length; i++) {
			allRevs[i] = repo.getChangelog().getRevision(i);
		}
		return allRevs;
	}

	
	private static RevisionSet rs(Nodeid... nodes) {
		return new RevisionSet(Arrays.asList(nodes));
	}
}
