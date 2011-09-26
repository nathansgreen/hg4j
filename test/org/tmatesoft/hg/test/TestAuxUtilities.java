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
import org.tmatesoft.hg.internal.ArrayHelper;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestAuxUtilities {

	@Test
	public void testArrayHelper() {
		String[] initial = {"d", "w", "k", "b", "c", "i", "a", "r", "e", "h" };
		ArrayHelper ah = new ArrayHelper();
		String[] result = initial.clone();
		ah.sort(result);
		String[] restored = restore(result, ah.getReverse());
		Assert.assertArrayEquals(initial, restored);
		//
		// few elements are on the right place from the very start and do not shift during sort.
		// make sure for them we've got correct reversed indexes as well
		initial = new String[] {"d", "h", "c", "b", "k", "i", "a", "r", "e", "w" };
		ah.sort(result = initial.clone());
		restored = restore(result, ah.getReverse());
		Assert.assertArrayEquals(initial, restored);
	}

	private static String[] restore(String[] sorted, int[] sortReverse) {
		String[] rebuilt = new String[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			int indexInOriginal = sortReverse[i];
			rebuilt[indexInOriginal-1] = sorted[i];
		}
		return rebuilt;
	}
}
