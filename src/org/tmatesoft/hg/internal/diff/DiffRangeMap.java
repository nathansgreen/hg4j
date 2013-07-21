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
package org.tmatesoft.hg.internal.diff;

import java.util.ArrayList;

import org.tmatesoft.hg.internal.DiffHelper;
import org.tmatesoft.hg.internal.IntSliceSeq;
import org.tmatesoft.hg.internal.IntTuple;
import org.tmatesoft.hg.internal.DiffHelper.ChunkSequence;

/**
 * Sequence of pairs of ranges (s1Start,s1End) - (s2Start, s2End)
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class DiffRangeMap extends DiffHelper.DeltaInspector<ChunkSequence<?>> {
	private final IntSliceSeq ranges;
	
	public DiffRangeMap() {
		ranges = new IntSliceSeq(5);
	}
	
	/**
	 * handy method to fill this map from supplied DiffHelper
	 * <pre>
	 *   DiffHelper<LineSequence> pg = ...
	 *   pg.findMatchingBlocks(p1ToBase); // doesn't compile
	 *   DiffHelper<?> dh = pg;
	 *   dh.findMatchingBlocks(p1ToBase); // compiles ok!
	 * </pre>
	 */
	public DiffRangeMap fill(DiffHelper<?> dh) {
		dh.findMatchingBlocks(this);
		return this;
	}
	
	@Override
	protected void added(int s1InsertPoint, int s2From, int s2To) {
		ranges.add(s1InsertPoint, s1InsertPoint, s2From, s2To, (int)'+');
	}
	@Override
	protected void changed(int s1From, int s1To, int s2From, int s2To) {
		ranges.add(s1From, s1To, s2From, s2To, (int)'*');
	}
	@Override
	protected void deleted(int s2DeletePoint, int s1From, int s1To) {
		ranges.add(s1From, s1To, s2DeletePoint, s2DeletePoint, (int)'-');
	}
	@Override
	protected void unchanged(int s1From, int s2From, int length) {
		ranges.add(s1From, s1From + length, s2From, s2From + length, (int)'=');
	}

	public Iterable<RangePair> findInSource(int sourceStart, int sourceEnd) {
		ArrayList<RangePair> rv = new ArrayList<RangePair>(4); 
		for (IntTuple t : ranges) {
			int srcRangeStart = t.at(0);
			int srcRangeEnd = t.at(1);
			if (srcRangeEnd <= sourceStart) { // srcRangeEnd exclusive
				continue;
			}
			if (srcRangeStart >= sourceEnd) {
				break;
			}
			rv.add(new RangePair(srcRangeStart, srcRangeEnd, t.at(2), t.at(3)));
		}
		return rv;
	}
	
	public Iterable<RangePair> insertions() {
		return rangesOfKind('+');
	}

	public Iterable<RangePair> same() {
		return rangesOfKind('=');
	}

	private Iterable<RangePair> rangesOfKind(int kind) {
		ArrayList<RangePair> rv = new ArrayList<RangePair>(4); 
		for (IntTuple t : ranges) {
			if (t.at(4) == kind) {
				rv.add(new RangePair(t.at(0), t.at(1), t.at(2), t.at(3)));
			}
		}
		return rv;
	}
	
	public static final class RangePair {
		private final int s1Start;
		private final int s1End;
		private final int s2Start;
		private final int s2End;

		public RangePair(int s1Start, int s1End, int s2Start, int s2End) {
			this.s1Start = s1Start;
			this.s1End = s1End;
			this.s2Start = s2Start;
			this.s2End = s2End;
		}
		public int start1() {
			return s1Start;
		}
		public int end1() {
			return s1End;
		}
		public int length1() {
			return s1End - s1Start;
		}
		public int start2() {
			return s2Start;
		}
		public int end2() {
			return s2End;
		}
		public int length2() {
			return s2End - s2Start;
		}
		@Override
		public String toString() {
			return String.format("[%d..%d)->[%d..%d)", s1Start, s1End, s2Start, s2End);
		}
	}
}
