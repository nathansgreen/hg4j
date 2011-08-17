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

	private static class IndexBranch {
		private final LeafBranch[] leafs = new LeafBranch[64];
	}
	private static class LeafBranch {
		private final Object[] data = new Object[64];
	}

	private final int[] fixups = new int[] {0x1, 0x10, 0xA, 0xD, 0x1F }; // rehash attempts
	private final IndexBranch[] level2 = new IndexBranch[64];
	private int size = 0;

	public void put(T o) {
		int hash = o.hashCode();
		//
		// 8 bits per level
//		int i1 = (hash >>> 24) & 0xFF, i2 = (hash >>> 16) & 0xFF , i3 = (hash >>> 8) & 0xFF, i4 = hash & 0xFF;
		//
		// 10, 8, 8 and 6 bits
//		final int i1 = (hash >>> 22) & 0x3FF, i2 = (hash >>> 14) & 0xFF , i3 = (hash >>> 6) & 0xFF, i4 = hash & 0x3F;
		//
		// 8, 6, 6, 6, 6
		// 10, 6, 6, 6, 4
		//
		// 6, 5, 5, 5 = 21 bit
//		hash = hash ^ (hash >>> 24); // incorporate upper byte we don't use into lower to value it
//		final int i1 = (hash >>> 18) & 0x3F, i2 = (hash >>> 12) & 0x1F , i3 = (hash >>> 7) & 0x1F, i4 = (hash >>> 2) & 0x1F;
		// 6, 5, 5
//		hash = hash ^ (hash >>> 16);
//		final int i1 = (hash >>> 10) & 0x3F, i2 = (hash >>> 5) & 0x1F , i3 = hash & 0x1F;
		//
		// 6, 6, 6
		final int i1 = (hash >>> 15) & 0x3F, i2 = (hash >>> 6) & 0x3F , i3 = hash & 0x3F;
		LeafBranch l3 = leafBranchPut(i1, i2);
		if (l3.data[i3] == null) {
			l3.data[i3] = o;
			size++;
			return;
		}
		int neighbour = (i3+1) & 0x3F; 
		if (l3.data[neighbour] == null) {
			l3.data[neighbour] = o;
			size++;
			return;
		}
		int conflictCount = 0;
		for (int fixup : fixups) {
//			if (showConflicts) {
//				System.out.printf("(fixup: 0x%x) ", fixup);
//			}
			l3 = leafBranchPut(i1 ^ fixup, i2);
			conflictCount++;
			if (l3.data[i3] != null) {
//				if (showConflicts) {
//					System.out.printf("i1 failed ");
//				}
				l3 = leafBranchPut(i1, i2 ^ fixup);
				conflictCount++;
//				if (showConflicts) {
//					System.out.printf("i2 %s ",  (l3.data[i3] == null) ? "ok" : "failed");
//				}
//			} else {
//				if (showConflicts) {
//					System.out.printf("i1 ok");
//				}
			}
//			if (showConflicts) {
//				System.out.println();
//			}
			if (l3.data[i3] == null) {
				l3.data[i3] = o;
//				System.out.printf("Resolved conflict in %d steps (fixup 0x%X)\n", conflictCount, fixup);
				size++;
				return;
			}
		}
		throw new IllegalStateException(String.valueOf(o));
	}
	
	@SuppressWarnings("unchecked")
	public T get(T o) {
		int hash = o.hashCode();
		//hash = hash ^ (hash >>> 16);
		final int i1 = (hash >>> 15) & 0x3F, i2 = (hash >>> 6) & 0x3F , i3 = hash & 0x3F;
		//
		LeafBranch l3 = leafBranchGet(i1, i2);
		if (l3 == null || l3.data[i3] == null) {
			return null;
		}
		if (o.equals(l3.data[i3])) {
			return (T) l3.data[i3];
		}
		//
		int neighbour = (i3+1) & 0x3F; 
		if (o.equals(l3.data[neighbour])) {
			return (T) l3.data[neighbour];
		}

		//
		// resolve conflict
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

	public void dump() {
		int count = 0;
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
		System.out.printf("Total: %d elements", count);
	}
}
