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

import java.io.ByteArrayOutputStream;

/**
 * Alternative to {@link ByteArrayOutputStream}, with extra operation that prevent superfluous byte[] instances
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ByteVector {
	private byte[] data;
	private int count;
	private final int increment;
	
	
	public ByteVector(int initialSize, int increment) {
		data = new byte[initialSize];
		this.increment = increment;
	}

	public void add(int b) {
		if (count == data.length) {
			byte[] newData = new byte[count + increment];
			System.arraycopy(data, 0, newData, 0, count);
			data = newData;
		}
		data[count++] = (byte) b;
	}

	public int indexOf(int b) {
		for (int i = 0; i < count; i++) {
			if (data[i] == b) {
				return i;
			}
		}
		return -1;
	}

	public byte get(int i) {
		if (i < 0 || i >= count) {
			throw new IllegalArgumentException(String.valueOf(i));
		}
		return data[i];
	}

	public boolean isEmpty() {
		return count == 0;
	}

	public int size() {
		return count;
	}

	public void clear() {
		count = 0;
	}
	
	public boolean equalsTo(byte[] array) {
		if (array == null || array.length != count) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			if (data[i] != array[i]) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Copies content of this vector into destination array.
	 * @param destination array, greater or equal to {@link #size()} of the vector
	 */
	public void copyTo(byte[] destination) {
		if (destination == null || destination.length < count) {
			throw new IllegalArgumentException();
		}
		System.arraycopy(data, 0, destination, 0, count);
	}

	public byte[] toByteArray() {
		byte[] rv = new byte[count];
		copyTo(rv);
		return rv;
	}

	public byte[] toByteArray(int from, int to) {
		if (from > to) {
			throw new IllegalArgumentException();
		}
		if (to > count) {
			throw new IllegalArgumentException();
		}
		byte[] rv = new byte[to-from];
		System.arraycopy(data, from, rv, 0, rv.length);
		return rv;
	}
}
