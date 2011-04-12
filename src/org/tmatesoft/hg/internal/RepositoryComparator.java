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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.core.Nodeid.NULL;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRemoteRepository.Range;
import org.tmatesoft.hg.repo.HgRemoteRepository.RemoteBranch;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RepositoryComparator {

	private final HgChangelog.ParentWalker localRepo;
	private final HgRemoteRepository remoteRepo;
	private List<Nodeid> common;

	public RepositoryComparator(HgChangelog.ParentWalker pwLocal, HgRemoteRepository hgRemote) {
		localRepo = pwLocal;
		remoteRepo = hgRemote;
	}
	
	public RepositoryComparator compare(Object context) throws HgException, CancelledException {
		ProgressSupport progressSupport = ProgressSupport.Factory.get(context);
		CancelSupport cancelSupport = CancelSupport.Factory.get(context);
		cancelSupport.checkCancelled();
		progressSupport.start(10);
		common = Collections.unmodifiableList(findCommonWithRemote());
		progressSupport.done();
		return this;
	}
	
	public List<Nodeid> getCommon() {
		if (common == null) {
			throw new HgBadStateException("Call #compare(Object) first");
		}
		return common;
	}

	private List<Nodeid> findCommonWithRemote() throws HgException {
		List<Nodeid> remoteHeads = remoteRepo.heads();
		LinkedList<Nodeid> resultCommon = new LinkedList<Nodeid>(); // these remotes are known in local
		LinkedList<Nodeid> toQuery = new LinkedList<Nodeid>(); // these need further queries to find common
		for (Nodeid rh : remoteHeads) {
			if (localRepo.knownNode(rh)) {
				resultCommon.add(rh);
			} else {
				toQuery.add(rh);
			}
		}
		if (toQuery.isEmpty()) {
			return resultCommon; 
		}
		LinkedList<RemoteBranch> checkUp2Head = new LinkedList<RemoteBranch>(); // branch.root and branch.head are of interest only.
		// these are branches with unknown head but known root, which might not be the last common known,
		// i.e. there might be children changeset that are also available at remote, [..?..common-head..remote-head] - need to 
		// scroll up to common head.
		while (!toQuery.isEmpty()) {
			List<RemoteBranch> remoteBranches = remoteRepo.branches(toQuery);	//head, root, first parent, second parent
			toQuery.clear();
			while(!remoteBranches.isEmpty()) {
				RemoteBranch rb = remoteBranches.remove(0);
				// I assume branches remote call gives branches with head equal to what I pass there, i.e.
				// that I don't need to check whether rb.head is unknown.
				if (localRepo.knownNode(rb.root)) {
					// we known branch start, common head is somewhere in its descendants line  
					checkUp2Head.add(rb);
				} else {
					// dig deeper in the history, if necessary
					if (!NULL.equals(rb.p1) && !localRepo.knownNode(rb.p1)) {
						toQuery.add(rb.p1);
					}
					if (!NULL.equals(rb.p2) && !localRepo.knownNode(rb.p2)) {
						toQuery.add(rb.p2);
					}
				}
			}
		}
		// can't check nodes between checkUp2Head element and local heads, remote might have distinct descendants sequence
		for (RemoteBranch rb : checkUp2Head) {
			// rb.root is known locally
			List<Nodeid> remoteRevisions = remoteRepo.between(rb.head, rb.root);
			if (remoteRevisions.isEmpty()) {
				// head is immediate child
				resultCommon.add(rb.root);
			} else {
				// between gives result from head to root, I'd like to go in reverse direction
				Collections.reverse(remoteRevisions);
				Nodeid root = rb.root;
				while(!remoteRevisions.isEmpty()) {
					Nodeid n = remoteRevisions.remove(0);
					if (localRepo.knownNode(n)) {
						if (remoteRevisions.isEmpty()) {
							// this is the last known node before an unknown
							resultCommon.add(n);
							break;
						}
						if (remoteRevisions.size() == 1) {
							// there's only one left between known n and unknown head
							// this check is to save extra between query, not really essential
							Nodeid last = remoteRevisions.remove(0);
							resultCommon.add(localRepo.knownNode(last) ? last : n);
							break;
						}
						// might get handy for next between query, to narrow search down
						root = n;
					} else {
						remoteRevisions = remoteRepo.between(n, root);
						Collections.reverse(remoteRevisions);
						if (remoteRevisions.isEmpty()) {
							resultCommon.add(root);
						}
					}
				}
			}
		}
		// TODO ensure unique elements in the list
		return resultCommon;
	}

	// somewhat similar to Outgoing.findCommonWithRemote() 
	public List<BranchChain> calculateMissingBranches() throws HgException {
		List<Nodeid> remoteHeads = remoteRepo.heads();
		LinkedList<Nodeid> common = new LinkedList<Nodeid>(); // these remotes are known in local
		LinkedList<Nodeid> toQuery = new LinkedList<Nodeid>(); // these need further queries to find common
		for (Nodeid rh : remoteHeads) {
			if (localRepo.knownNode(rh)) {
				common.add(rh);
			} else {
				toQuery.add(rh);
			}
		}
		if (toQuery.isEmpty()) {
			return Collections.emptyList(); // no incoming changes
		}
		LinkedList<BranchChain> branches2load = new LinkedList<BranchChain>(); // return value
		// detailed comments are in Outgoing.findCommonWithRemote
		LinkedList<RemoteBranch> checkUp2Head = new LinkedList<RemoteBranch>();
		// records relation between branch head and its parent branch, if any
		HashMap<Nodeid, BranchChain> head2chain = new HashMap<Nodeid, BranchChain>();
		while (!toQuery.isEmpty()) {
			List<RemoteBranch> remoteBranches = remoteRepo.branches(toQuery);	//head, root, first parent, second parent
			toQuery.clear();
			while(!remoteBranches.isEmpty()) {
				RemoteBranch rb = remoteBranches.remove(0);
				BranchChain chainElement = head2chain.get(rb.head);
				if (chainElement == null) {
					chainElement = new BranchChain(rb.head);
					// record this unknown branch to download later
					branches2load.add(chainElement);
				}
				if (localRepo.knownNode(rb.root)) {
					// we known branch start, common head is somewhere in its descendants line  
					checkUp2Head.add(rb);
				} else {
					chainElement.branchRoot = rb.root;
					// dig deeper in the history, if necessary
					if (!NULL.equals(rb.p1) && !localRepo.knownNode(rb.p1)) {
						toQuery.add(rb.p1);
						head2chain.put(rb.p1, chainElement.p1 = new BranchChain(rb.p1));
					}
					if (!NULL.equals(rb.p2) && !localRepo.knownNode(rb.p2)) {
						toQuery.add(rb.p2);
						head2chain.put(rb.p2, chainElement.p2 = new BranchChain(rb.p2));
					}
				}
			}
		}
		for (RemoteBranch rb : checkUp2Head) {
			Nodeid h = rb.head;
			Nodeid r = rb.root;
			int watchdog = 1000;
			BranchChain bc = head2chain.get(h);
			assert bc != null;
			// if we know branch root locally, there could be no parent branch chain elements.
			assert bc.p1 == null;
			assert bc.p2 == null;
			do {
				List<Nodeid> between = remoteRepo.between(h, r);
				if (between.isEmpty()) {
					bc.branchRoot = r;
					break;
				} else {
					Collections.reverse(between);
					for (Nodeid n : between) {
						if (localRepo.knownNode(n)) {
							r = n;
						} else {
							h = n;
							break;
						}
					}
					Nodeid lastInBetween = between.get(between.size() - 1);
					if (r.equals(lastInBetween)) {
						bc.branchRoot = r;
						break;
					} else if (h.equals(lastInBetween)) { // the only chance for current head pointer to point to the sequence tail
						// is when r is second from the between list end (iow, head,1,[2],4,8...,root)
						bc.branchRoot = r;
						break;
					}
				}
			} while(--watchdog > 0);
			if (watchdog == 0) {
				throw new HgBadStateException(String.format("Can't narrow down branch [%s, %s]", rb.head.shortNotation(), rb.root.shortNotation()));
			}
		}
		return branches2load;
	}

	public static final class BranchChain {
		// when we construct a chain, we know head which is missing locally, hence init it right away.
		// as for root (branch unknown start), we might happen to have one locally, and need further digging to find out right branch start  
		public final Nodeid branchHead;
		public Nodeid branchRoot;
		// either of these can be null, or both.
		// although RemoteBranch has either both parents null, or both non-null, when we construct a chain
		// we might encounter that we locally know one of branch's parent, hence in the chain corresponding field will be blank.
		public BranchChain p1;
		public BranchChain p2;

		public BranchChain(Nodeid head) {
			assert head != null;
			branchHead = head;
		}
		public boolean isTerminal() {
			return p1 == null || p2 == null;
		}
		
		@Override
		public String toString() {
			return String.format("BranchChain [%s, %s]", branchRoot, branchHead);
		}

		public void dump() {
			System.out.println(toString());
			internalDump("  ");
		}

		private void internalDump(String prefix) {
			if (p1 != null) {
				System.out.println(prefix + p1.toString());
			}
			if (p2 != null) {
				System.out.println(prefix + p2.toString());
			}
			prefix += "  ";
			if (p1 != null) {
				p1.internalDump(prefix);
			}
			if (p2 != null) {
				p2.internalDump(prefix);
			}
		}
	}

	/**
	 * @return list of nodeids from branchRoot to branchHead, inclusive. IOW, first element of the list is always root of the branch 
	 */
	public List<Nodeid> completeBranch(final Nodeid branchRoot, final Nodeid branchHead) throws HgException {
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

		List<Nodeid> initial = remoteRepo.between(branchHead, branchRoot);
		Nodeid[] result = new Nodeid[1 + (1 << initial.size())];
		result[0] = branchHead;
		int rootIndex = -1; // index in the result, where to place branche's root.
		if (initial.isEmpty()) {
			rootIndex = 1;
		} else if (initial.size() == 1) {
			rootIndex = 2;
		}
		LinkedList<DataEntry> datas = new LinkedList<DataEntry>();
		// DataEntry in datas has entries list filled with 'between' data, whereas 
		// DataEntry in toQuery keeps only nodeid and its index, with entries to be initialized before 
		// moving to datas. 
		LinkedList<DataEntry> toQuery = new LinkedList<DataEntry>();
		//
		datas.add(new DataEntry(branchHead, 0, initial));
		int totalQueries = 1;
		HashSet<Nodeid> queried = new HashSet<Nodeid>();
		while(!datas.isEmpty()) {
			// keep record of those planned to be queried next time we call between()
			// although may keep these in queried, if really don't want separate collection
			HashSet<Nodeid> scheduled = new HashSet<Nodeid>();  
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
						if (!queried.contains(x) && !scheduled.contains(x) && (rootIndex == -1 || rootIndex - de.headIndex > 1)) {
							/*queries for elements right before head is senseless, but unless we know head's index, do it anyway*/
							toQuery.add(new DataEntry(x, idx, null));
							scheduled.add(x);
						}
					}
				}
			} while (!datas.isEmpty());
			if (!toQuery.isEmpty()) {
				totalQueries++;
			}
			// for each query, create an between request range, keep record Range->DataEntry to know range's start index  
			LinkedList<HgRemoteRepository.Range> betweenBatch = new LinkedList<HgRemoteRepository.Range>();
			HashMap<HgRemoteRepository.Range, DataEntry> rangeToEntry = new HashMap<HgRemoteRepository.Range, DataEntry>();
			for (DataEntry de : toQuery) {
				queried.add(de.queryHead);
				HgRemoteRepository.Range r = new HgRemoteRepository.Range(branchRoot, de.queryHead);
				betweenBatch.add(r);
				rangeToEntry.put(r, de);
			}
			if (!betweenBatch.isEmpty()) {
				Map<Range, List<Nodeid>> between = remoteRepo.between(betweenBatch);
				for (Entry<Range, List<Nodeid>> e : between.entrySet()) {
					DataEntry de = rangeToEntry.get(e.getKey());
					assert de != null;
					de.entries = e.getValue();
					if (rootIndex == -1 && de.entries.size() == 1) {
						// returned sequence of length 1 means we used element from [head-2] as root
						int numberOfElementsExcludingRootAndHead = de.headIndex + 1;
						rootIndex = numberOfElementsExcludingRootAndHead + 1;
						System.out.printf("On query %d found out exact number of missing elements: %d\n", totalQueries, numberOfElementsExcludingRootAndHead);
					}
					datas.add(de); // queue up to record result and construct further requests
				}
				betweenBatch.clear();
				rangeToEntry.clear();
			}
			toQuery.clear();
		}
		if (rootIndex == -1) {
			throw new HgBadStateException("Shall not happen, provided between output is correct"); // FIXME
		}
		result[rootIndex] = branchRoot;
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
		System.out.println("Total queries:" + totalQueries);
		if (!resultOk) {
			throw new HgBadStateException("See console for details"); // FIXME
		}
		return fromRootToHead;
	}
}
