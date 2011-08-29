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

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.tmatesoft.hg.internal.IntMap;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestIntMap {

	public static void main(String[] args) {
		TestIntMap t = new TestIntMap();
		t.testBasic();
	}
	
	@Test
	public void testBasic() {
		IntMap<String> m = new IntMap<String>(2);
		m.put(18, "18");
		m.put(1, "1");
		m.put(9, "9");
		m.put(20, "20");
		m.put(2, "2");
		m.put(3, "3");
		m.put(21, "21");
		m.put(15, "15");
		m.put(12, "12");
		m.put(11, "11");
		m.put(31, "31");
		assertEquals(11, m.size());
		assertEquals(1, m.firstKey());
		assertEquals(31, m.lastKey());
		int actualCount = 0;
		for (int i = m.firstKey(); i <= m.lastKey(); i++) {
			if (m.containsKey(i)) {
				actualCount++;
				assertEquals(m.get(i), Integer.toString(i));
			}
		}
		assertEquals(m.size(), actualCount);
	}
}
