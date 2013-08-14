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

import java.util.Formatter;

import org.tmatesoft.hg.internal.IntSliceSeq;
import org.tmatesoft.hg.internal.IntTuple;

/**
 * Sequence of range pairs (denoted origin and target), {originStart, targetStart, length}, tailored for diff/annotate
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class RangePairSeq {
	private final IntSliceSeq ranges = new IntSliceSeq(3);
	
	public void add(int start1, int start2, int length) {
		int count = ranges.size();
		if (count > 0) {
			int lastS1 = ranges.get(--count, 0);
			int lastS2 = ranges.get(count, 1);
			int lastLen = ranges.get(count, 2);
			if (start1 == lastS1 + lastLen && start2 == lastS2 + lastLen) {
				// new range continues the previous one - just increase the length
				ranges.set(count, lastS1, lastS2, lastLen + length);
				return;
			}
		}
		ranges.add(start1, start2, length);
	}
	
	public void clear() {
		ranges.clear();
	}

	public int size() {
		return ranges.size();
	}

	/**
	 * find out line index in the target that matches specified origin line
	 */
	public int mapLineIndex(int ln) {
		for (IntTuple t : ranges) {
			int s1 = t.at(0);
			if (s1 > ln) {
				return -1;
			}
			int l = t.at(2);
			if (s1 + l > ln) {
				int s2 = t.at(1);
				return s2 + (ln - s1);
			}
		}
		return -1;
	}
	
	/**
	 * find out line index in origin that matches specified target line
	 */
	public int reverseMapLine(int targetLine) {
		for (IntTuple t : ranges) {
			int ts = t.at(1);
			if (ts > targetLine) {
				return -1;
			}
			int l = t.at(2);
			if (ts + l > targetLine) {
				int os = t.at(0);
				return os + (targetLine - ts);
			}
		}
		return -1;
	}
	
	public RangePairSeq intersect(RangePairSeq target) {
		RangePairSeq v = new RangePairSeq();
		for (IntTuple t : ranges) {
			int originLine = t.at(0);
			int targetLine = t.at(1);
			int length = t.at(2);
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
		for (IntTuple t : ranges) {
			int rangeStart = t.at(o);
			if (rangeStart > ln) {
				return false;
			}
			int rangeLen = t.at(2);
			if (rangeStart + rangeLen > ln) {
				return true;
			}
		}
		return false;
	}

	public CharSequence dump() {
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);
		for (IntTuple t : ranges) {
			int s1 = t.at(0);
			int s2 = t.at(1);
			int len = t.at(2);
			f.format("[%d..%d) == [%d..%d);  ", s1, s1 + len, s2, s2 + len);
		}
		return sb;
	}
	
	@Override
	public String toString() {
		return String.format("RangeSeq[%d]:%s", size(), dump());
	}
}