/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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
import static org.tmatesoft.hg.repo.HgRepositoryFiles.Phaseroots;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

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

	private final Internals repo;
	private final HgParentChildMap<HgChangelog> parentHelper;
	private Boolean repoSupporsPhases;
	private List<Nodeid> draftPhaseRoots;
	private List<Nodeid> secretPhaseRoots;
	private RevisionDescendants[][] phaseDescendants = new RevisionDescendants[HgPhase.values().length][];

	public PhasesHelper(Internals internalRepo) {
		this(internalRepo, null);
	}

	public PhasesHelper(Internals internalRepo, HgParentChildMap<HgChangelog> pw) {
		repo = internalRepo;
		parentHelper = pw;
	}
	
	public HgRepository getRepo() {
		return repo.getRepo();
	}

	public boolean isCapableOfPhases() throws HgRuntimeException {
		if (null == repoSupporsPhases) {
			repoSupporsPhases = readRoots();
		}
		return repoSupporsPhases.booleanValue();
	}


	/**
	 * @param cset revision to query
	 * @return phase of the changeset, never <code>null</code>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public HgPhase getPhase(HgChangeset cset) throws HgRuntimeException {
		final Nodeid csetRev = cset.getNodeid();
		final int csetRevIndex = cset.getRevisionIndex();
		return getPhase(csetRevIndex, csetRev);
	}

	/**
	 * @param csetRevIndex revision index to query
	 * @param csetRev revision nodeid, optional 
	 * @return phase of the changeset, never <code>null</code>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public HgPhase getPhase(final int csetRevIndex, Nodeid csetRev) throws HgRuntimeException {
		if (!isCapableOfPhases()) {
			return HgPhase.Undefined;
		}
		// csetRev is only used when parentHelper is available
		if (parentHelper != null && (csetRev == null || csetRev.isNull())) {
			csetRev = getRepo().getChangelog().getRevision(csetRevIndex);
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


	/**
	 * @return all revisions with secret phase
	 */
	public RevisionSet allSecret() {
		return allOf(HgPhase.Secret);
	}
	
	/**
	 * @return all revisions with draft phase
	 */
	public RevisionSet allDraft() {
		return allOf(HgPhase.Draft).subtract(allOf(HgPhase.Secret));
	}
	
	public void updateRoots(Collection<Nodeid> draftRoots, Collection<Nodeid> secretRoots) throws HgInvalidControlFileException {
		draftPhaseRoots = draftRoots.isEmpty() ? Collections.<Nodeid>emptyList() : new ArrayList<Nodeid>(draftRoots);
		secretPhaseRoots = secretRoots.isEmpty() ? Collections.<Nodeid>emptyList() : new ArrayList<Nodeid>(secretRoots);
		String fmt = "%d %s\n";
		File phaseroots = repo.getRepositoryFile(Phaseroots);
		FileWriter fw = null;
		try {
			fw = new FileWriter(phaseroots);
			for (Nodeid n : secretPhaseRoots) {
				fw.write(String.format(fmt, HgPhase.Secret.mercurialOrdinal(), n.toString()));
			}
			for (Nodeid n : draftPhaseRoots) {
				fw.write(String.format(fmt, HgPhase.Draft.mercurialOrdinal(), n.toString()));
			}
			fw.flush();
		} catch (IOException ex) {
			throw new HgInvalidControlFileException(ex.getMessage(), ex, phaseroots);
		} finally {
			new FileUtils(repo.getLog()).closeQuietly(fw);
		}
	}

	/**
	 * For a given phase, collect all revisions with phase that is the same or more private (i.e. for Draft, returns Draft+Secret)
	 * The reason is not a nice API intention (which is awful, indeed), but an ease of implementation 
	 */
	private RevisionSet allOf(HgPhase phase) {
		assert phase != HgPhase.Public;
		if (!isCapableOfPhases()) {
			return new RevisionSet(Collections.<Nodeid>emptyList());
		}
		final List<Nodeid> roots = getPhaseRoots(phase);
		if (parentHelper != null) {
			return new RevisionSet(roots).union(new RevisionSet(parentHelper.childrenOf(roots)));
		} else {
			RevisionSet rv = new RevisionSet(Collections.<Nodeid>emptyList());
			for (RevisionDescendants rd : getPhaseDescendants(phase)) {
				rv = rv.union(rd.asRevisionSet());
			}
			return rv;
		}
	}

	private Boolean readRoots() throws HgRuntimeException {
		File phaseroots = repo.getRepositoryFile(Phaseroots);
		try {
			if (!phaseroots.exists()) {
				return Boolean.FALSE;
			}
			LineReader lr = new LineReader(phaseroots, repo.getLog());
			final Collection<String> lines = lr.read(new LineReader.SimpleLineCollector(), new LinkedList<String>());
			HashMap<HgPhase, List<Nodeid>> phase2roots = new HashMap<HgPhase, List<Nodeid>>();
			for (String line : lines) {
				String[] lc = line.split("\\s+");
				if (lc.length == 0) {
					continue;
				}
				if (lc.length != 2) {
					repo.getSessionContext().getLog().dump(getClass(), Warn, "Bad line in phaseroots:%s", line);
					continue;
				}
				int phaseIndex = Integer.parseInt(lc[0]);
				Nodeid rootRev = Nodeid.fromAscii(lc[1]);
				if (!getRepo().getChangelog().isKnown(rootRev)) {
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
		} catch (HgIOException ex) {
			throw new HgInvalidControlFileException(ex, true);
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


	private RevisionDescendants[] getPhaseDescendants(HgPhase phase) throws HgRuntimeException {
		int ordinal = phase.ordinal();
		if (phaseDescendants[ordinal] == null) {
			phaseDescendants[ordinal] = buildPhaseDescendants(phase);
		}
		return phaseDescendants[ordinal];
	}

	private RevisionDescendants[] buildPhaseDescendants(HgPhase phase) throws HgRuntimeException {
		int[] roots = toIndexes(getPhaseRoots(phase));
		RevisionDescendants[] rv = new RevisionDescendants[roots.length];
		for (int i = 0; i < roots.length; i++) {
			rv[i] = new RevisionDescendants(getRepo(), roots[i]);
			rv[i].build();
		}
		return rv;
	}
	
	private int[] toIndexes(List<Nodeid> roots) throws HgRuntimeException {
		int[] rv = new int[roots.size()];
		for (int i = 0; i < rv.length; i++) {
			rv[i] = getRepo().getChangelog().getRevisionIndex(roots.get(i));
		}
		return rv;
	}
}
