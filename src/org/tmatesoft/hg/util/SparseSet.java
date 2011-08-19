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
package org.tmatesoft.hg.util;

import java.util.Arrays;

import org.tmatesoft.hg.internal.Experimental;

/**
 * WORK IN PROGRESS, DO NOT USE
 * Memory-friendly alternative to HashMap-backed Pool. Set where object can be obtained (not only queried for presence)
 * 
 * cpython repo, use of HashMap Pool results in ~6 Mb of Map.Entry and Map.Entry[],
 * while use of SparseSet result in 2 Mb. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Requires tuning to accomodate to collection size. Present state (6-6-6) is too much for a lot of uses")
public class SparseSet<T> {
	
	public static void main(String[] args) {
		SparseSet<String> ss = new SparseSet<String>();
		String one = Integer.toString(156), two = Integer.toString(1024), three = Integer.toString(1123123);
		ss.put(one);
		ss.put(two);
		ss.put(three);
		System.out.println(one == ss.get(one));
		System.out.println(two == ss.get(two));
		System.out.println(three == ss.get(three));
		System.out.println(null == ss.get("one"));
		System.out.println(one == ss.get(Integer.toString(156)));
		System.out.println(two == ss.get(Integer.toString(1024)));
		System.out.println(three == ss.get(Integer.toString(1123123)));
		ss.dump();
	}

	@SuppressWarnings("unused")
	private static final int MASK_8BIT = 0xFF, MASK_7BIT = 0x7F, MASK_6BIT = 0x3F, MASK_5BIT = 0x1F, MASK_4BIT = 0x0F;
	private static final int I1_SHIFT = 15, I2_SHIFT = 6, I3_SHIFT = 0;
	// 6, 5, 5
	private static final int I1_MASK = MASK_5BIT, I2_MASK = MASK_4BIT, I3_MASK = MASK_4BIT;

	private final int[] fixups = new int[] {0x1, 0x10, 0xA, 0xD, 0x1F }; // rehash attempts
	private final IndexBranch[] level2 = new IndexBranch[I1_MASK + 1];
	private int size = 0;
	

	//
	int directPut, neighborPut;
	int[] fixupPut1 = new int[fixups.length], fixupPut2 = new int[fixups.length];;

	public void put(T o) {
		final int hash = hash(o);
		final int i1 = (hash >>> I1_SHIFT) & I1_MASK, i2 = (hash >>> I2_SHIFT) & I2_MASK, i3 = (hash >>> I3_SHIFT) & I3_MASK;
		LeafBranch l3 = leafBranchPut(i1, i2);
		int res;
		if ((res = l3.put(i3, o)) != 0) {
			size++;
			if (res == 1) {
				directPut++;
			} else if (res == 2) {
				neighborPut++;
			}
			return;
		}
		for (int i = 0; i < fixups.length; i++) {
			int fixup = fixups[i];
			l3 = leafBranchPut(i1 ^ fixup, i2);
			if (l3.putIfEmptyOrSame(i3, o)) {
				size++;
				fixupPut1[i]++;
				return;
			}
			l3 = leafBranchPut(i1, i2 ^ fixup);
			if (l3.putIfEmptyOrSame(i3, o)) {
				size++;
				fixupPut2[i]++;
				return;
			}
		}
		throw new IllegalStateException(String.valueOf(o));
	}
	
	@SuppressWarnings("unchecked")
	public T get(T o) {
		final int hash = hash(o);
		final int i1 = (hash >>> I1_SHIFT) & I1_MASK, i2 = (hash >>> I2_SHIFT) & I2_MASK, i3 = (hash >>> I3_SHIFT) & I3_MASK;
		//
		LeafBranch l3 = leafBranchGet(i1, i2);
		if (l3 == null) {
			return null;
		}
		Object c;
		if ((c = l3.get(i3, o)) != null) {
			return c == l3 ? null : (T) c;
		}
		if ((c = l3.get(i3 ^ 0x1, o)) != null) {
			return c == l3 ? null : (T) c;
		}
		if ((c = l3.get(i3 ^ 0x2, o)) != null) {
			return c == l3 ? null : (T) c;
		}
		if ((c = l3.get(i3 ^ 0x3, o)) != null) {
			return c == l3 ? null : (T) c;
		}
		for (int fixup : fixups) {
			Object data = leafValueGet(i1 ^ fixup, i2, i3);
			if (data == null) {
				return null;
			}
			if (o.equals(data)) {
				return (T)data;
			}
			data = leafValueGet(i1, i2 ^ fixup, i3);
			if (data == null) {
				return null;
			}
			if (o.equals(data)) {
				return (T)data;
			}
		}
		dump();
		throw new IllegalStateException(String.format("[%d,%d,%d] hash: 0x%X, hash2: 0x%X, %s", i1, i2, i3, o.hashCode(), hash, o));
	}

	public int size() {
		return size;
	}
	private LeafBranch leafBranchPut(int i1, int i2) {
		IndexBranch l2 = level2[i1];
		if (l2 == null) {
			level2[i1] = l2 = new IndexBranch();
		}
		LeafBranch l3 = l2.leafs[i2];
		if (l3 == null) {
			l2.leafs[i2] = l3 = new LeafBranch();
		}
		return l3;
	}

	// unlike regular collection clear, keeps all allocated arrays to minimize gc/reallocate costs
	// do force clean, use #drop
	public void clear() {
		for (int i1 = 0; i1 < level2.length; i1++) {
			IndexBranch l2 = level2[i1];
			if (l2 == null) {
				continue;
			}
			for (int i2 = 0; i2 < l2.leafs.length; i2++) {
				LeafBranch l3 = l2.leafs[i2];
				if (l3 == null) {
					continue;
				}
				for (int i3 = 0; i3 < l3.data.length; i3++) {
					l3.data[i3] = null;
				}
			}
		}
		reset();
	}
	
	public void drop() {
		reset();
		for (int i1 = 0; i1 < level2.length; level2[i1++] = null);
	}
	
	private void reset() {
		size = 0;
		directPut = neighborPut = 0;
		Arrays.fill(fixupPut1, 0);
		Arrays.fill(fixupPut2, 0);
	}

	private LeafBranch leafBranchGet(int i1, int i2) {
		IndexBranch l2 = level2[i1];
		if (l2 == null) {
			return null;
		}
		return l2.leafs[i2];
	}

	private Object leafValueGet(int i1, int i2, int i3) {
		IndexBranch l2 = level2[i1];
		if (l2 == null) {
			return null;
		}
		LeafBranch l3 = l2.leafs[i2];
		if (l3 == null) {
			return null;
		}
		return l3.data[i3];
	}
	
	private int hash(Object o) {
		int h = o.hashCode();
		// HashMap.newHash()
		h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
	}

	@Override
	public String toString() {
		return String.format("SparseSet (0x%02X-0x%02X-0x%02X), %d elements. Direct: %d. Resolutions: neighbour: %d, i1: %s. i2: %s", I1_MASK, I2_MASK, I3_MASK, size, directPut, neighborPut, Arrays.toString(fixupPut1), Arrays.toString(fixupPut2));
	}

	public void dump() {
		int count = 0;
		System.out.println(toString());
		for (int i = 0; i < level2.length; i++) {
			IndexBranch l2 = level2[i];
			if (l2 == null) {
				continue;
			}
			for (int j = 0; j < l2.leafs.length; j++) {
				LeafBranch l3 = l2.leafs[j];
				if (l3 == null) {
					continue;
				}
				for (int k = 0; k < l3.data.length; k++) {
					Object d = l3.data[k];
					if (d != null) {
						System.out.printf("[%3d,%3d,%3d] %s\n", i,j,k,d);
						count++;
					}
				}
			}
		}
		System.out.printf("Total: %d elements\n", count);
	}

	private static class IndexBranch {
		private final LeafBranch[] leafs = new LeafBranch[64];
	}
	
	private static final class LeafBranch {
		public final Object[] data = new Object[64];

		public int put(int ix, Object d) {
			if (putIfEmptyOrSame(ix, d)) {
				return 1;
			}
			// try neighbour elements
			if (putIfEmptyOrSame(ix ^ 0x1, d) || putIfEmptyOrSame(ix ^ 0x2, d) || putIfEmptyOrSame(ix ^ 0x3, d)) {
				return 2;
			}
			return 0;
		}

		public boolean putIfEmptyOrSame(int ix, Object d) {
			if (data[ix] == null || data[ix].equals(d)) {
				data[ix] = d;
				return true;
			}
			return false;
		}

		/**
		 * <code>null</code> result indicates further checks make sense
		 * @return <code>this</code> if there's no entry at all, <code>null</code> if entry doesn't match, or entry value itself otherwise
		 */
		public Object get(int ix, Object o) {
			if (data[ix] == null) {
				return this;
			}
			if (data[ix].equals(o)) {
				return data[ix];
			}
			return null;
		}
	}

	//
	// 8 bits per level
//	int i1 = (hash >>> 24) & 0xFF, i2 = (hash >>> 16) & 0xFF , i3 = (hash >>> 8) & 0xFF, i4 = hash & 0xFF;
	//
	// 10, 8, 8 and 6 bits
//	final int i1 = (hash >>> 22) & 0x3FF, i2 = (hash >>> 14) & 0xFF , i3 = (hash >>> 6) & 0xFF, i4 = hash & 0x3F;
	//
	// 8, 6, 6, 6, 6
	// 10, 6, 6, 6, 4
	//
	// 6, 5, 5, 5 = 21 bit
//	hash = hash ^ (hash >>> 24); // incorporate upper byte we don't use into lower to value it
//final int i1 = (hash >>> 18) & 0x3F, i2 = (hash >>> 12) & 0x1F , i3 = (hash >>> 7) & 0x1F, i4 = (hash >>> 2) & 0x1F;
// 6, 5, 5
//hash = hash ^ (hash >>> 16);
//final int i1 = (hash >>> 10) & 0x3F, i2 = (hash >>> 5) & 0x1F , i3 = hash & 0x1F;
//
// 6, 6, 6
//final int i1 = (hash >>> 15) & 0x3F, i2 = (hash >>> 6) & 0x3F , i3 = hash & 0x3F;
//
// 8, 5, 5

}
