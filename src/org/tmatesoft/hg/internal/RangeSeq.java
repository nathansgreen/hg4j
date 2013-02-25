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
package org.tmatesoft.hg.internal;

import java.util.Formatter;

/**
 * Sequence of range pairs (denoted origin and target), {originStart, targetStart, length}, tailored for diff/annotate
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class RangeSeq {
	// XXX smth like IntSliceVector to access triples (or slices of any size, in fact)
	// with easy indexing, e.g. #get(sliceIndex, indexWithinSlice)
	// and vect.get(7,2) instead of vect.get(7*SIZEOF_SLICE+2)
	private final IntVector ranges = new IntVector(3*10, 3*5);
	private int count;
	
	public void add(int start1, int start2, int length) {
		if (count > 0) {
			int lastIndex = 3 * (count-1);
			int lastS1 = ranges.get(lastIndex);
			int lastS2 = ranges.get(lastIndex + 1);
			int lastLen = ranges.get(lastIndex + 2);
			if (start1 == lastS1 + lastLen && start2 == lastS2 + lastLen) {
				// new range continues the previous one - just increase the length
				ranges.set(lastIndex + 2, lastLen + length);
				return;
			}
		}
		ranges.add(start1, start2, length);
		count++;
	}
	
	public void clear() {
		ranges.clear();
		count = 0;
	}

	public int size() {
		return count;
	}

	/**
	 * find out line index in the target that matches specified origin line
	 */
	public int mapLineIndex(int ln) {
		for (int i = 0; i < ranges.size(); i += 3) {
			int s1 = ranges.get(i);
			if (s1 > ln) {
				return -1;
			}
			int l = ranges.get(i+2);
			if (s1 + l > ln) {
				int s2 = ranges.get(i + 1);
				return s2 + (ln - s1);
			}
		}
		return -1;
	}
	
	/**
	 * find out line index in origin that matches specified target line
	 */
	public int reverseMapLine(int targetLine) {
		for (int i = 0; i < ranges.size(); i +=3) {
			int ts = ranges.get(i + 1);
			if (ts > targetLine) {
				return -1;
			}
			int l = ranges.get(i + 2);
			if (ts + l > targetLine) {
				int os = ranges.get(i);
				return os + (targetLine - ts);
			}
		}
		return -1;
	}
	
	public RangeSeq intersect(RangeSeq target) {
		RangeSeq v = new RangeSeq();
		for (int i = 0; i < ranges.size(); i += 3) {
			int originLine = ranges.get(i);
			int targetLine = ranges.get(i + 1);
			int length = ranges.get(i + 2);
			int startTargetLine = -1, startOriginLine = -1, c = 0;
			for (int j = 0; j < length; j++) {
				int lnInFinal = target.mapLineIndex(targetLine + j);
				if (lnInFinal == -1 || (startTargetLine != -1 && lnInFinal != startTargetLine + c)) {
					// the line is not among "same" in ultimate origin
					// or belongs to another/next "same" chunk 
					if (startOriginLine == -1) {
						continue;
					}
					v.add(startOriginLine, startTargetLine, c);
					c = 0;
					startOriginLine = startTargetLine = -1;
					// fall-through to check if it's not complete miss but a next chunk
				}
				if (lnInFinal != -1) {
					if (startOriginLine == -1) {
						startOriginLine = originLine + j;
						startTargetLine = lnInFinal;
						c = 1;
					} else {
						// lnInFinal != startTargetLine + s is covered above
						assert lnInFinal == startTargetLine + c;
						c++;
					}
				}
			}
			if (startOriginLine != -1) {
				assert c > 0;
				v.add(startOriginLine, startTargetLine, c);
			}
		}
		return v;
	}
	
	// true when specified line in origin is equal to a line in target
	public boolean includesOriginLine(int ln) {
		return includes(ln, 0);
	}
	
	// true when specified line in target is equal to a line in origin
	public boolean includesTargetLine(int ln) {
		return includes(ln, 1);
	}

	private boolean includes(int ln, int o) {
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

	public CharSequence dump() {
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);
		for (int i = 0; i < ranges.size(); i += 3) {
			int s1 = ranges.get(i);
			int s2 = ranges.get(i + 1);
			int len = ranges.get(i + 2);
			f.format("[%d..%d) == [%d..%d);  ", s1, s1 + len, s2, s2 + len);
		}
		return sb;
	}
	
	@Override
	public String toString() {
		return String.format("RangeSeq[%d]", count);
	}
}