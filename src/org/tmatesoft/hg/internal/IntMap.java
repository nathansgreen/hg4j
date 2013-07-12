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
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;


/**
 * Map implementation that uses plain int keys and performs with log n effectiveness.
 * May contain null values
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class IntMap<V> {

	private int[] keys;
	private Object[] values;
	private int size;
	
	public IntMap(int size) {
		keys = new int[size <= 0 ? 16 : size];
		values = new Object[keys.length];
	}
	
	public int size() {
		return size;
	}
	
	public int firstKey() {
		if (size == 0) {
			throw new NoSuchElementException();
		}
		return keys[0];
	}

	public int lastKey() {
		if (size == 0) {
			throw new NoSuchElementException();
		}
		return keys[size-1];
	}
	
	public void trimToSize() {
		if (size < keys.length) {
			int[] newKeys = new int[size];
			Object[] newValues = new Object[size];
			System.arraycopy(keys, 0, newKeys, 0, size);
			System.arraycopy(values, 0, newValues, 0, size);
			keys = newKeys;
			values = newValues;
		}
	}

	public void put(int key, V value) {
		int ix = binarySearch(keys, size, key);
		if (ix < 0) {
			final int insertPoint = -ix - 1;
			assert insertPoint <= size; // can't be greater, provided binarySearch didn't malfunction.
			if (size == keys.length) {
				int capInc = size >>> 2; // +25%
				int newCapacity = size + (capInc < 2 ? 2 : capInc) ;
				int[] newKeys = new int[newCapacity];
				Object[] newValues = new Object[newCapacity];
				System.arraycopy(keys, 0, newKeys, 0, insertPoint);
				System.arraycopy(keys, insertPoint, newKeys, insertPoint+1, keys.length - insertPoint);
				System.arraycopy(values, 0, newValues, 0, insertPoint);
				System.arraycopy(values, insertPoint, newValues, insertPoint+1, values.length - insertPoint);
				keys = newKeys;
				values = newValues;
			} else {
				// arrays got enough capacity
				if (insertPoint != size) {
					System.arraycopy(keys, insertPoint, keys, insertPoint+1, keys.length - insertPoint - 1);
					System.arraycopy(values, insertPoint, values, insertPoint+1, values.length - insertPoint - 1);
				}
				// else insertPoint is past known elements, no need to copy arrays
			}
			keys[insertPoint] = key;
			values[insertPoint] = value;
			size++;
		} else {
			values[ix] = value;
		}
	}
	
	public boolean containsKey(int key) {
		return binarySearch(keys, size, key) >= 0;
	}

	@SuppressWarnings("unchecked")
	public V get(int key) {
		int ix = binarySearch(keys, size, key);
		if (ix >= 0) {
			return (V) values[ix];
		}
		return null;
	}
	
	public void remove(int key) {
		int ix = binarySearch(keys, size, key);
		if (ix >= 0) {
			if (ix <= size - 1) {
				System.arraycopy(keys, ix+1, keys, ix, size - ix - 1);
				System.arraycopy(values, ix+1, values, ix, size - ix - 1);
			} // if ix points to last element, no reason to attempt a copy
			size--;
			keys[size] = 0;
			values[size] = null;
		}
	}
	
	public void clear() {
		Arrays.fill(values, 0, size, null); // do not keep the references
		size = 0;
	}
	
	/**
	 * Forget first N entries (in natural order) in the map.
	 */
	public void removeFromStart(int count) {
		if (count > 0 && count <= size) {
			if (count < size) {
				System.arraycopy(keys, count, keys, 0, size - count);
				System.arraycopy(values, count, values, 0, size - count);
			}
			for (int i = size - count; i < size; i++) {
				keys[i] = 0;
				values[i] = null;
			}
			size -= count;
		} 
	}

	// document iterator is non-modifying (neither remove() nor setValue() works)
	// perhaps, may also implement Iterable<Map.Entry> to use nice for()
	public Iterator<Map.Entry<Integer, V>> entryIterator() {
		class E implements Map.Entry<Integer, V> {
			private Integer key;
			private V value;

			public Integer getKey() {
				return key;
			}

			public V getValue() {
				return value;
			}

			public V setValue(V value) {
				throw new UnsupportedOperationException();
			}
			
			void init(Integer k, V v) {
				key = k;
				value = v;
			}
		}
		
		return new Iterator<Map.Entry<Integer, V>>() {
			private int i = 0;
			private final E entry = new E();
			private final int _size;
			private final int[] _keys;
			private final Object[] _values;
			
			{
				_size = IntMap.this.size;
				_keys = IntMap.this.keys;
				_values = IntMap.this.values;
			}

			public boolean hasNext() {
				return i < _size;
			}

			public Entry<Integer, V> next() {
				if (i >= _size) {
					throw new NoSuchElementException();
				}
				@SuppressWarnings("unchecked")
				V val = (V) _values[i];
				entry.init(_keys[i], val);
				i++;
				return entry;
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}
	
	public Map<Integer, ? super V> fill(Map<Integer, ? super V> map) {
		for (Iterator<Map.Entry<Integer, V>> it = entryIterator(); it.hasNext(); ) {
			Map.Entry<Integer, V> next = it.next();
			map.put(next.getKey(), next.getValue());
		}
		return map;
	}
	
	public int[] keys() {
		int[] rv = new int[size];
		System.arraycopy(keys, 0, rv, 0, size);
		return rv;
	}
	
	public Collection<V> values() {
		@SuppressWarnings("unchecked")
		V[] rv = (V[]) new Object[size];
		System.arraycopy(values, 0, rv, 0, size);
		return Arrays.<V>asList(rv);
	}

	// copy of Arrays.binarySearch, with upper search limit as argument
	private static int binarySearch(int[] a, int high, int key) {
		int low = 0;
		high--;

		while (low <= high) {
			int mid = (low + high) >> 1;
			int midVal = a[mid];

			if (midVal < key)
				low = mid + 1;
			else if (midVal > key)
				high = mid - 1;
			else
				return mid; // key found
		}
		return -(low + 1); // key not found.
	}
}
