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
		RevisionSet a = f(n1, n2, n3);
		RevisionSet b = f(n3, n4);
		RevisionSet union_ab = f(n1, n2, n3, n4);
		RevisionSet intersect_ab = f(n3);
		RevisionSet subtract_ab = f(n1, n2);
		RevisionSet subtract_ba = f(n4);
		RevisionSet symDiff_ab = f(n1, n2, n4);
		
		errorCollector.assertEquals(union_ab, a.union(b));
		errorCollector.assertEquals(union_ab, b.union(a));
		errorCollector.assertEquals(intersect_ab, a.intersect(b));
		errorCollector.assertEquals(intersect_ab, b.intersect(a));
		errorCollector.assertEquals(subtract_ab, a.subtract(b));
		errorCollector.assertEquals(subtract_ba, b.subtract(a));
		errorCollector.assertEquals(symDiff_ab, a.symmetricDifference(b));
		errorCollector.assertEquals(symDiff_ab, b.symmetricDifference(a));
	}

	
	private static RevisionSet f(Nodeid... nodes) {
		return new RevisionSet(Arrays.asList(nodes));
	}
}
