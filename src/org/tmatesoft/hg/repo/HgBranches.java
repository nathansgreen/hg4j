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
package org.tmatesoft.hg.repo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgBranches {
	
	private final Map<String, BranchInfo> branches = new TreeMap<String, BranchInfo>();
	private final HgRepository repo;
	
	HgBranches(HgRepository hgRepo) {
		repo = hgRepo;
	}

	void collect(final ProgressSupport ps) {
		ps.start(1 + repo.getChangelog().getRevisionCount() * 2);
		final HgChangelog.ParentWalker pw = repo.getChangelog().new ParentWalker();
		pw.init();
		ps.worked(repo.getChangelog().getRevisionCount());
		final HashMap<String, Nodeid> branchStart = new HashMap<String, Nodeid>();
		final HashMap<String, Nodeid> branchLastSeen = new HashMap<String, Nodeid>();
		final HashMap<String, List<Nodeid>> branchHeads = new HashMap<String, List<Nodeid>>();
		final HashSet<String> closedBranches = new HashSet<String>();
		HgChangelog.Inspector insp = new HgChangelog.Inspector() {
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				String branchName = cset.branch();
				if (!branchStart.containsKey(branchName)) {
					branchStart.put(branchName, nodeid);
					branchHeads.put(branchName, new LinkedList<Nodeid>());
				}
				branchLastSeen.remove(branchName);
				if ("1".equals(cset.extras().get("close"))) {
					branchHeads.get(branchName).add(nodeid); // XXX what if it still has children?
					closedBranches.add(branchName);
				} else {
					if (pw.hasChildren(nodeid)) {
						// children may be in another branch
						// and unless we later came across another element from this branch,
						// we need to record all these as valid heads
						// XXX what about next case: head1 with children in different branch, and head2 without children
						// head1 would get lost
						branchLastSeen.put(branchName, nodeid);
					} else {
						// no more children known for this node, it's (one of the) head of the branch
						branchHeads.get(branchName).add(nodeid);
					}
				}
				ps.worked(1);
			}
		}; 
		repo.getChangelog().all(insp);
		for (String bn : branchLastSeen.keySet()) {
			branchHeads.get(bn).add(branchLastSeen.get(bn));
		}
		for (String bn : branchStart.keySet()) {
			Nodeid[] heads = branchHeads.get(bn).toArray(new Nodeid[0]);
			BranchInfo bi = new BranchInfo(bn, branchStart.get(bn), heads, closedBranches.contains(bn));
			branches.put(bn, bi);
		}
		ps.done();
	}

	public List<BranchInfo> getAllBranches() {
		return new LinkedList<BranchInfo>(branches.values());
				
	}

	public BranchInfo getBranch(String name) {
		return branches.get(name);
	}
	
	public static class BranchInfo {
		private final String name;
		private final List<Nodeid> heads;
		private final boolean closed;
		private final Nodeid start;

		BranchInfo(String branchName, Nodeid first, Nodeid[] branchHeads, boolean isClosed) {
			name = branchName;
			start = first;
			heads = Collections.unmodifiableList(new ArrayList<Nodeid>(Arrays.asList(branchHeads)));
			closed = isClosed;
		}

		public String getName() {
			return name;
		}
		public boolean isClosed() {
			return closed;
		}
		public List<Nodeid> getHeads() {
			return heads;
		}
//		public Nodeid getTip() {
//		}
		public Nodeid getStart() {
			// first node where branch appears
			return start;
		}
	}
}
