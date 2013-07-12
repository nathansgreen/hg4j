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
import java.util.Collection;
import java.util.Collections;
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
import org.tmatesoft.hg.util.LogFacility.Severity;
import org.tmatesoft.hg.util.Outcome;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * 'hg push <remote>' counterpart, send local changes to a remote server
 * 
 * @since 1.2
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgPushCommand extends HgAbstractCommand<HgPushCommand> {
	
	private final HgRepository repo;
	private HgRemoteRepository remoteRepo;
	private RevisionSet outgoing;

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
			final Internals implRepo = HgInternals.getImplementationRepo(repo);
			final PhasesHelper phaseHelper = new PhasesHelper(implRepo, parentHelper);
			final RepositoryComparator comparator = new RepositoryComparator(parentHelper, remoteRepo);
			comparator.compare(new ProgressSupport.Sub(progress, 50), getCancelSupport(null, true));
			List<Nodeid> l = comparator.getLocalOnlyRevisions();
			if (phaseHelper.isCapableOfPhases() && phaseHelper.withSecretRoots()) {
				RevisionSet secret = phaseHelper.allSecret();
				outgoing = new RevisionSet(l).subtract(secret);
			} else {
				outgoing = new RevisionSet(l);
			}
			if (!outgoing.isEmpty()) {
				//
				// prepare bundle
				BundleGenerator bg = new BundleGenerator(implRepo);
				File bundleFile = bg.create(outgoing.asList());
				progress.worked(20);
				HgBundle b = new HgLookup(repo.getSessionContext()).loadBundle(bundleFile);
				//
				// send changes
				remoteRepo.unbundle(b, comparator.getRemoteHeads());
			} // update phase information nevertheless
			progress.worked(20);
			//
			// update phase information
			if (phaseHelper.isCapableOfPhases()) {
				HgRemoteRepository.Phases remotePhases = remoteRepo.getPhases();
				RevisionSet remoteDraftsLocalPublic = phaseHelper.synchronizeWithRemote(remotePhases, outgoing);
				if (!remoteDraftsLocalPublic.isEmpty()) {
					// foreach remoteDraftsLocallyPublic.heads() do push Draft->Public
					for (Nodeid n : remoteDraftsLocalPublic.heads(parentHelper)) {
						try {
							Outcome upo = remoteRepo.updatePhase(HgPhase.Draft, HgPhase.Public, n);
							if (!upo.isOk()) {
								implRepo.getLog().dump(getClass(), Severity.Info, "Failed to update remote phase, reason: %s", upo.getMessage());
							}
						} catch (HgRemoteConnectionException ex) {
							implRepo.getLog().dump(getClass(), Severity.Error, ex, String.format("Failed to update phase of %s", n.shortNotation()));
						}
					}
				}
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
			b.unlink(); // keep the file only in case of failure
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
	
	public Collection<Nodeid> getPushedRevisions() {
		return outgoing == null ? Collections.<Nodeid>emptyList() : outgoing.asList();
	}
}
