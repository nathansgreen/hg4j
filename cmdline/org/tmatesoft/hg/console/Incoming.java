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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
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
		if (Boolean.TRUE.booleanValue()) {
			new SequenceConstructor().test();
			return;
		}
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		// in fact, all we need from changelog is set of all nodeids. However, since ParentWalker reuses same Nodeids, it's not too expensive
		// to reuse it here, XXX although later this may need to be refactored
		final HgChangelog.ParentWalker pw = hgRepo.getChangelog().new ParentWalker();
		pw.init();
		//
		HashSet<Nodeid> base = new HashSet<Nodeid>();
		HashSet<Nodeid> unknownRemoteHeads = new HashSet<Nodeid>();
		// imagine empty repository - any nodeid from remote heads would be unknown
		unknownRemoteHeads.add(Nodeid.fromAscii("382cfe9463db0484a14136e4b38407419525f0c0".getBytes(), 0, 40));
		//
		LinkedList<RemoteBranch> remoteBranches = new LinkedList<RemoteBranch>();
		remoteBranches(unknownRemoteHeads, remoteBranches);
		//
		HashSet<Nodeid> visited = new HashSet<Nodeid>();
		HashSet<RemoteBranch> processed = new HashSet<RemoteBranch>();
		LinkedList<Nodeid[]> toScan = new LinkedList<Nodeid[]>();
		LinkedHashSet<Nodeid> toFetch = new LinkedHashSet<Nodeid>();
		// next one seems to track heads we've asked (or plan to ask) remote.branches for
		HashSet<Nodeid> unknownHeads /*req*/ = new HashSet<Nodeid>(unknownRemoteHeads);
		while (!remoteBranches.isEmpty()) {
			LinkedList<Nodeid> toQueryRemote = new LinkedList<Nodeid>();
			while (!remoteBranches.isEmpty()) {
				RemoteBranch next = remoteBranches.removeFirst();
				if (visited.contains(next.head) || processed.contains(next)) {
					continue;
				}
				if (Nodeid.NULL.equals(next.head)) {
					// it's discovery.py that expects next.head to be nullid here, I can't imagine how this may happen, hence this exception
					throw new IllegalStateException("I wonder if null if may ever get here with remote branches");
				} else if (pw.knownNode(next.root)) {
					// root of the remote change is known locally, analyze to find exact missing changesets
					toScan.addLast(new Nodeid[] { next.head, next.root });
					processed.add(next);
				} else {
					if (!visited.contains(next.root) && !toFetch.contains(next.root)) {
						// if parents are locally known, this is new branch (sequence of changes) (sequence sprang out of known parents) 
						if ((next.p1 == null || pw.knownNode(next.p1)) && (next.p2 == null || pw.knownNode(next.p2))) {
							toFetch.add(next.root);
						}
						// XXX perhaps, may combine this parent processing below (I don't understand what this code is exactly about)
						if (pw.knownNode(next.p1)) {
							base.add(next.p1);
						}
						if (pw.knownNode(next.p2)) {
							base.add(next.p2);
						}
					}
					if (next.p1 != null && !pw.knownNode(next.p1) && !unknownHeads.contains(next.p1)) {
						toQueryRemote.add(next.p1);
						unknownHeads.add(next.p1);
					}
					if (next.p2 != null && !pw.knownNode(next.p2) && !unknownHeads.contains(next.p2)) {
						toQueryRemote.add(next.p2);
						unknownHeads.add(next.p2);
					}
				}
				visited.add(next.head);
			}
			if (!toQueryRemote.isEmpty()) {
				// discovery.py in fact does this in batches of 10 revisions a time.
				// however, this slicing may be done in remoteBranches call instead (if needed)
				remoteBranches(toQueryRemote, remoteBranches);
			}
		}
		while (!toScan.isEmpty()) {
			Nodeid[] head_root = toScan.removeFirst();
			List<Nodeid> nodesBetween = remoteBetween(head_root[0], head_root[1], new LinkedList<Nodeid>());
			nodesBetween.add(head_root[1]);
			int x = 1;
			Nodeid p = head_root[0];
			for (Nodeid i : nodesBetween) {
				System.out.println("narrowing " + x + ":" + nodesBetween.size() + " " + i.shortNotation());
				if (pw.knownNode(i)) {
					if (x <= 2) {
						toFetch.add(p);
						base.add(i);
					} else {
						// XXX original discovery.py collects new elements to scan separately
						// likely to "batch" calls to server
						System.out.println("narrowed branch search to " + p.shortNotation() + ":" + i.shortNotation());
						toScan.addLast(new Nodeid[] { p, i });
					}
					break;
				}
				x = x << 1;
				p = i;
			}
		}
		for (Nodeid n : toFetch) {
			if (pw.knownNode(n)) {
				System.out.println("Erroneous to fetch:" + n);
			} else {
				System.out.println(n);
			}
		}
		
	}


	private static void remoteBranches(Collection<Nodeid> unknownRemoteHeads, List<RemoteBranch> remoteBranches) {
		//
		// TODO implement this with remote access
		//
		RemoteBranch rb = new RemoteBranch(unknownRemoteHeads.iterator().next(), Nodeid.fromAscii("dbd663faec1f0175619cf7668bddc6350548b8d6"), NULL, NULL);
		remoteBranches.add(rb);
	}

	private static List<Nodeid> remoteBetween(Nodeid nodeid1, Nodeid nodeid2, List<Nodeid> list) {
		// sent: cmd=between&pairs=d6d2a630f4a6d670c90a5ca909150f2b426ec88f-dbd663faec1f0175619cf7668bddc6350548b8d6
		// received: a78c980749e3ccebb47138b547e9b644a22797a9 286d221f6c28cbfce25ea314e1f46a23b7f979d3 fc265ddeab262ff5c34b4cf4e2522d8d41f1f05b a3576694a4d1edaa681cab15b89d6b556b02aff4
		// 1st, 2nd, fourth and eights of total 8 changes between rev9 and rev0
		//
		//
		//           a78c980749e3ccebb47138b547e9b644a22797a9 286d221f6c28cbfce25ea314e1f46a23b7f979d3 fc265ddeab262ff5c34b4cf4e2522d8d41f1f05b a3576694a4d1edaa681cab15b89d6b556b02aff4
		//d6d2a630f4a6d670c90a5ca909150f2b426ec88f a78c980749e3ccebb47138b547e9b644a22797a9 5abe5af181bd6a6d3e94c378376c901f0f80da50 08db726a0fb7914ac9d27ba26dc8bbf6385a0554

		// TODO implement with remote access
		String response = null;
		if (nodeid1.equals(Nodeid.fromAscii("382cfe9463db0484a14136e4b38407419525f0c0".getBytes(), 0, 40)) && nodeid2.equals(Nodeid.fromAscii("dbd663faec1f0175619cf7668bddc6350548b8d6".getBytes(), 0, 40))) {
			response = "d6d2a630f4a6d670c90a5ca909150f2b426ec88f a78c980749e3ccebb47138b547e9b644a22797a9 5abe5af181bd6a6d3e94c378376c901f0f80da50 08db726a0fb7914ac9d27ba26dc8bbf6385a0554";
		} else if (nodeid1.equals(Nodeid.fromAscii("a78c980749e3ccebb47138b547e9b644a22797a9".getBytes(), 0, 40)) && nodeid2.equals(Nodeid.fromAscii("5abe5af181bd6a6d3e94c378376c901f0f80da50".getBytes(), 0, 40))) {
			response = "286d221f6c28cbfce25ea314e1f46a23b7f979d3";
		}
		if (response == null) {
			throw HgRepository.notImplemented();
		}
		for (String s : response.split(" ")) {
			list.add(Nodeid.fromAscii(s.getBytes(), 0, 40));
		}
		return list;
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
			int root = 0, head = 1000;
			int[] data = between(root, head); // max number of elements to recover is 2**(1+data.length)-1, need room for 
			// as much elements, hence 2**(data.length+1). In worst case, when there are onlu 2**data.length + 1 missing element,
			// almost half of the finalSequence would be empty
			int[] finalSequence = new int[1 << (data.length+1) >>> 5]; // div 32 - total bits to integers
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
					if (data.length > 2) { 
						for (int x : data) {
							if (!queried.contains(x) && head - x > 1) { /*queries for neighboring elements is senseless*/
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
