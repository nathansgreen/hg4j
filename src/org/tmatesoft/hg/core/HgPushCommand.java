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
import java.util.List;

import org.tmatesoft.hg.internal.BundleGenerator;
import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

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
			final HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
			parentHelper.init();
			final RepositoryComparator comparator = new RepositoryComparator(parentHelper, remoteRepo);
			comparator.compare(new ProgressSupport.Sub(progress, 50), getCancelSupport(null, true));
			List<Nodeid> l = comparator.getLocalOnlyRevisions();
			//
			// prepare bundle
			BundleGenerator bg = new BundleGenerator(HgInternals.getImplementationRepo(repo));
			File bundleFile = bg.create(l);
			progress.worked(20);
			HgBundle b = new HgLookup(repo.getSessionContext()).loadBundle(bundleFile);
			//
			// send changes
			remoteRepo.unbundle(b, comparator.getRemoteHeads());
			progress.worked(20);
			//
			// FIXME update phase information
//			remote.listkeys("phases");
			progress.worked(5);
			//
			// FIXME update bookmark information
//			remote.listkeys("bookmarks");
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
		HgRepository r = hgLookup.detect("/home/artem/hg/junit-test-repos/log-1/");
		HgRemoteRepository rr = hgLookup.detect(new URL("http://localhost:8000/"));
		new HgPushCommand(r).destination(rr).execute();
	}
}
