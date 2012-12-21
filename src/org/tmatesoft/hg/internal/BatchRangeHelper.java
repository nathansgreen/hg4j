/*
 * Copyright (c) 2012 TMate Software Ltd
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

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * Helper to break given range [start..end] (inclusive bounds) to series of ranges,
 * all but last are of batchSize length, and the last one is at most of batchSize+batchSizeTolerance length.
 * 
 * Range is [{@link #start()rangeStart}..{@link #end() rangeEnd}], where rangeStart is less or equal to rangeEnd.
 * 
 * When reverse range iteration is requested, original range is iterated from end to start, but the subranges 
 * boundaries are in natural order. i.e. for 0..100, 10 first subrange would be [91..100], not [100..91]. This 
 * helps clients of this class to get [start()..end()] in natural order regardless of iteration direction.
 * 
 * Note, this class (and its treatment of inclusive boundaries) is designed solely for use with methods that navigate
 * revlogs and take (start,end) pair of inclusive range.
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BatchRangeHelper {

	private final int rangeCount;
	private final int rangeDelta;
	private final int nextValueDelta;
	private final int firstBoundary, lastBoundary;
	private int rangeIndex, rangeValue, rangeStart, rangeEnd;

	public BatchRangeHelper(int start, int end, int batchSize, boolean reverse) {
		this(start, end, batchSize, batchSize/5, reverse); 
	}

	public BatchRangeHelper(int start, int end, int batchSize, int batchSizeTolerance, boolean reverse) {
		assert end >= start;
		assert start >= 0;
		assert batchSize > 0;
		assert batchSizeTolerance >= 0;
		final int totalElements = end-start+1;
		int batchRangeCount = totalElements / batchSize;
		// batchRangeCount == 0, totalElements > 0 => need at least 1 range
		if (batchRangeCount == 0 || batchRangeCount*batchSize+batchSizeTolerance < totalElements) {
			batchRangeCount++;
		}
		rangeCount = batchRangeCount;
		rangeDelta = batchSize-1; // ranges are inclusive, and always grow naturally.
		nextValueDelta = reverse ? -batchSize : batchSize;
		firstBoundary = reverse ? end-rangeDelta : start;
		lastBoundary = reverse ? start : end;
		reset();
	}

	public boolean hasNext() {
		return rangeIndex < rangeCount;
	}
	
	public void next() {
		if (!hasNext()) {
			throw new NoSuchElementException();
		}
		rangeStart = rangeValue;
		rangeEnd = rangeValue + rangeDelta;
		rangeValue += nextValueDelta;
		if (++rangeIndex >= rangeCount) {
			if (nextValueDelta < 0) {
				// reverse iteration, lastBoundary represents start
				rangeStart = lastBoundary;
			} else {
				// lastBoundary represents end
				rangeEnd = lastBoundary;
			}
		}
	}
	
	public int start() {
		return rangeStart;
	}
	
	public int end() {
		return rangeEnd;
	}
	
	public BatchRangeHelper reset() {
		rangeValue = firstBoundary;
		rangeIndex = 0;
		return this;
	}

	public int[] toArray() {
		int[] rv = new int[rangeCount*2];
		reset();
		int i = 0;
		while (hasNext()) {
			next();
			rv[i++] = start();
			rv[i++] = end();
		}
		reset();
		return rv;
	}

	public static void main(String[] args) {
		System.out.println("With remainder within tolerance");
		System.out.println(Arrays.toString(new BatchRangeHelper(0, 102, 10, 4, false).toArray()));
		System.out.println(Arrays.toString(new BatchRangeHelper(0, 102, 10, 4, true).toArray()));
		System.out.println("With remainder out of tolerance");
		System.out.println(Arrays.toString(new BatchRangeHelper(0, 102, 10, 2, false).toArray()));
		System.out.println(Arrays.toString(new BatchRangeHelper(0, 102, 10, 2, true).toArray()));
		System.out.println("Range smaller than batch");
		System.out.println(Arrays.toString(new BatchRangeHelper(1, 9, 10, false).toArray()));
		System.out.println(Arrays.toString(new BatchRangeHelper(1, 9, 10, true).toArray()));
		System.out.println("Range smaller than batch and smaller than tolerance");
		System.out.println(Arrays.toString(new BatchRangeHelper(1, 9, 10, 20, false).toArray()));
		System.out.println(Arrays.toString(new BatchRangeHelper(1, 9, 10, 20, true).toArray()));
		System.out.println("Zero tolerance");
		System.out.println(Arrays.toString(new BatchRangeHelper(0, 100, 10, 0, false).toArray()));
		System.out.println(Arrays.toString(new BatchRangeHelper(0, 100, 10, 0, true).toArray()));
		System.out.println("Right to boundary");
		System.out.println(Arrays.toString(new BatchRangeHelper(1, 100, 10, false).toArray()));
		System.out.println(Arrays.toString(new BatchRangeHelper(1, 100, 10, true).toArray()));
	}
}
