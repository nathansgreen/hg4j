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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

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

	private int readCache() {
		File branchheadsCache = new File(repo.getRepositoryRoot(), "branchheads.cache");
		int lastInCache = -1;
		if (!branchheadsCache.canRead()) {
			return lastInCache;
		}
		BufferedReader br = null;
		final Pattern spacePattern = Pattern.compile(" ");
		try {
			br = new BufferedReader(new FileReader(branchheadsCache));
			String line = br.readLine();
			if (line == null || line.trim().length() == 0) {
				return lastInCache;
			}
			String[] cacheIdentity = spacePattern.split(line.trim());
			lastInCache = Integer.parseInt(cacheIdentity[1]);
			// XXX may want to check if nodeid of cset from repo.getChangelog() of lastInCache index match cacheIdentity[0]
			//
			while ((line = br.readLine()) != null) {
				String[] elements = line.trim().split(" ");
				if (elements.length < 2) {
					// bad entry
					continue;
				}
				Nodeid[] branchHeads = new Nodeid[elements.length - 1];
				for (int i = 0; i < elements.length - 1; i++) {
					branchHeads[i] = Nodeid.fromAscii(elements[i]);
				}
				// I assume split returns substrings of the original string, hence copy of a branch name
				String branchName = new String(elements[elements.length-1]);
				BranchInfo bi = new BranchInfo(branchName, branchHeads);
				branches.put(branchName, bi);
			}
			return lastInCache;
		} catch (IOException ex) {
			ex.printStackTrace(); // XXX log error, but otherwise do nothing 
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException ex) {
					ex.printStackTrace(); // ignore
				}
			}
		}
		return -1; // deliberately not lastInCache, to avoid anything but -1 when 1st line was read and there's error is in lines 2..end
	}

	void collect(final ProgressSupport ps) {
		branches.clear();
		ps.start(1 + repo.getChangelog().getRevisionCount() * 2);
		//
		int lastCached = readCache();
		/*
		 * Next code was supposed to fill missing aspects of the BranchInfo, but is too slow
		 * 
		if (lastCached != -1 && lastCached <= repo.getChangelog().getLastRevision()) {
			LinkedList<BranchInfo> incompleteBranches = new LinkedList<HgBranches.BranchInfo>(branches.values());
			for (BranchInfo bi : incompleteBranches) {
				LinkedList<Nodeid> closedHeads = new LinkedList<Nodeid>();
				for (Nodeid h : bi.getHeads()) {
					if ("1".equals(repo.getChangelog().changeset(h).extras().get("close"))) {
						closedHeads.add(h);
					}
				}
				HashSet<Nodeid> earliest = new HashSet<Nodeid>(bi.getHeads());
				HashSet<Nodeid> visited = new HashSet<Nodeid>();
				ArrayList<Nodeid> parents = new ArrayList<Nodeid>(2);
				HashSet<Nodeid> candidate = new HashSet<Nodeid>();
				do {
					candidate.clear();
					for (Nodeid e : earliest) {
						parents.clear();
						if (pw.appendParentsOf(e, parents)) {
							// at least one parent
							Nodeid p1 = parents.get(0);
							if (p1 != null && !visited.contains(p1) && bi.getName().equals(repo.getChangelog().changeset(p1).branch())) {
								visited.add(p1);
								candidate.add(p1);
							}
							Nodeid p2 = parents.size() > 1 ? parents.get(1) : null;
							if (p2 != null && !visited.contains(p2) && bi.getName().equals(repo.getChangelog().changeset(p2).branch())) {
								visited.add(p2);
								candidate.add(p2);
							}
						}
					}
					if (!candidate.isEmpty()) {
						earliest.clear();
						earliest.addAll(candidate);
					}
				} while (!candidate.isEmpty());
				// earliest can't be empty, we've started with non-empty heads.
				Nodeid first = null;
				if (earliest.size() == 1) {
					first = earliest.iterator().next();
				} else {
					int earliestRevNum = Integer.MAX_VALUE;
					for (Nodeid e : earliest) {
						int x = repo.getChangelog().getLocalRevision(e);
						if (x < earliestRevNum) {
							earliestRevNum = x;
							first = e;
						}
					}
				}
				assert first != null;
				System.out.println("Updated branch " + bi.getName());
				branches.put(bi.getName(), new BranchInfo(bi.getName(), first, bi.getHeads().toArray(new Nodeid[0]), closedHeads.size() == bi.getHeads().size()));
			}
		}
		*/
		if (lastCached != repo.getChangelog().getLastRevision()) {
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
			repo.getChangelog().range(lastCached == -1 ? 0 : lastCached+1, HgRepository.TIP, insp);
			for (String bn : branchLastSeen.keySet()) {
				branchHeads.get(bn).add(branchLastSeen.get(bn));
			}
			for (String bn : branchStart.keySet()) {
				BranchInfo bi = branches.get(bn);
				if (bi != null) {
					// although heads from cache shall not intersect with heads after lastCached,
					// use of LHS doesn't hurt (and makes sense e.g. if cache is not completely correct in my tests) 
					LinkedHashSet<Nodeid> heads = new LinkedHashSet<Nodeid>(bi.getHeads());
					for (Nodeid oldHead : bi.getHeads()) {
						// XXX perhaps, need pw.canReach(Nodeid from, Collection<Nodeid> to)
						List<Nodeid> newChildren = pw.childrenOf(Collections.singletonList(oldHead));
						if (!newChildren.isEmpty()) {
							// likely not a head any longer,
							// check if any new head can be reached from old one, and, if yes,
							// do not consider that old head as head.
							for (Nodeid newHead : branchHeads.get(bn)) {
								if (newChildren.contains(newHead)) {
									heads.remove(oldHead);
									break;
								}
							}
						} // else - oldHead still head for the branch
					}
					heads.addAll(branchHeads.get(bn));
					bi = new BranchInfo(bn, bi.getStart(), heads.toArray(new Nodeid[0]), bi.isClosed() && closedBranches.contains(bn));
				} else {
					Nodeid[] heads = branchHeads.get(bn).toArray(new Nodeid[0]);
					bi = new BranchInfo(bn, branchStart.get(bn), heads, closedBranches.contains(bn));
				}
				branches.put(bn, bi);
			}
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

		// XXX in fact, few but not all branchHeads might be closed, and isClosed for whole branch is not
		// possible to determine.
		BranchInfo(String branchName, Nodeid first, Nodeid[] branchHeads, boolean isClosed) {
			name = branchName;
			start = first;
			heads = Collections.unmodifiableList(new ArrayList<Nodeid>(Arrays.asList(branchHeads)));
			closed = isClosed;
		}
		
		// incomplete branch, there's not enough information at the time of creation. shall be replaced with
		// proper BI in #collect()
		BranchInfo(String branchName, Nodeid[] branchHeads) {
			this(branchName, Nodeid.NULL, branchHeads, false);
		}

		public String getName() {
			return name;
		}
		/*public*/ boolean isClosed() {
			return closed;
		}
		public List<Nodeid> getHeads() {
			return heads;
		}
//		public Nodeid getTip() {
//		}
		/*public*/ Nodeid getStart() {
			// first node where branch appears
			return start;
		}
	}
}
