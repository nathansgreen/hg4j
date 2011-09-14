/*
 * Copyright (c) 2011 TMate Software Ltd
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

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Pair;

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
		Assert.assertEquals("default", repo.getWorkingCopyBranchName());
	}

	public void testMixedNameCaseHandling() {
		// 1. dirstate: /a/b/c, FileIterator: /a/B/C
		// 2. dirstate: /a/B/C, FileIterator: /a/b/c
		// 2. dirstate: /a/B/C, FileIterator: /A/b/C
	}
}
