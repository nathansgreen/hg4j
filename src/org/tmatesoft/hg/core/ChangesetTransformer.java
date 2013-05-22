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
package org.tmatesoft.hg.core;

import java.util.Set;

import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.LifecycleBridge;
import org.tmatesoft.hg.internal.PathPool;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.PathRewrite;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Bridges {@link HgChangelog.RawChangeset} with high-level {@link HgChangeset} API
 * TODO post-1.0 Move to .internal once access to package-local HgChangeset cons is resolved. For 1.0, enough it's package-local 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
/*package-local*/ class ChangesetTransformer implements HgChangelog.Inspector, Adaptable {
	private final HgChangesetHandler handler;
	private final LifecycleBridge lifecycleBridge;
	private final Transformation t;
	private Set<String> branches;
	private HgCallbackTargetException failure;

	// repo and delegate can't be null, parent walker can
	// ps and cs can't be null
	public ChangesetTransformer(HgRepository hgRepo, HgChangesetHandler delegate, HgParentChildMap<HgChangelog> pw, ProgressSupport ps, CancelSupport cs) {
		if (hgRepo == null || delegate == null) {
			throw new IllegalArgumentException();
		}
		if (ps == null || cs == null) {
			throw new IllegalArgumentException();
		}
		HgStatusCollector statusCollector = new HgStatusCollector(hgRepo);
		t = new Transformation(statusCollector, pw);
		handler = delegate;
		// lifecycleBridge takes care of progress and cancellation, plus
		// gives us explicit way to stop iteration (once HgCallbackTargetException) comes.
		lifecycleBridge = new LifecycleBridge(ps, cs);
	}
	
	public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) throws HgRuntimeException {
		if (branches != null && !branches.contains(cset.branch())) {
			return;
		}

		HgChangeset changeset = t.handle(revisionNumber, nodeid, cset);
		try {
			handler.cset(changeset);
			lifecycleBridge.nextStep();
		} catch (HgCallbackTargetException ex) {
			failure = ex.setRevision(nodeid).setRevisionIndex(revisionNumber);
			lifecycleBridge.stop();
		}
	}
	
	public void checkFailure() throws HgCallbackTargetException, CancelledException {
		if (failure != null) {
			HgCallbackTargetException toThrow = failure;
			throw toThrow;
		}
		if (lifecycleBridge.isCancelled()) {
			CancelledException toThrow = lifecycleBridge.getCancelOrigin();
			assert toThrow != null;
			throw toThrow;
		}
	}
	
	public void limitBranches(Set<String> branches) {
		this.branches = branches;
	}

	// part relevant to RawChangeset->HgChangeset transformation
	static class Transformation {
		private final HgChangeset changeset;

		public Transformation(HgStatusCollector statusCollector, HgParentChildMap<HgChangelog> pw) {
			// files listed in a changeset don't need their names to be rewritten (they are normalized already)
			// pp serves as a cache for all filenames encountered and as a source for Path listed in the changeset
			PathPool pp = new PathPool(new PathRewrite.Empty());
			statusCollector.setPathPool(pp);
			changeset = new HgChangeset(statusCollector, pp);
			changeset.setParentHelper(pw);
		}

		/**
		 * Callers shall not assume they get new HgChangeset instance each time, implementation may reuse instances.  
		 * @return hi-level changeset description
		 */
		HgChangeset handle(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
			changeset.init(revisionNumber, nodeid, cset);
			return changeset;
		}
	}

	public <T> T getAdapter(Class<T> adapterClass) {
		if (adapterClass == Lifecycle.class) {
			return adapterClass.cast(lifecycleBridge);
		}
		// just in case there are more adapters in future
		return Adaptable.Factory.getAdapter(handler, adapterClass, null);
	}
}