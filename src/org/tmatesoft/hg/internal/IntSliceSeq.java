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

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class IntSliceSeq implements Iterable<IntTuple>, Cloneable {
	private final IntVector slices;
	private final int slice;

	public IntSliceSeq(int sliceSize) {
		// initial size/grow values are pure guess
		this(sliceSize, 10, 5);
	}
	
	public IntSliceSeq(int sliceSize, int initialSlices, int slicesToGrow) {
		slices = new IntVector(sliceSize * initialSlices, sliceSize*slicesToGrow);
		slice = sliceSize;
	}
	
	public IntSliceSeq add(int... values) {
		checkValuesAny(values);
		slices.add(values);
		return this;
	}
	
	public IntSliceSeq set(int sliceIndex, int... values) {
		checkValuesExact(values);
		for (int i = 0, j = sliceIndex*slice; i < slice; i++,j++) {
			slices.set(j, values[i]);
		}
		return this;
	}
	
	public IntTuple get(int sliceIndex) {
		checkArgRange(size(), sliceIndex);
		return new IntTuple(slice).set(slices, sliceIndex*slice);
	}
	
	public int get(int sliceIndex, int valueIndex) {
		checkArgRange(size(), sliceIndex);
		checkArgRange(slice, valueIndex);
		return slices.get(sliceIndex*slice + valueIndex);
	}
	
	public void addAll(IntSliceSeq other) {
		if (other.slice != this.slice) {
			throw new IllegalArgumentException(String.format("Tuple size doesn't match: %d and %d", slice, other.slice));
		}
		slices.addAll(other.slices);
	}
	
	public int size() {
		return slices.size() / slice;
	}
	
	public int sliceSize() {
		return slice;
	}

	public void clear() {
		slices.clear();
	}

	public IntTuple last() {
		int lastElementIndex = (size() - 1);
		if (lastElementIndex < 0) {
			throw new NoSuchElementException();
		}
		return get(lastElementIndex);
	}

	public Iterator<IntTuple> iterator() {
		return new Iterator<IntTuple>() {
			private final IntTuple t = new IntTuple(slice);
			private int next = 0;

			public boolean hasNext() {
				return next < size();
			}

			public IntTuple next() {
				return t.set(slices, next++*slice);
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < size(); i++) {
			sb.append('(');
			for (int j = 0; j < slice; j++) {
				sb.append(slices.get(i*slice + j));
				sb.append(',');
			}
			sb.setLength(sb.length() - 1);
			sb.append(')');
			sb.append(' ');
		}
		return sb.toString();
	}
	
	@Override
	public IntSliceSeq clone() {
		try {
			return (IntSliceSeq) super.clone();
		} catch (CloneNotSupportedException ex) {
			throw new Error(ex);
		}
	}

	private void checkArgRange(int rangeSize, int index) {
		if (index >= 0 && index < rangeSize) {
			return;
		}
		throw new IllegalArgumentException(String.valueOf(index));
	}
	private void checkValuesExact(int[] values) {
		if (values == null || values.length != slice) {
			throw new IllegalArgumentException(String.valueOf(values == null ? values : values.length));
		}
	}
	private void checkValuesAny(int[] values) {
		if (values == null || values.length % slice != 0) {
			throw new IllegalArgumentException(String.valueOf(values == null ? values : values.length));
		}
	}
}
