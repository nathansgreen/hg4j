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
package org.tmatesoft.hg.console;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.tmatesoft.hg.core.HgIncomingCommand;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;


/**
 * <em>hg incoming</em> counterpart
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Incoming {

	public static void main(String[] args) throws Exception {
		if (Boolean.FALSE.booleanValue()) {
			new SequenceConstructor().test();
			return;
		}
		Options cmdLineOpts = Options.parse(args);
		HgRepoFacade hgRepo = new HgRepoFacade();
		if (!hgRepo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getRepository().getLocation());
			return;
		}
		HgRemoteRepository hgRemote = new HgLookup().detectRemote(cmdLineOpts.getSingle(""), hgRepo.getRepository());
		if (hgRemote.isInvalid()) {
			System.err.printf("Remote repository %s is not valid", hgRemote.getLocation());
			return;
		}
		HgIncomingCommand cmd = hgRepo.createIncomingCommand();
		cmd.against(hgRemote);
		//
		List<Nodeid> missing = cmd.executeLite(null);
		Collections.reverse(missing); // useful to test output, from newer to older
		Outgoing.dump("Nodes to fetch:", missing);
		System.out.printf("Total: %d\n\n", missing.size());
		//
		// Complete
		final ChangesetDumpHandler h = new ChangesetDumpHandler(hgRepo.getRepository());
		h.complete(false); // this option looks up index of parent revision, done via repo.changelog (which doesn't have any of these new revisions)
		// this can be fixed by tracking all nodeid->revision idx inside ChangesetDumpHandler, and refer to repo.changelog only when that mapping didn't work
		h.verbose(cmdLineOpts.getBoolean("-v", "--verbose"));
		cmd.executeFull(h);
	}
	

	/*
	 * This is for investigation purposes only
	 */
	private static class SequenceConstructor {

		private int[] between(int root, int head) {
			if (head <= (root+1)) {
				return new int[0];
			}
			System.out.printf("[%d, %d]\t\t", root, head);
			int size = 1 + (int) Math.floor(Math.log(head-root - 1) / Math.log(2));
			int[] rv = new int[size];
			for (int v = 1, i = 0; i < rv.length; i++) {
				rv[i] = root + v;
				v = v << 1;
			}
			System.out.println(Arrays.toString(rv));
			return rv;
		}

		public void test() {
			int root = 0, head = 126;
			int[] data = between(root, head); // max number of elements to recover is 2**data.length-1, when head is exactly
			// 2**data.length element of the branch.  
			// In such case, total number of elements in the branch (including head and root, would be 2**data.length+1
			int[] finalSequence = new int[1 + (1 << data.length >>> 5)]; // div 32 - total bits to integers, +1 for possible modulus
			int exactNumberOfElements = -1; // exact number of meaningful bits in finalSequence
			LinkedHashMap<Integer, int[]> datas = new LinkedHashMap<Integer, int[]>();
			datas.put(root, data);
			int totalQueries = 1;
			HashSet<Integer> queried = new HashSet<Integer>();
			int[] checkSequence = null;
			while(!datas.isEmpty()) {
				LinkedList<int[]> toQuery = new LinkedList<int[]>();
				do {
					Iterator<Entry<Integer, int[]>> it = datas.entrySet().iterator();
					Entry<Integer, int[]> next = it.next();
					int r = next.getKey();
					data = next.getValue();
					it.remove();
					populate(r, head, data, finalSequence);
					if (checkSequence != null) {
						boolean match = true;
//						System.out.println("Try to match:");
						for (int i = 0; i < checkSequence.length; i++) {
//							System.out.println(i);
//							System.out.println("control:" + toBinaryString(checkSequence[i], ' '));
//							System.out.println("present:" + toBinaryString(finalSequence[i], ' '));
							if (checkSequence[i] != finalSequence[i]) {
								match = false;
							} else {
								match &= true;
							}
						}
						System.out.println(match ? "Match, on query:" + totalQueries : "Didn't match");
					}
					if (data.length > 1) { 
						/*queries for elements next to head is senseless, hence data.length check above and head-x below*/
						for (int x : data) {
							if (!queried.contains(x) && head - x > 1) { 
								toQuery.add(new int[] {x, head});
							}
						}
					}
				} while (!datas.isEmpty()) ;
				if (!toQuery.isEmpty()) {
					System.out.println();
					totalQueries++;
				}
				Collections.sort(toQuery, new Comparator<int[]>() {

					public int compare(int[] o1, int[] o2) {
						return o1[0] < o2[0] ? -1 : (o1[0] == o2[0] ? 0 : 1);
					}
				});
				for (int[] x : toQuery) {
					if (!queried.contains(x[0])) {
						queried.add(x[0]);
						data = between(x[0], x[1]);
						if (exactNumberOfElements == -1 && data.length == 1) {
							exactNumberOfElements = x[0] + 1;
							System.out.printf("On query %d found out exact number of missing elements: %d\n", totalQueries, exactNumberOfElements);
							// get a bit sequence of exactNumberOfElements, 0111..110
							// to 'and' it with finalSequence later
							int totalInts = (exactNumberOfElements + 2 /*heading and tailing zero bits*/) >>> 5;
							int trailingBits = (exactNumberOfElements + 2) & 0x1f;
							if (trailingBits != 0) {
								totalInts++;
							}
							checkSequence = new int[totalInts];
							Arrays.fill(checkSequence, 0xffffffff);
							checkSequence[0] &= 0x7FFFFFFF;
							if (trailingBits == 0) {
								checkSequence[totalInts-1] &= 0xFFFFFFFE;
							} else if (trailingBits == 1) {
								checkSequence[totalInts-1] = 0;
							} else {
								// trailingBits include heading and trailing zero bits
								int mask = 0x80000000 >> trailingBits-2; // with sign!
								checkSequence[totalInts - 1] &= mask;
							}
							for (int e : checkSequence) {
								System.out.print(toBinaryString(e, ' '));
							}
							System.out.println();
						}
						datas.put(x[0], data);
					}
				}
			}

			System.out.println("Total queries:" + totalQueries);
			for (int x : finalSequence) {
				System.out.print(toBinaryString(x, ' '));
			}
		}
		
		private void populate(int root, int head, int[] data, int[] finalSequence) {
			for (int i = 1, x = 0; root+i < head; i = i << 1, x++) {
				int value = data[x];
				int value_check = root+i;
				if (value != value_check) {
					throw new IllegalStateException();
				}
				int wordIx = (root + i) >>> 5;
				int bitIx = (root + i) & 0x1f;
				finalSequence[wordIx] |= 1 << (31-bitIx);
			}
		}
		
		private static String toBinaryString(int x, char byteSeparator) {
			StringBuilder sb = new StringBuilder(4*8+4);
			sb.append(toBinaryString((byte) (x >>> 24)));
			sb.append(byteSeparator);
			sb.append(toBinaryString((byte) ((x & 0x00ff0000) >>> 16)));
			sb.append(byteSeparator);
			sb.append(toBinaryString((byte) ((x & 0x00ff00) >>> 8)));
			sb.append(byteSeparator);
			sb.append(toBinaryString((byte) (x & 0x00ff)));
			sb.append(byteSeparator);
			return sb.toString();
		}

		private static String toBinaryString(byte b) {
			final String nibbles = "0000000100100011010001010110011110001001101010111100110111101111";
			assert nibbles.length() == 16*4;
			int x1 = (b >>> 4) & 0x0f, x2 = b & 0x0f;
			x1 *= 4; x2 *= 4; // 4 characters per nibble
			return nibbles.substring(x1, x1+4).concat(nibbles.substring(x2, x2+4));
		}
	}
}
