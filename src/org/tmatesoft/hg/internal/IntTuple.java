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

/**
 * Tuple of integers
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class IntTuple implements Cloneable {
	private final int size;
	private IntVector v;
	private int start;

	IntTuple(int length) {
		size = length;
	}
	/*package*/IntTuple set(IntVector vect, int index) {
		v = vect;
		start = index;
		return this;
	}
	
	public int size() {
		return size;
	}

	public int at(int index) {
		if (index < size) {
			return v.get(start + index);
		}
		throw new IllegalArgumentException(String.valueOf(index));
	}
	
	public IntTuple clone() {
		try {
			return (IntTuple) super.clone();
		} catch (CloneNotSupportedException ex) {
			throw new Error(ex);
		}
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append('(');
		for (int i = 0; i < size; i++) {
			sb.append(at(i));
			sb.append(", ");
		}
		sb.setLength(sb.length() - 2);
		sb.append(')');
		return sb.toString();
	}
}