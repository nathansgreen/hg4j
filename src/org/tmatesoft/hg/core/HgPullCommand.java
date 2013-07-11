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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.internal.AddRevInspector;
import org.tmatesoft.hg.internal.COWTransaction;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.internal.Transaction;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * 'hg pull <remote>' counterpart, get remote changes to local repository
 * 
 * @since 1.2
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgPullCommand extends HgAbstractCommand<HgPullCommand> {

	private final HgRepository repo;
	private HgRemoteRepository remote;
	private RevisionSet added;

	public HgPullCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public HgPullCommand source(HgRemoteRepository hgRemote) {
		remote = hgRemote;
		return this;
	}

	public void execute() throws HgRemoteConnectionException, HgIOException, HgLibraryFailureException, CancelledException {
		final ProgressSupport progress = getProgressSupport(null);
		try {
			progress.start(100);
			// TODO refactor same code in HgIncomingCommand #getComparator and #getParentHelper
			final HgChangelog clog = repo.getChangelog();
			final HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(clog);
			parentHelper.init();
			final RepositoryComparator comparator = new RepositoryComparator(parentHelper, remote);
			// get incoming revisions
			comparator.compare(new ProgressSupport.Sub(progress, 50), getCancelSupport(null, true));
			final List<Nodeid> common = comparator.getCommon();
			// get bundle with changes from remote
			HgBundle incoming = remote.getChanges(common);
			//
			// add revisions to changelog, manifest, files
			final Internals implRepo = HgInternals.getImplementationRepo(repo);
			final AddRevInspector insp;
			Transaction.Factory trFactory = new COWTransaction.Factory();
			Transaction tr = trFactory.create(repo);
			try {
				incoming.inspectAll(insp = new AddRevInspector(implRepo, tr));
				insp.done();
				tr.commit();
			} catch (HgRuntimeException ex) {
				tr.rollback();
				throw ex;
			} catch (HgIOException ex) {
				tr.rollback();
				throw ex;
			} catch (RuntimeException ex) {
				tr.rollback();
				throw ex;
			}
			progress.worked(45);
			added = insp.addedChangesets();
			
			if (!added.isEmpty()) {
				// FIXME refresh parentHelper with newly added revisions in effective way
				parentHelper.init(); 
			}
			// get remote phases, update local phases to match that of remote
			// do not update any remote phase (it's pull, after all)
			final PhasesHelper phaseHelper = new PhasesHelper(implRepo, parentHelper);
			if (phaseHelper.isCapableOfPhases()) {
				RevisionSet rsCommon = new RevisionSet(common);
				HgRemoteRepository.Phases remotePhases = remote.getPhases();
				phaseHelper.synchronizeWithRemote(remotePhases, rsCommon.union(added));
			}
			progress.worked(5);
			incoming.unlink(); // keep the file only in case of failure
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			progress.done();
		}
	}
	
	public Collection<Nodeid> getPulledRevisions() {
		return added == null ? Collections.<Nodeid>emptyList() : added.asList();
	}
}
