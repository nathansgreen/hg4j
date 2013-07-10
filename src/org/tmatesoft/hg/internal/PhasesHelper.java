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
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRemoteRepository;
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

	public boolean withSecretRoots() {
		return !secretPhaseRoots.isEmpty();
	}

	/**
	 * @param cset
	 *            revision to query
	 * @return phase of the changeset, never <code>null</code>
	 * @throws HgInvalidControlFileException
	 *             if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException
	 *             subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public HgPhase getPhase(HgChangeset cset) throws HgRuntimeException {
		final Nodeid csetRev = cset.getNodeid();
		final int csetRevIndex = cset.getRevisionIndex();
		return getPhase(csetRevIndex, csetRev);
	}

	/**
	 * @param csetRevIndex
	 *            revision index to query
	 * @param csetRev
	 *            revision nodeid, optional
	 * @return phase of the changeset, never <code>null</code>
	 * @throws HgInvalidControlFileException
	 *             if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException
	 *             subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public HgPhase getPhase(final int csetRevIndex, Nodeid csetRev) throws HgRuntimeException {
		if (!isCapableOfPhases()) {
			return HgPhase.Undefined;
		}
		// csetRev is only used when parentHelper is available
		if (parentHelper != null && (csetRev == null || csetRev.isNull())) {
			csetRev = getRepo().getChangelog().getRevision(csetRevIndex);
		}

		for (HgPhase phase : new HgPhase[] { HgPhase.Secret, HgPhase.Draft }) {
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

	// XXX throw HgIOException instead?
	public void updateRoots(Collection<Nodeid> draftRoots, Collection<Nodeid> secretRoots) throws HgInvalidControlFileException {
		draftPhaseRoots = draftRoots.isEmpty() ? Collections.<Nodeid> emptyList() : new ArrayList<Nodeid>(draftRoots);
		secretPhaseRoots = secretRoots.isEmpty() ? Collections.<Nodeid> emptyList() : new ArrayList<Nodeid>(secretRoots);
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
			new FileUtils(repo.getLog(), this).closeQuietly(fw);
		}
	}

	public void newCommitNode(Nodeid newChangeset, HgPhase newCommitPhase) throws HgRuntimeException {
		final int riCset = repo.getRepo().getChangelog().getRevisionIndex(newChangeset);
		HgPhase ph = getPhase(riCset, newChangeset);
		if (ph.compareTo(newCommitPhase) >= 0) {
			// present phase is more secret than the desired one
			return;
		}
		// newCommitPhase can't be public here, condition above would be satisfied
		assert newCommitPhase != HgPhase.Public;
		// ph is e.g public when newCommitPhase is draft
		// or is draft when desired phase is secret
		final RevisionSet rs = allOf(newCommitPhase).union(new RevisionSet(Collections.singleton(newChangeset)));
		final RevisionSet newRoots;
		if (parentHelper != null) {
			newRoots = rs.roots(parentHelper);
		} else {
			newRoots = rs.roots(repo.getRepo());
		}
		if (newCommitPhase == HgPhase.Draft) {
			updateRoots(newRoots.asList(), secretPhaseRoots);
		} else if (newCommitPhase == HgPhase.Secret) {
			updateRoots(draftPhaseRoots, newRoots.asList());
		} else {
			throw new HgInvalidStateException(String.format("Unexpected phase %s for new commits", newCommitPhase));
		}
	}

	/**
	 * @return set of revisions that are public locally, but draft on remote.
	 */
	public RevisionSet synchronizeWithRemote(HgRemoteRepository.Phases remotePhases, RevisionSet sharedWithRemote) throws HgInvalidControlFileException {
		assert parentHelper != null;
		RevisionSet presentSecret = allSecret();
		RevisionSet presentDraft = allDraft();
		RevisionSet secretLeft, draftLeft;
		RevisionSet remoteDrafts = knownRemoteDrafts(remotePhases, sharedWithRemote, presentSecret);
		if (remotePhases.isPublishingServer()) {
			// although it's unlikely shared revisions would affect secret changesets,
			// it doesn't hurt to check secret roots along with draft ones
			// 
			// local drafts that are known to be public now
			RevisionSet draftsBecomePublic = presentDraft.intersect(sharedWithRemote);
			RevisionSet secretsBecomePublic = presentSecret.intersect(sharedWithRemote);
			// any ancestor of the public revision is public, too
			RevisionSet draftsGone = presentDraft.ancestors(draftsBecomePublic, parentHelper);
			RevisionSet secretsGone = presentSecret.ancestors(secretsBecomePublic, parentHelper);
			// remove public and their ancestors from drafts
			draftLeft = presentDraft.subtract(draftsGone).subtract(draftsBecomePublic);
			secretLeft = presentSecret.subtract(secretsGone).subtract(secretsBecomePublic);
		} else {
			// shall merge local and remote phase states
			// revisions that cease to be secret (gonna become Public), e.g. someone else pushed them
			RevisionSet secretGone = presentSecret.intersect(remoteDrafts);
			// parents of those remote drafts are public, mark them as public locally, too
			RevisionSet remotePublic = presentSecret.ancestors(secretGone, parentHelper);
			secretLeft = presentSecret.subtract(secretGone).subtract(remotePublic);
			/*
			 * Revisions grow from left to right (parents to the left, children to the right)
			 * 
			 * I: Set of local is subset of remote
			 * 
			 * local draft
			 * --o---r---o---l---o--
			 * remote draft
			 * 
			 * Remote draft roots shall be updated
			 * 
			 * 
			 * II: Set of local is superset of remote
			 * 
			 * local draft
			 * --o---l---o---r---o--
			 * remote draft
			 * 
			 * Local draft roots shall be updated
			 */
			RevisionSet sharedDraft = presentDraft.intersect(remoteDrafts); // (I: ~presentDraft; II: ~remoteDraft
			// XXX do I really need sharedDrafts here? why not ancestors(remoteDrafts)?
			RevisionSet localDraftRemotePublic = presentDraft.ancestors(sharedDraft, parentHelper); // I: 0; II: those treated public on remote
			// remoteDrafts are local revisions known as draft@remote
			// remoteDraftsLocalPublic - revisions that would cease to be listed as draft on remote
			RevisionSet remoteDraftsLocalPublic = remoteDrafts.ancestors(sharedDraft, parentHelper);
			RevisionSet remoteDraftsLeft = remoteDrafts.subtract(remoteDraftsLocalPublic);
			// forget those deemed public by remote (drafts shared by both remote and local are ok to stay)
			RevisionSet combinedDraft = presentDraft.union(remoteDraftsLeft);
			draftLeft = combinedDraft.subtract(localDraftRemotePublic);
		}
		final RevisionSet newDraftRoots = draftLeft.roots(parentHelper);
		final RevisionSet newSecretRoots = secretLeft.roots(parentHelper);
		updateRoots(newDraftRoots.asList(), newSecretRoots.asList());
		//
		// if there's a remote draft root that points to revision we know is public
		RevisionSet remoteDraftsLocalPublic = remoteDrafts.subtract(draftLeft).subtract(secretLeft);
		return remoteDraftsLocalPublic;
	}

	// shared - set of revisions we've shared with remote
	private RevisionSet knownRemoteDrafts(HgRemoteRepository.Phases remotePhases, RevisionSet shared, RevisionSet localSecret) {
		ArrayList<Nodeid> knownRemoteDraftRoots = new ArrayList<Nodeid>();
		for (Nodeid rdr : remotePhases.draftRoots()) {
			if (parentHelper.knownNode(rdr)) {
				knownRemoteDraftRoots.add(rdr);
			}
		}
		// knownRemoteDraftRoots + childrenOf(knownRemoteDraftRoots) is everything remote may treat as Draft
		RevisionSet remoteDrafts = new RevisionSet(knownRemoteDraftRoots);
		RevisionSet localChildren = remoteDrafts.children(parentHelper);
		// we didn't send any local secret revision
		localChildren = localChildren.subtract(localSecret);
		// draft roots are among remote drafts
		remoteDrafts = remoteDrafts.union(localChildren);
		// remoteDrafts is set of local revisions remote may see as Draft. However,
		// need to remove from this set revisions we didn't share with remote:
		// 1) shared.children gives all local revisions accessible from shared.
		// 2) shared.roots.children is equivalent with smaller intermediate set, the way we build
		// childrenOf doesn't really benefits from that.
		RevisionSet localChildrenNotSent = shared.children(parentHelper).subtract(shared);
		// remote shall know only what we've sent, subtract revisions we didn't actually sent
		remoteDrafts = remoteDrafts.subtract(localChildrenNotSent);
		return remoteDrafts;
	}

	/**
	 * For a given phase, collect all revisions with phase that is the same or more private (i.e. for Draft, returns Draft+Secret)
	 * The reason is not a nice API intention (which is awful, indeed), but an ease of implementation
	 */
	private RevisionSet allOf(HgPhase phase) {
		assert phase != HgPhase.Public;
		if (!isCapableOfPhases()) {
			return new RevisionSet(Collections.<Nodeid> emptyList());
		}
		final List<Nodeid> roots = getPhaseRoots(phase);
		if (parentHelper != null) {
			return new RevisionSet(roots).union(new RevisionSet(parentHelper.childrenOf(roots)));
		} else {
			RevisionSet rv = new RevisionSet(Collections.<Nodeid> emptyList());
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
				if (repo.shallCreatePhaseroots()) {
					draftPhaseRoots = Collections.<Nodeid>emptyList();
					secretPhaseRoots = Collections.<Nodeid>emptyList();
					return Boolean.TRUE;
				}
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
			draftPhaseRoots = phase2roots.containsKey(Draft) ? phase2roots.get(Draft) : Collections.<Nodeid> emptyList();
			secretPhaseRoots = phase2roots.containsKey(Secret) ? phase2roots.get(Secret) : Collections.<Nodeid> emptyList();
		} catch (HgIOException ex) {
			throw new HgInvalidControlFileException(ex, true);
		}
		return Boolean.TRUE;
	}

	private List<Nodeid> getPhaseRoots(HgPhase phase) {
		switch (phase) {
		case Draft:
			return draftPhaseRoots;
		case Secret:
			return secretPhaseRoots;
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
