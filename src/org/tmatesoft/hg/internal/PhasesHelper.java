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
import static org.tmatesoft.hg.util.LogFacility.Severity.Info;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * Support to deal with Mercurial phases feature (as of Mercurial version 2.1)
 * 
 * @see http://mercurial.selenic.com/wiki/Phases
 * @see http://mercurial.selenic.com/wiki/PhasesDevel
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class PhasesHelper {

	private final HgRepository repo;
	private final HgParentChildMap<HgChangelog> parentHelper;
	private Boolean repoSupporsPhases;
	private List<Nodeid> draftPhaseRoots;
	private List<Nodeid> secretPhaseRoots;
	private RevisionDescendants[][] phaseDescendants = new RevisionDescendants[HgPhase.values().length][];

	public PhasesHelper(HgRepository hgRepo) {
		this(hgRepo, null);
	}

	public PhasesHelper(HgRepository hgRepo, HgParentChildMap<HgChangelog> pw) {
		repo = hgRepo;
		parentHelper = pw;
	}
	
	public HgRepository getRepo() {
		return repo;
	}

	public boolean isCapableOfPhases() throws HgInvalidControlFileException {
		if (null == repoSupporsPhases) {
			repoSupporsPhases = readRoots();
		}
		return repoSupporsPhases.booleanValue();
	}


	public HgPhase getPhase(HgChangeset cset) throws HgInvalidControlFileException {
		final Nodeid csetRev = cset.getNodeid();
		final int csetRevIndex = cset.getRevisionIndex();
		return getPhase(csetRevIndex, csetRev);
	}

	public HgPhase getPhase(final int csetRevIndex, Nodeid csetRev) throws HgInvalidControlFileException {
		if (!isCapableOfPhases()) {
			return HgPhase.Undefined;
		}
		// csetRev is only used when parentHelper is available
		if (parentHelper != null && (csetRev == null || csetRev.isNull())) {
			csetRev = repo.getChangelog().getRevision(csetRevIndex);
		}
					
		for (HgPhase phase : new HgPhase[] {HgPhase.Secret, HgPhase.Draft }) {
			List<Nodeid> roots = getPhaseRoots(phase);
			if (roots.isEmpty()) {
				continue;
			}
			if (parentHelper != null) {
				if (roots.contains(csetRev)) {
					return phase;
				}
				if (parentHelper.childrenOf(roots).contains(csetRev)) {
					return phase;
				}
			} else {
				// no parent helper
				// search all descendants.RevisuionDescendats includes root as well.
				for (RevisionDescendants rd : getPhaseDescendants(phase)) {
					// isCandidate is to go straight to another root if changeset was added later that the current root
					if (rd.isCandidate(csetRevIndex) && rd.isDescendant(csetRevIndex)) {
						return phase;
					}
				}
			}
		}
		return HgPhase.Public;

	}

	private Boolean readRoots() throws HgInvalidControlFileException {
		// FIXME shall access phaseroots through HgRepository#repoPathHelper
		File phaseroots = new File(HgInternals.getRepositoryDir(repo), "store/phaseroots");
		BufferedReader br = null;
		try {
			if (!phaseroots.exists()) {
				return Boolean.FALSE;
			}
			HashMap<HgPhase, List<Nodeid>> phase2roots = new HashMap<HgPhase, List<Nodeid>>();
			br = new BufferedReader(new FileReader(phaseroots));
			String line;
			while ((line = br.readLine()) != null) {
				String[] lc = line.trim().split("\\s+");
				if (lc.length == 0) {
					continue;
				}
				if (lc.length != 2) {
					repo.getSessionContext().getLog().dump(getClass(), Warn, "Bad line in phaseroots:%s", line);
					continue;
				}
				int phaseIndex = Integer.parseInt(lc[0]);
				Nodeid rootRev = Nodeid.fromAscii(lc[1]);
				if (!repo.getChangelog().isKnown(rootRev)) {
					repo.getSessionContext().getLog().dump(getClass(), Warn, "Phase(%d) root node %s doesn't exist in the repository, ignored.", phaseIndex, rootRev);
					continue;
				}
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
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException ex) {
					repo.getSessionContext().getLog().dump(getClass(), Info, ex, null);
					// ignore the exception otherwise 
				}
			}
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


	private RevisionDescendants[] getPhaseDescendants(HgPhase phase) throws HgInvalidControlFileException {
		int ordinal = phase.ordinal();
		if (phaseDescendants[ordinal] == null) {
			phaseDescendants[ordinal] = buildPhaseDescendants(phase);
		}
		return phaseDescendants[ordinal];
	}

	private RevisionDescendants[] buildPhaseDescendants(HgPhase phase) throws HgInvalidControlFileException {
		int[] roots = toIndexes(getPhaseRoots(phase));
		RevisionDescendants[] rv = new RevisionDescendants[roots.length];
		for (int i = 0; i < roots.length; i++) {
			rv[i] = new RevisionDescendants(repo, roots[i]);
			rv[i].build();
		}
		return rv;
	}
	
	private int[] toIndexes(List<Nodeid> roots) throws HgInvalidControlFileException {
		int[] rv = new int[roots.size()];
		for (int i = 0; i < rv.length; i++) {
			rv[i] = repo.getChangelog().getRevisionIndex(roots.get(i));
		}
		return rv;
	}
}
