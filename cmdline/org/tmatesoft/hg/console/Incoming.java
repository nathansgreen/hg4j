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

import static org.tmatesoft.hg.core.Nodeid.NULL;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRemoteRepository.RemoteBranch;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * WORK IN PROGRESS, DO NOT USE
 * hg incoming counterpart
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
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		String key = "svnkit";
		ConfigFile cfg = new Internals().newConfigFile();
		cfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
		String server = cfg.getSection("paths").get(key);
		if (server == null) {
			throw new HgException(String.format("Can't find server %s specification in the config", key));
		}
		HgRemoteRepository hgRemote = new HgLookup().detect(new URL(server));
		//
		// in fact, all we need from changelog is set of all nodeids. However, since ParentWalker reuses same Nodeids, it's not too expensive
		// to reuse it here, XXX although later this may need to be refactored
		final HgChangelog.ParentWalker pw = hgRepo.getChangelog().new ParentWalker();
		pw.init();
		//
		List<RemoteBranch> missingBranches = calculateMissingBranches(hgRepo, hgRemote);
		LinkedList<Nodeid> missing = new LinkedList<Nodeid>();
		for (RemoteBranch rb : missingBranches) {
			List<Nodeid> completeBranch = completeBranch(hgRemote, rb);
			// FIXME ensure topologically sorted result
			missing.addAll(completeBranch);
		}
		// Collections.reverse(missing); // useful to test output, from newer to older
		for (Nodeid n : missing) {
			if (pw.knownNode(n)) {
				System.out.println("Erroneous to fetch:" + n);
			} else {
				System.out.println(n);
			}
		}
	}
	
	private static List<RemoteBranch> calculateMissingBranches(HgRepository hgRepo, HgRemoteRepository hgRemote) {
		// FIXME implement
		//
		// sample 0..52
		Nodeid head = Nodeid.fromAscii("30bd389788464287cee22ccff54c330a4b715de5");
		Nodeid root = Nodeid.fromAscii("dbd663faec1f0175619cf7668bddc6350548b8d6");
		Nodeid p1 = NULL, p2 = NULL;
		RemoteBranch fake = new RemoteBranch(head, root, p1, p2);
		return Collections.singletonList(fake);
	}

	// RemoteBranch not necessarily a 'true' remote branch. I use this structure to keep root, head, and root's parents, any
	// of them can be known locally, parent might be only one (when I split 'true' RemoteBranch in between because of locally known node 
	private static List<Nodeid> completeBranch(HgRemoteRepository hgRemote, RemoteBranch rb) throws HgException {
		class DataEntry {
			public final Nodeid queryHead;
			public final int headIndex;
			public List<Nodeid> entries;

			public DataEntry(Nodeid head, int index, List<Nodeid> data) {
				queryHead = head;
				headIndex = index;
				entries = data;
			}
		};

		List<Nodeid> initial = hgRemote.between(rb.head, rb.root);
		Nodeid[] result = new Nodeid[1 << initial.size()];
		result[0] = rb.head;
		int rootIndex = -1; // index in the result, where to place branche's root.
		LinkedList<DataEntry> datas = new LinkedList<DataEntry>();
		// DataEntry in datas has entries list filled with 'between' data, whereas 
		// DataEntry in toQuery keeps only nodeid and its index, with entries to be initialized before 
		// moving to datas. 
		LinkedList<DataEntry> toQuery = new LinkedList<DataEntry>();
		//
		datas.add(new DataEntry(rb.head, 0, initial));
		int totalQueries = 1;
		HashSet<Nodeid> queried = new HashSet<Nodeid>();
		while(!datas.isEmpty()) {
			do {
				DataEntry de = datas.removeFirst();
				// populate result with discovered elements between de.qiueryRoot and branch's head
				for (int i = 1, j = 0; j < de.entries.size(); i = i << 1, j++) {
					int idx = de.headIndex + i;
					result[idx] = de.entries.get(j);
				}
				// form next query entries from new unknown elements
				if (de.entries.size() > 1) {
					/* when entries has only one element, it means de.queryRoot was at head-2 position, and thus
					 * no new information can be obtained. E.g. when it's 2, it might be case of [0..4] query with
					 * [1,2] result, and we need one more query to get element 3.   
					 */
					for (int i =1, j = 0; j < de.entries.size(); i = i<<1, j++) {
						int idx = de.headIndex + i;
						Nodeid x = de.entries.get(j);
						if (!queried.contains(x) && (rootIndex == -1 || rootIndex - de.headIndex > 1)) {
							/*queries for elements right before head is senseless, but unless we know head's index, do it anyway*/
							toQuery.add(new DataEntry(x, idx, null));
						}
					}
				}
			} while (!datas.isEmpty());
			if (!toQuery.isEmpty()) {
				totalQueries++;
			}
			for (DataEntry de : toQuery) {
				if (!queried.contains(de.queryHead)) {
					queried.add(de.queryHead);
					List<Nodeid> between = hgRemote.between(de.queryHead, rb.root);
					if (rootIndex == -1 && between.size() == 1) {
						// returned sequence of length 1 means we used element from [head-2] as root
						int numberOfElementsExcludingRootAndHead = de.headIndex + 1;
						rootIndex = numberOfElementsExcludingRootAndHead + 1;
						System.out.printf("On query %d found out exact number of missing elements: %d\n", totalQueries, numberOfElementsExcludingRootAndHead);
					}
					de.entries = between;
					datas.add(de); // queue up to record result and construct further requests
				}
			}
			toQuery.clear();
		}
		if (rootIndex == -1) {
			throw new HgBadStateException("Shall not happen, provided between output is correct"); // FIXME
		}
		result[rootIndex] = rb.root;
		boolean resultOk = true;
		LinkedList<Nodeid> fromRootToHead = new LinkedList<Nodeid>();
		for (int i = 0; i <= rootIndex; i++) {
			Nodeid n = result[i];
			if (n == null) {
				System.out.printf("ERROR: element %d wasn't found\n",i);
				resultOk = false;
			}
			fromRootToHead.addFirst(n); // reverse order
		}
		if (!resultOk) {
			throw new HgBadStateException("See console for details"); // FIXME
		}
		return fromRootToHead;
	}

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
