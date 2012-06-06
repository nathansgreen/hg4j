/*
 * Copyright (c) 2012 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgPhase.Draft;
import static org.tmatesoft.hg.repo.HgPhase.Secret;
import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgInvalidControlFileException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * Support to deal with phases feature fo Mercurial (as of Mercutial version 2.1)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class PhasesHelper {

	private final HgRepository hgRepo;
	private final HgChangelog.ParentWalker parentHelper;
	private Boolean repoSupporsPhases;
	private List<Nodeid> draftPhaseRoots;
	private List<Nodeid> secretPhaseRoots;
	private int[] earliestRevIndex = new int[HgPhase.values().length];

	public PhasesHelper(HgRepository repo) {
		this(repo, null);
	}

	public PhasesHelper(HgRepository repo, HgChangelog.ParentWalker pw) {
		hgRepo = repo;
		parentHelper = pw;
		Arrays.fill(earliestRevIndex, BAD_REVISION);
	}

	public boolean isCapableOfPhases() throws HgInvalidControlFileException {
		if (null == repoSupporsPhases) {
			repoSupporsPhases = readRoots();
		}
		return repoSupporsPhases.booleanValue();
	}


	public HgPhase getPhase(HgChangeset cset) throws HgInvalidControlFileException {
		final Nodeid csetRev = cset.getNodeid();
		final int csetRevIndex = cset.getRevision();
		return getPhase(csetRevIndex, csetRev);
	}

	public HgPhase getPhase(final int csetRevIndex, Nodeid csetRev) throws HgInvalidControlFileException {
		if (!isCapableOfPhases()) {
			return HgPhase.Undefined;
		}
		if (csetRev == null || csetRev.isNull()) {
			csetRev = hgRepo.getChangelog().getRevision(csetRevIndex);
		}
					
		for (HgPhase phase : new HgPhase[] {HgPhase.Secret, HgPhase.Draft }) {
			List<Nodeid> roots = getPhaseRoots(phase);
			if (roots.isEmpty()) {
				continue;
			}
			if (roots.contains(csetRev)) {
				return phase;
			}
			if (parentHelper != null) {
				if (parentHelper.childrenOf(roots).contains(csetRev)) {
					return phase;
				}
			} else {
				// no parent helper
				// search all descendants
				int earliestRootRevIndex = getEarliestPhaseRevision(phase);
				if (earliestRootRevIndex > csetRevIndex) {
					// this phase started later than our changeset was added, try another phase
					continue;
				}
				/*
				 * TODO descendants() method to build a BitSet with 1 at index of those that are descendants
				 * wrap it into a class with root nodeid to 
				 * (a) collect only for a subset of repository, 
				 * (b) be able to answer isDescendant(int csetRevIndex) using absolute indexing (i.e bitAt(csetRevIndex - rootRevIndex))  
				 */
				final HashSet<Nodeid> parents2consider = new HashSet<Nodeid>(roots);
				final boolean[] result = new boolean[] { false };
				hgRepo.getChangelog().walk(0/*earlierstRootRevIndex*/, csetRevIndex, new HgChangelog.ParentInspector() {
					
					public void next(int revisionIndex, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2) {
						boolean descendant = false;
						if (!nidParent1.isNull() && parents2consider.contains(nidParent1)) {
							parents2consider.add(revision);
							descendant = true;
						}
						if (!nidParent2.isNull() && parents2consider.contains(nidParent2)) {
							parents2consider.add(revision);
							descendant = true;
						}
						if (descendant && revisionIndex == csetRevIndex) {
							// revision of interest descends from one of the roots
							result[0] = true;
						}
					}
				});
				if (result[0]) {
					return phase;
				}
			}
		}
		return HgPhase.Public;

	}

	
	private int[] toIndexes(List<Nodeid> roots) throws HgInvalidControlFileException {
		int[] rv = new int[roots.size()];
		for (int i = 0; i < rv.length; i++) {
			rv[i] = hgRepo.getChangelog().getRevisionIndex(roots.get(i));
		}
		return rv;
	}

	private Boolean readRoots() throws HgInvalidControlFileException {
		// FIXME shall access phaseroots through HgRepository#repoPathHelper
		File phaseroots = new File(HgInternals.getRepositoryDir(hgRepo), "store/phaseroots");
		try {
			if (!phaseroots.exists()) {
				return Boolean.FALSE;
			}
			HashMap<HgPhase, List<Nodeid>> phase2roots = new HashMap<HgPhase, List<Nodeid>>();
			BufferedReader br = new BufferedReader(new FileReader(phaseroots));
			String line;
			while ((line = br.readLine()) != null) {
				String[] lc = line.trim().split("\\s+");
				if (lc.length == 0) {
					continue;
				}
				if (lc.length != 2) {
					HgInternals.getContext(hgRepo).getLog().warn(getClass(), "Bad line in phaseroots:%s", line);
					continue;
				}
				int phaseIndex = Integer.parseInt(lc[0]);
				Nodeid rootRev = Nodeid.fromAscii(lc[1]);
				HgPhase phase = HgPhase.parse(phaseIndex);
				List<Nodeid> roots = phase2roots.get(phase);
				if (roots == null) {
					phase2roots.put(phase, roots = new LinkedList<Nodeid>());
				}
				roots.add(rootRev);
			}
			draftPhaseRoots = phase2roots.containsKey(Draft) ? phase2roots.get(Draft) : Collections.<Nodeid>emptyList();
			secretPhaseRoots = phase2roots.containsKey(Secret) ? phase2roots.get(Secret) : Collections.<Nodeid>emptyList();
		} catch (IOException ex) {
			throw new HgInvalidControlFileException(ex.toString(), ex, phaseroots);
		}
		return Boolean.TRUE;
	}

	private List<Nodeid> getPhaseRoots(HgPhase phase) {
		switch (phase) {
		case Draft : return draftPhaseRoots;
		case Secret : return secretPhaseRoots;
		}
		return Collections.emptyList();
	}
	
	private int getEarliestPhaseRevision(HgPhase phase) throws HgInvalidControlFileException {
		int ordinal = phase.ordinal();
		if (earliestRevIndex[ordinal] == BAD_REVISION) {
			int[] rootIndexes = toIndexes(getPhaseRoots(phase));
			Arrays.sort(rootIndexes);
			// instead of MAX_VALUE may use clog.getLastRevision() + 1
			earliestRevIndex[ordinal] = rootIndexes.length == 0 ? Integer.MAX_VALUE : rootIndexes[0];
		}
		return earliestRevIndex[ordinal];
	}
}
