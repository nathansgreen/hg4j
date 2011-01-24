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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.console;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.Changelog;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * WORK IN PROGRESS, DO NOT USE
 * hg in counterpart
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Incoming {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		// in fact, all we need from changelog is set of all nodeids. However, since ParentWalker reuses same Nodeids, it's not too expensive
		// to reuse it here, XXX although later this may need to be refactored
		final Changelog.ParentWalker pw = hgRepo.getChangelog().new ParentWalker();
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

	static final class RemoteBranch {
		public Nodeid head, root, p1, p2;

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (false == obj instanceof RemoteBranch) {
				return false;
			}
			RemoteBranch o = (RemoteBranch) obj;
			return head.equals(o.head) && root.equals(o.root) && (p1 == null && o.p1 == null || p1.equals(o.p1)) && (p2 == null && o.p2 == null || p2.equals(o.p2));
		}
	}

	private static void remoteBranches(Collection<Nodeid> unknownRemoteHeads, List<RemoteBranch> remoteBranches) {
		// discovery.findcommonincoming:
		// unknown = remote.branches(remote.heads); 
		// sent: cmd=branches&roots=d6d2a630f4a6d670c90a5ca909150f2b426ec88f+
		// received: d6d2a630f4a6d670c90a5ca909150f2b426ec88f dbd663faec1f0175619cf7668bddc6350548b8d6 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000
		// head, root, first parent, second parent
		//
		// TODO implement this with remote access
		//
		RemoteBranch rb = new RemoteBranch();
		rb.head = unknownRemoteHeads.iterator().next();
		rb.root = Nodeid.fromAscii("dbd663faec1f0175619cf7668bddc6350548b8d6".getBytes(), 0, 40);
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

}
