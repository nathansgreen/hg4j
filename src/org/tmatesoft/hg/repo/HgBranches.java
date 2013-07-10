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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ChangelogMonitor;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FileUtils;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Access information about branches in the repository
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgBranches {
	
	private final Internals internalRepo;
	private final ChangelogMonitor repoChangeTracker;
	private final Map<String, BranchInfo> branches = new TreeMap<String, BranchInfo>();
	private boolean isCacheActual = false;

	HgBranches(Internals internals) {
		internalRepo = internals;
		repoChangeTracker = new ChangelogMonitor(internals.getRepo());
	}

	private int readCache() {
		final HgRepository repo = internalRepo.getRepo();
		File branchheadsCache = getCacheFile();
		int lastInCache = -1;
		if (!branchheadsCache.canRead()) {
			return lastInCache;
		}
		BufferedReader br = null; // TODO replace with LineReader
		final Pattern spacePattern = Pattern.compile(" ");
		try {
			final LinkedHashMap<String, List<Nodeid>> branchHeads = new LinkedHashMap<String, List<Nodeid>>();
			br = new BufferedReader(new FileReader(branchheadsCache));
			String line = br.readLine();
			if (line == null || line.trim().length() == 0) {
				return lastInCache;
			}
			String[] cacheIdentity = spacePattern.split(line.trim());
			lastInCache = Integer.parseInt(cacheIdentity[1]);
			final int lastKnownRepoRevIndex = repo.getChangelog().getLastRevision();
			if (lastInCache > lastKnownRepoRevIndex || !repo.getChangelog().getRevision(lastKnownRepoRevIndex).equals(Nodeid.fromAscii(cacheIdentity[0]))) {
				// there are chances cache file got invalid entries due to e.g. rollback operation
				return -1;
			}
			while ((line = br.readLine()) != null) {
				String[] elements = spacePattern.split(line.trim());
				if (elements.length != 2) {
					// bad entry
					continue;
				}
				// I assume split returns substrings of the original string, hence copy of a branch name
				String branchName = new String(elements[elements.length-1]);
				List<Nodeid> heads = branchHeads.get(elements[1]);
				if (heads == null) {
					branchHeads.put(branchName, heads = new LinkedList<Nodeid>());
				}
				heads.add(Nodeid.fromAscii(elements[0]));
			}
			for (Map.Entry<String, List<Nodeid>> e : branchHeads.entrySet()) {
				Nodeid[] heads = e.getValue().toArray(new Nodeid[e.getValue().size()]);
				BranchInfo bi = new BranchInfo(e.getKey(), heads);
				branches.put(e.getKey(), bi);
			}
			return lastInCache;
		} catch (IOException ex) {
			 // log error, but otherwise do nothing
			repo.getSessionContext().getLog().dump(getClass(), Warn, ex, null);
			// FALL THROUGH to return -1 indicating no cache information 
		} catch (NumberFormatException ex) {
			repo.getSessionContext().getLog().dump(getClass(), Warn, ex, null);
			// FALL THROUGH
		} catch (HgRuntimeException ex) {
			// if happens, log error and pretend there's no cache
			repo.getSessionContext().getLog().dump(getClass(), Error, ex, null);
			// FALL THROUGH
		} finally {
			new FileUtils(repo.getSessionContext().getLog(), this).closeQuietly(br);
		}
		return -1; // deliberately not lastInCache, to avoid anything but -1 when 1st line was read and there's error is in lines 2..end
	}
	
	void collect(final ProgressSupport ps) throws HgRuntimeException {
		branches.clear();
		final HgRepository repo = internalRepo.getRepo();
		final HgChangelog clog = repo.getChangelog();
		final HgRevisionMap<HgChangelog> rmap;
		ps.start(1 + clog.getRevisionCount() * 2);
		//
		int lastCached = readCache();
		isCacheActual = lastCached == clog.getLastRevision();
		if (!isCacheActual) {
			// XXX need a way to share HgParentChildMap<HgChangelog>
			final HgParentChildMap<HgChangelog> pw = new HgParentChildMap<HgChangelog>(clog);
			pw.init();
			ps.worked(clog.getRevisionCount());
			//
			// first revision branch found at
			final HashMap<String, Nodeid> branchStart = new HashMap<String, Nodeid>();
			// revisions from the branch that have no children at all
			final HashMap<String, List<Nodeid>> branchHeads = new HashMap<String, List<Nodeid>>();
			HgChangelog.Inspector insp = new HgChangelog.Inspector() {
				
				private final ArrayList<Nodeid> parents = new ArrayList<Nodeid>(3);
				
				public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
					String branchName = cset.branch();
					List<Nodeid> _branchHeads;
					// there are chances (with --force key) branch can get more than one start
					// revision. Neither BranchInfo nor this code support this scenario at the moment. 
					if (!branchStart.containsKey(branchName)) {
						branchStart.put(branchName, nodeid);
						branchHeads.put(branchName, _branchHeads = new LinkedList<Nodeid>());
					} else {
						_branchHeads = branchHeads.get(branchName);
						if (_branchHeads == null) {
							branchHeads.put(branchName, _branchHeads = new LinkedList<Nodeid>());
						}
					}
					// so far present node is the best candidate for head
					_branchHeads.add(nodeid);
					parents.clear();
					// parents of this node, however, cease to be heads (if they are from this branch)
					pw.appendParentsOf(nodeid, parents);
					_branchHeads.removeAll(parents);
					ps.worked(1);
				}
			};
			// XXX alternatively may iterate with pw.all().subList(lastCached)
			// but need an effective way to find out branch of particular changeset
			clog.range(lastCached == -1 ? 0 : lastCached+1, HgRepository.TIP, insp);
			//
			// build BranchInfo, based on found and cached 
			for (String bn : branchStart.keySet()) {
				BranchInfo bi = branches.get(bn);
				if (bi != null) {
					// combine heads found so far with those cached 
					LinkedHashSet<Nodeid> oldHeads = new LinkedHashSet<Nodeid>(bi.getHeads());
					// expect size of both oldHeads and newHeads sets to be small, and for x for hence acceptable.
					for (Nodeid newHead : branchHeads.get(bn)) {
						for (Iterator<Nodeid> it = oldHeads.iterator(); it.hasNext();) {
							if (pw.isChild(it.next(), newHead)) {
								it.remove();
							}
						}
					}
					oldHeads.addAll(branchHeads.get(bn));
					assert oldHeads.size() > 0;
					bi = new BranchInfo(bn, bi.getStart(), oldHeads.toArray(new Nodeid[oldHeads.size()]));
				} else {
					Nodeid[] heads = branchHeads.get(bn).toArray(new Nodeid[0]);
					bi = new BranchInfo(bn, branchStart.get(bn), heads);
				}
				branches.put(bn, bi);
			}
			rmap = pw.getRevisionMap();
		} else { // !cacheActual
			rmap = new HgRevisionMap<HgChangelog>(clog).init(); 
		}
		for (BranchInfo bi : branches.values()) {
			bi.validate(clog, rmap);
		}
		repoChangeTracker.touch();
		ps.done();
	}

	public List<BranchInfo> getAllBranches() throws HgInvalidControlFileException {
		return new LinkedList<BranchInfo>(branches.values());
				
	}

	public BranchInfo getBranch(String name) throws HgInvalidControlFileException {
		return branches.get(name);
	}

	/**
	 * Writes down information about repository branches in a format Mercurial native client can understand.
	 * Cache file gets overwritten only if it is out of date (i.e. misses some branch information)
	 * @throws IOException if write to cache file failed
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	@Experimental(reason="Usage of cache isn't supposed to be public knowledge")
	public void writeCache() throws IOException, HgRuntimeException {
		if (isCacheActual) {
			return;
		}
		File branchheadsCache = getCacheFile();
		if (!branchheadsCache.exists()) {
			branchheadsCache.getParentFile().mkdirs(); // just in case cache/ doesn't exist jet
			branchheadsCache.createNewFile();
		}
		if (!branchheadsCache.canWrite()) {
			return;
		}
		final HgRepository repo = internalRepo.getRepo();
		final int lastRev = repo.getChangelog().getLastRevision();
		final Nodeid lastNid = repo.getChangelog().getRevision(lastRev);
		BufferedWriter bw = new BufferedWriter(new FileWriter(branchheadsCache));
		bw.write(lastNid.toString());
		bw.write((int) ' ');
		bw.write(Integer.toString(lastRev));
		bw.write("\n");
		for (BranchInfo bi : branches.values()) {
			for (Nodeid nid : bi.getHeads()) {
				bw.write(nid.toString());
				bw.write((int) ' ');
				bw.write(bi.getName());
				bw.write("\n");
			}
		}
		bw.close();
	}

	private File getCacheFile() {
		// prior to 1.8 used to be .hg/branchheads.cache
		// since 2.5 there is filter suffix
		File f = internalRepo.getFileFromRepoDir("cache/branchheads-base");
		if (f.exists()) {
			return f;
		}
		return internalRepo.getFileFromRepoDir("cache/branchheads");
	}
	
	/*package-local*/ void reloadIfChanged(ProgressSupport ps) throws HgRuntimeException {
		if (repoChangeTracker.isChanged()) {
			collect(ps);
		}
	}

	public static class BranchInfo {
		private final String name;
		private List<Nodeid> heads;
		private boolean closed;
		private final Nodeid start;
		private List<Nodeid> closedHeads; // subset of heads, those that bear 'closed' flag, or null if closed == true

		// XXX in fact, few but not all branchHeads might be closed, and isClosed for whole branch is not
		// possible to determine.
		BranchInfo(String branchName, Nodeid first, Nodeid[] branchHeads) {
			name = branchName;
			start = first;
			heads = Arrays.asList(branchHeads);
		}
		
		// incomplete branch, there's not enough information at the time of creation. shall be replaced with
		// proper BI in #collect()
		BranchInfo(String branchName, Nodeid[] branchHeads) {
			this(branchName, Nodeid.NULL, branchHeads);
		}
		
		void validate(HgChangelog clog, HgRevisionMap<HgChangelog> rmap) throws HgRuntimeException {
			int[] localCset = new int[heads.size()];
			int i = 0;
			for (Nodeid h : heads) {
				localCset[i++] = rmap.revisionIndex(h);
			}
			// [0] tipmost, [1] tipmost open
			final Nodeid[] tipmost = new Nodeid[] {null, null};
			final boolean[] allClosed = new boolean[] { true };
			final ArrayList<Nodeid> _closedHeads = new ArrayList<Nodeid>(heads.size());
			clog.range(new HgChangelog.Inspector() {
				
				public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
					assert heads.contains(nodeid);
					tipmost[0] = nodeid;
					if (!"1".equals(cset.extras().get("close"))) {
						tipmost[1] = nodeid;
						allClosed[0] = false;
					} else {
						_closedHeads.add(nodeid);
					}
				}
			}, localCset);
			closed = allClosed[0];
			Nodeid[] outcome = new Nodeid[localCset.length];
			i = 0;
			if (!closed && tipmost[1] != null) { 
				outcome[i++] = tipmost[1];
				if (i < outcome.length && !tipmost[0].equals(tipmost[1])) {
					outcome[i++] = tipmost[0];
				}
			} else {
				outcome[i++] = tipmost[0];
			}
			for (Nodeid h : heads) {
				if (!h.equals(tipmost[0]) && !h.equals(tipmost[1])) {
					outcome[i++] = h;
				}
			}
			heads = Arrays.asList(outcome);
			if (closed) {
				// no need
				closedHeads = null;
			} else {
				_closedHeads.trimToSize();
				closedHeads = _closedHeads;
			}
		}

		public String getName() {
			return name;
		}
		/**
		 * @return <code>true</code> if all heads of this branch are marked as closed
		 */
		public boolean isClosed() {
			return closed;
		}

		/**
		 * @return all heads for the branch, both open and closed, tip-most head first
		 */
		public List<Nodeid> getHeads() {
			return heads;
		}

		/**
		 * 
		 * @param head one of revision from {@link #getHeads() heads} of this branch 
		 * @return true if this particular head is closed
		 * @throws IllegalArgumentException if argument is not from {@link #getHeads() heads} of this branch
		 */
		public boolean isClosed(Nodeid head) {
			if (!heads.contains(head)) {
				throw new IllegalArgumentException(String.format("Revision %s does not belong to heads of %s branch", head, name), null);
			}
			if (closed) {
				return true;
			}
			return closedHeads.contains(head);
		}
//		public Nodeid getTip() {
//		}
		// XXX Not public as there are chances for few possible branch starts, and I need to decide how to handle that
		/*public*/ Nodeid getStart() {
			// first node where branch appears
			return start;
		}
	}
}
