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
package org.tmatesoft.hg.internal;

import java.util.Arrays;

/**
 * Internal alternative to Arrays.sort to build reversed index along with sorting
 * and to perform lookup (binary search) without sorted array, using reversed index.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class ArrayHelper<T extends Comparable<T>> {
	private int[] reverse; // aka sorted2natural
	private final T[] data;
	private T[] sorted;
	
	public ArrayHelper(T[] _data) {
		assert _data != null;
		data = _data;
	}

	/**
	 * Sort data this helper wraps, possibly using supplied array (optional)
	 * to keep sorted elements
	 * @param sortDest array to keep sorted values at, or <code>null</code>
	 * @param sortDestIsEmpty <code>false</code> when sortDest already contains copy of data to be sorted
	 * @param keepSorted <code>true</code> to save sorted array for future use (e.g. in
	 */
	public void sort(T[] sortDest, boolean sortDestIsEmpty, boolean keepSorted) {
		if (sortDest != null) {
			assert sortDest.length >= data.length;
			if (sortDestIsEmpty) {
				System.arraycopy(data, 0, sortDest, 0, data.length);
			}
			sorted = sortDest;
		} else {
			sorted = data.clone();
		}
		reverse = new int[data.length];
		for (int i = 0; i < reverse.length; i++) {
			// initial reverse indexes, so that elements that do
			// not move during sort got correct indexes
			reverse[i] = i;
		}
		sort1(0, data.length);
		if (!keepSorted) {
			sorted = null;
		}
	}

	/**
	 * @return all reverse indexes
	 */
	public int[] getReverseIndexes() {
		return reverse;
	}
	
	public int getReverseIndex(int sortedIndex) {
		return reverse[sortedIndex];
	}
	
	public T get(int index) {
		return data[index];
	}
	
	public T[] getData() {
		return data;
	}

	/**
	 * Look up sorted index of the value, using sort information 
	 * @return same value as {@link Arrays#binarySearch(Object[], Object)} does
	 */
	public int binarySearchSorted(T value) {
		if (sorted != null) {
			return Arrays.binarySearch(sorted, 0, data.length, value);
		}
		return binarySearchWithReverse(0, data.length, value);
	}

	/**
	 * Look up index of the value in the original array.
	 * @return index in original data, or <code>defaultValue</code> if value not found
	 */
	public int binarySearch(T value, int defaultValue) {
		int x = binarySearchSorted(value);
		if (x < 0) {
			return defaultValue;
		}
		return reverse[x];
	}

	/**
	 * Slightly modified version of Arrays.sort1(int[], int, int) quicksort alg (just to deal with Object[])
	 */
    private void sort1(int off, int len) {
		@SuppressWarnings("unchecked")
		Comparable<Object>[] x = (Comparable<Object>[]) sorted;
    	// Insertion sort on smallest arrays
    	if (len < 7) {
    	    for (int i=off; i<len+off; i++)
    			for (int j=i; j>off && x[j-1].compareTo(x[j]) > 0; j--)
    			    swap(j, j-1);
    	    return;
    	}

    	// Choose a partition element, v
    	int m = off + (len >> 1);       // Small arrays, middle element
    	if (len > 7) {
    	    int l = off;
    	    int n = off + len - 1;
    	    if (len > 40) {        // Big arrays, pseudomedian of 9
    			int s = len/8;
	    		l = med3(l,     l+s, l+2*s);
	    		m = med3(m-s,   m,   m+s);
	    		n = med3(n-2*s, n-s, n);
    	    }
    	    m = med3(l, m, n); // Mid-size, med of 3
    	}
    	Comparable<Object> v = x[m];

    	// Establish Invariant: v* (<v)* (>v)* v*
    	int a = off, b = a, c = off + len - 1, d = c;
    	while(true) {
    	    while (b <= c && x[b].compareTo(v) <= 0) {
    			if (x[b] == v)
    			    swap(a++, b);
    			b++;
    	    }
    	    while (c >= b && x[c].compareTo(v) >= 0) {
    			if (x[c] == v)
    			    swap(c, d--);
    			c--;
    	    }
    	    if (b > c)
    			break;
    	    swap(b++, c--);
    	}

    	// Swap partition elements back to middle
    	int s, n = off + len;
    	s = Math.min(a-off, b-a  );  vecswap(off, b-s, s);
    	s = Math.min(d-c,   n-d-1);  vecswap(b,   n-s, s);

    	// Recursively sort non-partition-elements
    	if ((s = b-a) > 1)
    	    sort1(off, s);
    	if ((s = d-c) > 1)
    	    sort1(n-s, s);
    }

    /**
     * Swaps x[a .. (a+n-1)] with x[b .. (b+n-1)].
     */
    private void vecswap(int a, int b, int n) {
		for (int i=0; i<n; i++, a++, b++) {
		    swap(a, b);
		}
    }

    /**
     * Returns the index of the median of the three indexed integers.
     */
    private int med3(int a, int b, int c) {
		@SuppressWarnings("unchecked")
		Comparable<Object>[] x = (Comparable<Object>[]) sorted;
		return (x[a].compareTo(x[b]) < 0 ?
			(x[b].compareTo(x[c]) < 0 ? b : x[a].compareTo(x[c]) < 0 ? c : a) :
			(x[b].compareTo(x[c]) > 0 ? b : x[a].compareTo(x[c]) > 0 ? c : a));
    }

    /**
	 * Swaps x[a] with x[b].
	 */
	private void swap(int a, int b) {
		Object[] x = sorted;
		Object t = x[a];
		x[a] = x[b];
		x[b] = t;
		int z1 = reverse[a];
		int z2 = reverse[b];
		reverse[b] = z1;
		reverse[a] = z2;
	}

	// copied from Arrays.binarySearch0, update to be instance method and to use reverse indexes
	private int binarySearchWithReverse(int fromIndex, int toIndex, T key) {
		int low = fromIndex;
		int high = toIndex - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;
			// data[reverse[x]] gives sorted value at index x
			T midVal = data[reverse[mid]];
			int cmp = midVal.compareTo(key);

			if (cmp < 0)
				low = mid + 1;
			else if (cmp > 0)
				high = mid - 1;
			else
				return mid; // key found
		}
		return -(low + 1);  // key not found.
	}

}
