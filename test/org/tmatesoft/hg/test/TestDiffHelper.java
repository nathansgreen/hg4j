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

import static org.junit.Assert.*;
import static org.tmatesoft.hg.internal.DiffHelper.LineSequence.newlines;

import org.junit.Test;
import org.tmatesoft.hg.internal.DiffHelper;
import org.tmatesoft.hg.internal.DiffHelper.ChunkSequence;
import org.tmatesoft.hg.internal.DiffHelper.LineSequence;
import org.tmatesoft.hg.internal.IntVector;

/**
 * Testing DiffHelper (foundation for facilities like commit and annotate) directly
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestDiffHelper {

	@Test
	public void testSimple() {
		DiffHelper<LineSequence> diffHelper = new DiffHelper<LineSequence>();
		MatchCollector<LineSequence> mc; DeltaCollector dc;

		// single change
		diffHelper.init(newlines("hello\nabc".getBytes()), newlines("hello\nworld".getBytes()));
		diffHelper.findMatchingBlocks(mc = new MatchCollector<LineSequence>());
		assertEquals(1, mc.matchCount());
		assertTrue(mc.originLineMatched(0));
		assertTrue(mc.targetLineMatched(0));
		assertFalse(mc.originLineMatched(1));
		assertFalse(mc.targetLineMatched(1));
		diffHelper.findMatchingBlocks(dc = new DeltaCollector());
		assertEquals(1, dc.unchangedCount());
		assertEquals(1, dc.deletedCount());
		assertEquals(1, dc.addedCount());

		// boundary case, additions to an empty origin
		diffHelper.init(newlines("".getBytes()), newlines("hello\nworld".getBytes()));
		diffHelper.findMatchingBlocks(mc = new MatchCollector<LineSequence>());
		assertEquals(0, mc.matchCount());
		diffHelper.findMatchingBlocks(dc = new DeltaCollector());
		assertEquals(0, dc.unchangedCount());
		assertEquals(0, dc.deletedCount());
		assertEquals(1, dc.addedCount()); // two lines added, but 1 range

		// boundary case, complete deletion
		diffHelper.init(newlines("hello\nworld".getBytes()), newlines("".getBytes()));
		diffHelper.findMatchingBlocks(mc = new MatchCollector<LineSequence>());
		assertEquals(0, mc.matchCount());
		diffHelper.findMatchingBlocks(dc = new DeltaCollector());
		assertEquals(0, dc.unchangedCount());
		assertEquals(1, dc.deletedCount());
		assertEquals(0, dc.addedCount());

		// regular case, few changes
		String s1 = "line 1\nline 2\r\nline 3\n\nline 1\nline 2";
		String s2 = "abc\ncdef\r\nline 2\r\nline 3\nline 2";
		diffHelper.init(newlines(s1.getBytes()), newlines(s2.getBytes()));
		diffHelper.findMatchingBlocks(mc = new MatchCollector<LineSequence>());
		assertEquals(2, mc.matchCount());
		assertFalse(mc.originLineMatched(0));
		assertTrue(mc.originLineMatched(1));
		assertTrue(mc.originLineMatched(2));
		assertFalse(mc.originLineMatched(3));
		assertFalse(mc.originLineMatched(4));
		assertTrue(mc.originLineMatched(5));
		assertFalse(mc.targetLineMatched(0));
		assertFalse(mc.targetLineMatched(1));
		assertTrue(mc.targetLineMatched(2));
		assertTrue(mc.targetLineMatched(3));
		assertTrue(mc.targetLineMatched(4));
		diffHelper.findMatchingBlocks(dc = new DeltaCollector());
		assertEquals(2, dc.unchangedCount()); // 3 lines but 2 ranges
		assertEquals(2, dc.deletedCount());
		assertEquals(1, dc.addedCount());
		assertTrue(dc.deletedLine(0));
		assertTrue(dc.deletedLine(3));
		assertTrue(dc.deletedLine(4));
		assertTrue(dc.addedLine(0));
		assertTrue(dc.addedLine(1));
	}
	
	@Test
	public void testOtherSequence() {
		class CharSequence implements DiffHelper.ChunkSequence<Character> {
			private final char[] chunks;

			CharSequence(String s) {
				chunks = s.toCharArray();
			}
			public Character chunk(int index) {
				return chunks[index];
			}
			public int chunkCount() {
				return chunks.length;
			}
		}
		DiffHelper<CharSequence> diff = new DiffHelper<CharSequence>();
		diff.init(new CharSequence("abcefg"), new CharSequence("bcdegh"));
		MatchCollector<CharSequence> mc;
		diff.findMatchingBlocks(mc = new MatchCollector<CharSequence>());
		assertEquals(3, mc.matchCount()); // bc, e, g
	}
	
	// range is comprised of 3 values, range length always last, range start comes at index o (either 0 or 1)
	static boolean includes(IntVector ranges, int o, int ln) {
		assert ranges.size() % 3 == 0;
		for (int i = 2; i < ranges.size(); o += 3, i+=3) {
			int rangeStart = ranges.get(o);
			if (rangeStart > ln) {
				return false;
			}
			int rangeLen = ranges.get(i);
			if (rangeStart + rangeLen > ln) {
				return true;
			}
		}
		return false;
	}

	static class MatchCollector<T extends ChunkSequence<?>> implements DiffHelper.MatchInspector<T> {
		private IntVector matched = new IntVector(10 * 3, 5 * 3);

		public void begin(T s1, T s2) {
		}

		public void match(int startSeq1, int startSeq2, int matchLength) {
			matched.add(startSeq1, startSeq2, matchLength);
		}

		public void end() {
		}
		
		int matchCount() {
			return matched.size() / 3;
		}
		
		// true if zero-based line matches any "same" block in the origin
		boolean originLineMatched(int ln) {
			return includes(matched, 0, ln);
		}
		
		boolean targetLineMatched(int ln) {
			return includes(matched, 1, ln);
		}
	}
	
	static class DeltaCollector extends DiffHelper.DeltaInspector<LineSequence> {
		private IntVector added, deleted, same;
		public DeltaCollector() {
			final int x = 10 * 3, y = 5 * 3;
			added = new IntVector(x, y);
			deleted = new IntVector(x, y);
			same = new IntVector(x, y);
		}
		@Override
		protected void added(int s1InsertPoint, int s2From, int s2To) {
			added.add(s1InsertPoint, s2From, s2To - s2From);
		}
		@Override
		protected void changed(int s1From, int s1To, int s2From, int s2To) {
			deleted(s2From, s1From, s1To);
			added(s1From, s2From, s2To);
		}
		@Override
		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			deleted.add(s2DeletePoint, s1From, s1To - s1From);
		}
		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			same.add(s1From, s2From, length);
		}

		int unchangedCount() {
			return same.size() / 3;
		}

		int addedCount() {
			return added.size() / 3;
		}

		int deletedCount() {
			return deleted.size() / 3;
		}
		boolean addedLine(int ln) {
			return includes(added, 1, ln);
		}
		boolean deletedLine(int ln) {
			return includes(deleted, 1, ln);
		}
	}
}
