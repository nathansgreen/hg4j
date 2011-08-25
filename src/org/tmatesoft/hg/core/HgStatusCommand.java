/*
 * Copyright (c) 2011 TMate Software Ltd
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

import static org.tmatesoft.hg.core.HgStatus.Kind.*;
import static org.tmatesoft.hg.repo.HgInternals.wrongLocalRevision;
import static org.tmatesoft.hg.repo.HgRepository.*;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CancellationException;

import org.tmatesoft.hg.internal.ChangelogHelper;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgStatusInspector;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;
import org.tmatesoft.hg.util.Path;

/**
 * Command to obtain file status information, 'hg status' counterpart. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgStatusCommand extends HgAbstractCommand<HgStatusCommand> {
	private final HgRepository repo;

	private int startRevision = TIP;
	private int endRevision = WORKING_COPY;
	private Path.Matcher scope;
	
	private final Mediator mediator = new Mediator();

	public HgStatusCommand(HgRepository hgRepo) { 
		repo = hgRepo;
		defaults();
	}

	public HgStatusCommand defaults() {
		final Mediator m = mediator;
		m.needModified = m.needAdded = m.needRemoved = m.needUnknown = m.needMissing = true;
		m.needCopies = m.needClean = m.needIgnored = false;
		return this;
	}
	public HgStatusCommand all() {
		final Mediator m = mediator;
		m.needModified = m.needAdded = m.needRemoved = m.needUnknown = m.needMissing = true;
		m.needCopies = m.needClean = m.needIgnored = true;
		return this;
	}
	

	public HgStatusCommand modified(boolean include) {
		mediator.needModified = include;
		return this;
	}
	public HgStatusCommand added(boolean include) {
		mediator.needAdded = include;
		return this;
	}
	public HgStatusCommand removed(boolean include) {
		mediator.needRemoved = include;
		return this;
	}
	public HgStatusCommand deleted(boolean include) {
		mediator.needMissing = include;
		return this;
	}
	public HgStatusCommand unknown(boolean include) {
		mediator.needUnknown = include;
		return this;
	}
	public HgStatusCommand clean(boolean include) {
		mediator.needClean = include;
		return this;
	}
	public HgStatusCommand ignored(boolean include) {
		mediator.needIgnored = include;
		return this;
	}
	
	/**
	 * If set, either base:revision or base:workingdir
	 * to unset, pass {@link HgRepository#TIP} or {@link HgRepository#BAD_REVISION}
	 * @param revision - local revision number to base status from
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException when revision is negative or {@link HgRepository#WORKING_COPY} 
	 */
	public HgStatusCommand base(int revision) {
		if (revision == WORKING_COPY || wrongLocalRevision(revision)) {
			throw new IllegalArgumentException(String.valueOf(revision));
		}
		if (revision == BAD_REVISION) {
			revision = TIP;
		}
		startRevision = revision;
		return this;
	}
	
	/**
	 * Revision without base == --change
	 * Pass {@link HgRepository#WORKING_COPY} or {@link HgRepository#BAD_REVISION} to reset
	 * @param revision - non-negative local revision number, or any of {@link HgRepository#BAD_REVISION}, {@link HgRepository#WORKING_COPY} or {@link HgRepository#TIP}  
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException if local revision number doesn't specify legitimate revision. 
	 */
	public HgStatusCommand revision(int revision) {
		if (revision == BAD_REVISION) {
			revision = WORKING_COPY;
		}
		if (wrongLocalRevision(revision)) {
			throw new IllegalArgumentException(String.valueOf(revision));
		}
		endRevision = revision;
		return this;
	}
	
	/**
	 * Shorthand for {@link #base(int) cmd.base(BAD_REVISION)}{@link #change(int) .revision(revision)}
	 *  
	 * @param revision compare given revision against its parent
	 * @return <code>this</code> for convenience
	 */
	public HgStatusCommand change(int revision) {
		base(BAD_REVISION);
		return revision(revision);
	}
	
	/**
	 * Limit status operation to certain sub-tree.
	 * 
	 * @param pathMatcher - matcher to use,  pass <code>null/<code> to reset
	 * @return <code>this</code> for convenience
	 */
	public HgStatusCommand match(Path.Matcher scopeMatcher) {
		scope = scopeMatcher;
		return this;
	}

	public HgStatusCommand subrepo(boolean visit) {
		throw HgRepository.notImplemented();
	}

	/**
	 * Perform status operation according to parameters set.
	 *  
	 * @param handler callback to get status information
	 * @throws IllegalArgumentException if handler is <code>null</code>
	 * @throws ConcurrentModificationException if this command already runs (i.e. being used from another thread)
	 */
	public void execute(Handler statusHandler) throws CancellationException, HgException {
		if (statusHandler == null) {
			throw new IllegalArgumentException();
		}
		if (mediator.busy()) {
			throw new ConcurrentModificationException();
		}
		HgStatusCollector sc = new HgStatusCollector(repo); // TODO from CommandContext
//		PathPool pathHelper = new PathPool(repo.getPathHelper()); // TODO from CommandContext
		try {
			// XXX if I need a rough estimation (for ProgressMonitor) of number of work units,
			// I may use number of files in either rev1 or rev2 manifest edition
			mediator.start(statusHandler, new ChangelogHelper(repo, startRevision));
			if (endRevision == WORKING_COPY) {
				HgWorkingCopyStatusCollector wcsc = scope != null ? HgWorkingCopyStatusCollector.create(repo, scope) : new HgWorkingCopyStatusCollector(repo);
				wcsc.setBaseRevisionCollector(sc);
				wcsc.walk(startRevision, mediator);
			} else {
				sc.setScope(scope); // explicitly set, even if null - would be handy once we reuse StatusCollector
				if (startRevision == TIP) {
					sc.change(endRevision, mediator);
				} else {
					sc.walk(startRevision, endRevision, mediator);
				}
			}
		} finally {
			mediator.done();
		}
	}

	public interface Handler {
		void handleStatus(HgStatus s);
	}

	private class Mediator implements HgStatusInspector {
		boolean needModified;
		boolean needAdded;
		boolean needRemoved;
		boolean needUnknown;
		boolean needMissing;
		boolean needClean;
		boolean needIgnored;
		boolean needCopies;
		Handler handler;
		private ChangelogHelper logHelper;

		Mediator() {
		}
		
		public void start(Handler h, ChangelogHelper changelogHelper) {
			handler = h;
			logHelper = changelogHelper;
		}

		public void done() {
			handler = null;
			logHelper = null;
		}
		
		public boolean busy() {
			return handler != null;
		}

		public void modified(Path fname) {
			if (needModified) {
				handler.handleStatus(new HgStatus(Modified, fname, logHelper));
			}
		}
		public void added(Path fname) {
			if (needAdded) {
				handler.handleStatus(new HgStatus(Added, fname, logHelper));
			}
		}
		public void removed(Path fname) {
			if (needRemoved) {
				handler.handleStatus(new HgStatus(Removed, fname, logHelper));
			}
		}
		public void copied(Path fnameOrigin, Path fnameAdded) {
			if (needCopies) {
				// FIXME in fact, merged files may report 'copied from' as well, correct status kind thus may differ from Added
				handler.handleStatus(new HgStatus(Added, fnameAdded, fnameOrigin, logHelper));
			}
		}
		public void missing(Path fname) {
			if (needMissing) {
				handler.handleStatus(new HgStatus(Missing, fname, logHelper));
			}
		}
		public void unknown(Path fname) {
			if (needUnknown) {
				handler.handleStatus(new HgStatus(Unknown, fname, logHelper));
			}
		}
		public void clean(Path fname) {
			if (needClean) {
				handler.handleStatus(new HgStatus(Clean, fname, logHelper));
			}
		}
		public void ignored(Path fname) {
			if (needIgnored) {
				handler.handleStatus(new HgStatus(Ignored, fname, logHelper));
			}
		}
	}
}
