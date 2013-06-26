/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.tmatesoft.hg.internal.BundleGenerator;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.repo.HgBookmarks;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgPhase;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.ProgressSupport;
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgPushCommand extends HgAbstractCommand<HgPushCommand> {
	
	private final HgRepository repo;
	private HgRemoteRepository remoteRepo;

	public HgPushCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	public HgPushCommand destination(HgRemoteRepository hgRemote) {
		remoteRepo = hgRemote;
		return this;
	}

	public void execute() throws HgRemoteConnectionException, HgIOException, CancelledException, HgLibraryFailureException {
		final ProgressSupport progress = getProgressSupport(null);
		try {
			progress.start(100);
			//
			// find out missing
			// TODO refactor same code in HgOutgoingCommand #getComparator and #getParentHelper
			final HgChangelog clog = repo.getChangelog();
			final HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(clog);
			parentHelper.init();
			final RepositoryComparator comparator = new RepositoryComparator(parentHelper, remoteRepo);
			comparator.compare(new ProgressSupport.Sub(progress, 50), getCancelSupport(null, true));
			List<Nodeid> l = comparator.getLocalOnlyRevisions();
			//
			// prepare bundle
			final Internals implRepo = HgInternals.getImplementationRepo(repo);
			BundleGenerator bg = new BundleGenerator(implRepo);
			File bundleFile = bg.create(l);
			progress.worked(20);
			HgBundle b = new HgLookup(repo.getSessionContext()).loadBundle(bundleFile);
			//
			// send changes
			remoteRepo.unbundle(b, comparator.getRemoteHeads());
			progress.worked(20);
			//
			// update phase information
			PhasesHelper phaseHelper = new PhasesHelper(implRepo, parentHelper);
			if (phaseHelper.isCapableOfPhases()) {
				RevisionSet outgoing = new RevisionSet(l);
				RevisionSet presentSecret = phaseHelper.allSecret();
				RevisionSet presentDraft = phaseHelper.allDraft();
				RevisionSet secretLeft, draftLeft;
				HgRemoteRepository.Phases remotePhases = remoteRepo.getPhases();
				if (remotePhases.isPublishingServer()) {
					// although it's unlikely outgoing would affect secret changesets,
					// it doesn't hurt to check secret roots along with draft ones
					secretLeft = presentSecret.subtract(outgoing);
					draftLeft = presentDraft.subtract(outgoing);
				} else {
					// shall merge local and remote phase states
					ArrayList<Nodeid> knownRemoteDraftRoots = new ArrayList<Nodeid>();
					for (Nodeid rdr : remotePhases.draftRoots()) {
						if (clog.isKnown(rdr)) {
							knownRemoteDraftRoots.add(rdr);
						}
					}
					// childrenOf(knownRemoteDraftRoots) is everything remote may treat as Draft
					RevisionSet remoteDrafts = new RevisionSet(parentHelper.childrenOf(knownRemoteDraftRoots));
					List<Nodeid> localChildrenNotSent = parentHelper.childrenOf(outgoing.heads(parentHelper).asList());
					// remote shall know only what we've sent, subtract revisions we didn't actually sent
					remoteDrafts = remoteDrafts.subtract(new RevisionSet(localChildrenNotSent));
					// if there's a remote draft root that points to revision we know is public
					RevisionSet remoteDraftsLocallyPublic = remoteDrafts.subtract(presentSecret).subtract(presentDraft);
					if (!remoteDraftsLocallyPublic.isEmpty()) {
						// foreach remoteDraftsLocallyPublic.heads() do push Draft->Public
						for (Nodeid n : remoteDraftsLocallyPublic.heads(parentHelper)) {
							try {
								remoteRepo.updatePhase(HgPhase.Draft, HgPhase.Public, n);
							} catch (HgRemoteConnectionException ex) {
								implRepo.getLog().dump(getClass(), Severity.Error, ex, String.format("Failed to update phase of %s", n.shortNotation()));
							}
						}
						remoteDrafts = remoteDrafts.subtract(remoteDraftsLocallyPublic);
					}
					// revisions that cease to be secret (gonna become Public), e.g. someone else pushed them
					RevisionSet secretGone = presentSecret.intersect(remoteDrafts);
					// trace parents of these published secret revisions
					RevisionSet secretMadePublic = presentSecret.parentsOf(secretGone, parentHelper);
					secretLeft = presentSecret.subtract(secretGone).subtract(secretMadePublic);
					// same for drafts
					RevisionSet draftGone = presentDraft.intersect(remoteDrafts);
					RevisionSet draftMadePublic = presentDraft.parentsOf(draftGone, parentHelper);
					draftLeft = presentDraft.subtract(draftGone).subtract(draftMadePublic);
				}
				final RevisionSet newDraftRoots = draftLeft.roots(parentHelper);
				final RevisionSet newSecretRoots = secretLeft.roots(parentHelper);
				phaseHelper.updateRoots(newDraftRoots.asList(), newSecretRoots.asList());
			}
			progress.worked(5);
			//
			// update bookmark information
			HgBookmarks localBookmarks = repo.getBookmarks();
			if (!localBookmarks.getAllBookmarks().isEmpty()) {
				for (Pair<String,Nodeid> bm : remoteRepo.getBookmarks()) {
					Nodeid localRevision = localBookmarks.getRevision(bm.first());
					if (localRevision == null || !parentHelper.knownNode(bm.second())) {
						continue;
					}
					// we know both localRevision and revision of remote bookmark,
					// need to make sure we don't push  older revision than it's at the server
					if (parentHelper.isChild(bm.second(), localRevision)) {
						remoteRepo.updateBookmark(bm.first(), bm.second(), localRevision);
					}
				}
			}
			// XXX WTF is obsolete in namespaces key??
			progress.worked(5);
		} catch (IOException ex) {
			throw new HgIOException(ex.getMessage(), null); // XXX not a nice idea to throw IOException from BundleGenerator#create
		} catch (HgRepositoryNotFoundException ex) {
			final HgInvalidStateException e = new HgInvalidStateException("Failed to load a just-created bundle");
			e.initCause(ex);
			throw new HgLibraryFailureException(e);
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			progress.done();
		}
	}
	
	/*
	 * To test, start a server:
	 * $ hg --config web.allow_push=* --config web.push_ssl=False --config server.validate=True --debug serve
	 */
	public static void main(String[] args) throws Exception {
		final HgLookup hgLookup = new HgLookup();
		HgRepository r = hgLookup.detect("/home/artem/hg/test-phases/");
		HgRemoteRepository rr = hgLookup.detect(new URL("http://localhost:8000/"));
		new HgPushCommand(r).destination(rr).execute();
	}
}
