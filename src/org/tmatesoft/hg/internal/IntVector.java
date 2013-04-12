/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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
 * Vector of primitive values
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class IntVector {
	
	private int[] data;
	private final int increment;
	private int count;


	public IntVector() {
		this(16, -1);
	}

	// increment == -1: grow by power of two.
	// increment == 0: no resize (Exception will be thrown on attempt to add past capacity)
	public IntVector(int initialCapacity, int increment) {
		data = new int[initialCapacity];
		this.increment = increment; 
	}

	public void add(int v) {
		if (count == data.length) {
			grow(0);
		}
		data[count++] = v;
	}
	
	public void add(int... values) {
		if (count + values.length > data.length) {
			grow(count + values.length);
		}
		for (int v : values) {
			data[count++] = v;
		}
	}
	
	public int get(int i) {
		if (i < 0 || i >= count) {
			throw new IndexOutOfBoundsException(String.format("Index: %d, size: %d", i, count));
		}
		return data[i];
	}
	
	public void set(int i, int v) {
		if (i < 0 || i >= count) {
			throw new IndexOutOfBoundsException(String.format("Index: %d, size: %d", i, count));
		}
		data[i] = v;
	}
	
	public int size() {
		return count;
	}
	
	public boolean isEmpty() {
		return count == 0;
	}
	
	public void clear() {
		count = 0;
	}
	
	public void trimTo(int newSize) {
		if (newSize < 0 || newSize > count) {
			throw new IllegalArgumentException(String.format("Can't trim vector of size %d to %d", count, newSize));
		}
		count = newSize;
	}
	
	public void trimToSize() {
		data = toArray(true);
	}


	public int[] toArray() {
		int[] rv = new int[count];
		System.arraycopy(data, 0, rv, 0, count);
		return rv;
	}
	
	public void reverse() {
		for (int a = 0, b = count-1; a < b; a++, b--) {
			int t = data[b];
			data[b] = data[a];
			data[a] = t;
		}
	}

	/**
	 * 
	 * @param ascending <code>true</code> to sort in ascending order, <code>false</code> for descending
	 */
	public void sort(boolean ascending) {
		Arrays.sort(data, 0, count);
		if (!ascending) {
			reverse();
		}
	}


	@Override
	public String toString() {
		return String.format("%s[%d]", IntVector.class.getSimpleName(), size());
	}

	/**
	 * Use only when this instance won't be used any longer
	 */
	@Experimental
	int[] toArray(boolean internalIfSizeMatchCapacity) {
		if (count == data.length) {
			return data;
		}
		return toArray();
	}

	private void grow(int newCapacityHint) {
		if (increment == 0) {
			throw new UnsupportedOperationException("This vector is not allowed to expand");
		}
		int newCapacity = increment < 0 ? data.length << 1 : data.length + increment;
		if (newCapacityHint > 0 && newCapacity < newCapacityHint) {
			newCapacity = newCapacityHint;
		}
		assert newCapacity > 0 && newCapacity != data.length : newCapacity;
		int[] newData = new int[newCapacity];
		System.arraycopy(data, 0, newData, 0, count);
		data = newData;
	}
}
