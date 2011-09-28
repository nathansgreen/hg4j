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

import static org.junit.Assert.*;

import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgBranches;
import org.tmatesoft.hg.repo.HgBranches.BranchInfo;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * <pre>
 * branches-1/create.bat:
 * branch1 to have fork, two heads, both closed -- shall be recognized as closed
 * branch2 to have fork, two heads, one closed  -- shall be recognized as active
 * branch3 no active head						-- shall be recognized as inactive
 * branch4 to fork, with 1 inactive and 1 active heads
 * branch5 to be closed and reopened
 * </pre>
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestBranches {

	@Test
	public void testClosedInactiveBranches() throws Exception {
		HgRepository repo = Configuration.get().find("branches-1");
		HgBranches branches = repo.getBranches();
		BranchInfo b1 = branches.getBranch("branch1");
		assertNotNull(b1);
		assertTrue(b1.isClosed());
		assertEquals(2, b1.getHeads().size());
		// order is important!
		assertEquals("131e84b878d25b5eab7f529ebb35e57b2a439db7", b1.getHeads().get(0).toString());
		assertEquals("c993cda1f5a7afd771efa87fe95fb7c5f73169e6", b1.getHeads().get(1).toString());
		//
		BranchInfo b2 = branches.getBranch("branch2");
		assertNotNull(b2);
		assertFalse(b2.isClosed());
		assertEquals(2, b2.getHeads().size());
		assertEquals("537f548adfd7eb9ce2a73ed7e7ca163eb1b61401", b2.getHeads().get(0).toString());
		assertEquals("e698babd9479b1c07e0ed3155f5e290ee15affed", b2.getHeads().get(1).toString());
		//
		BranchInfo b3 = branches.getBranch("branch3");
		assertNotNull(b3);
		assertFalse(b3.isClosed());
		assertEquals(1, b3.getHeads().size());
		assertEquals("b103f33723f37c7bb4b81d74a66135d6fdaf0ced", b3.getHeads().get(0).toString());
		//
		BranchInfo b4 = branches.getBranch("branch4");
		assertNotNull(b4);
		assertFalse(b4.isClosed());
		assertEquals(2, b4.getHeads().size());
		assertEquals("fceabd402f0193fb30605aed0ee3a9d5feb99f60", b4.getHeads().get(0).toString());
		assertEquals("892b6a504be7835f1748ba632fe15a9389d4479b", b4.getHeads().get(1).toString());
		//
		BranchInfo b5 = branches.getBranch("branch5");
		assertNotNull(b5);
		assertFalse(b5.isClosed());
		assertEquals(1, b5.getHeads().size());
		assertEquals("9cb6ad32b9074021356c38050e2aab6addba4393", b5.getHeads().get(0).toString());
	}

	@Test
	public void testBranchInfoClosedHeads() throws Exception{
		HgRepository repo = Configuration.get().find("branches-1");
		HgBranches branches = repo.getBranches();
		// branch1 - two closed heads
		BranchInfo b1 = branches.getBranch("branch1");
		assertTrue(b1.isClosed(b1.getHeads().get(0)));
		assertTrue(b1.isClosed(b1.getHeads().get(1)));
		try {
			b1.isClosed(Nodeid.fromAscii("9cb6ad32b9074021356c38050e2aab6addba4393"));
			fail("Revision that doesn't belong to heads of the branch shall not be handled");
		} catch (IllegalArgumentException ex) {
			// good
		}
		//
		// branch2, one closed head
		BranchInfo b2 = branches.getBranch("branch2");
		assertFalse(b2.isClosed(b2.getHeads().get(0)));
		assertTrue(b2.isClosed(b2.getHeads().get(1)));
		//
		// branch5, closed and reopened, 1 open head
		BranchInfo b5 = branches.getBranch("branch5");
		for (Nodeid h : b5.getHeads()) {
			assertFalse(b5.isClosed(h));
		}
	}
}
