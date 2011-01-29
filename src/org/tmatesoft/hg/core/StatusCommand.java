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

import static org.tmatesoft.hg.core.StatusCommand.HgStatus.Kind.*;
import static org.tmatesoft.hg.repo.HgRepository.*;

import java.util.ConcurrentModificationException;

import org.tmatesoft.hg.core.Path.Matcher;
import org.tmatesoft.hg.core.StatusCommand.HgStatus.Kind;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgStatusInspector;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StatusCommand {
	private final HgRepository repo;

	private int startRevision = TIP;
	private int endRevision = WORKING_COPY; 
	private boolean visitSubRepo = true;
	
	private Handler handler;
	private final Mediator mediator = new Mediator();

	public StatusCommand(HgRepository hgRepo) { 
		repo = hgRepo;
		defaults();
	}

	public StatusCommand defaults() {
		final Mediator m = mediator;
		m.needModified = m.needAdded = m.needRemoved = m.needUnknown = m.needMissing = true;
		m.needCopies = m.needClean = m.needIgnored = false;
		return this;
	}
	public StatusCommand all() {
		final Mediator m = mediator;
		m.needModified = m.needAdded = m.needRemoved = m.needUnknown = m.needMissing = true;
		m.needCopies = m.needClean = m.needIgnored = true;
		return this;
	}
	

	public StatusCommand modified(boolean include) {
		mediator.needModified = include;
		return this;
	}
	public StatusCommand added(boolean include) {
		mediator.needAdded = include;
		return this;
	}
	public StatusCommand removed(boolean include) {
		mediator.needRemoved = include;
		return this;
	}
	public StatusCommand deleted(boolean include) {
		mediator.needMissing = include;
		return this;
	}
	public StatusCommand unknown(boolean include) {
		mediator.needUnknown = include;
		return this;
	}
	public StatusCommand clean(boolean include) {
		mediator.needClean = include;
		return this;
	}
	public StatusCommand ignored(boolean include) {
		mediator.needIgnored = include;
		return this;
	}
	
	/**
	 * if set, either base:revision or base:workingdir
	 * to unset, pass {@link HgRepository#TIP} or {@link HgRepository#BAD_REVISION}
	 * @param revision
	 * @return
	 */
	
	public StatusCommand base(int revision) {
		if (revision == WORKING_COPY) {
			throw new IllegalArgumentException();
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
	 * @param revision
	 * @return
	 */
	public StatusCommand revision(int revision) {
		if (revision == BAD_REVISION) {
			revision = WORKING_COPY;
		}
		// XXX negative values, except for predefined constants, shall throw IAE.
		endRevision = revision;
		return this;
	}
	
	// pass null to reset
	public StatusCommand match(Path.Matcher pathMatcher) {
		mediator.matcher = pathMatcher;
		throw HgRepository.notImplemented();
	}

	public StatusCommand subrepo(boolean visit) {
		visitSubRepo  = visit;
		throw HgRepository.notImplemented();
	}

	/**
	 * Perform status operation according to parameters set.
	 *  
	 * @param handler callback to get status information
	 * @throws IllegalArgumentException if handler is <code>null</code>
	 * @throws ConcurrentModificationException if this command already runs (i.e. being used from another thread)
	 */
	public void execute(Handler statusHandler) {
		if (statusHandler == null) {
			throw new IllegalArgumentException();
		}
		if (handler != null) {
			throw new ConcurrentModificationException();
		}
		handler = statusHandler;
		HgStatusCollector sc = new HgStatusCollector(repo); // TODO from CommandContext
//		PathPool pathHelper = new PathPool(repo.getPathHelper()); // TODO from CommandContext
		try {
			// XXX if I need a rough estimation (for ProgressMonitor) of number of work units,
			// I may use number of files in either rev1 or rev2 manifest edition
			mediator.start();
			if (endRevision == WORKING_COPY) {
				HgWorkingCopyStatusCollector wcsc = new HgWorkingCopyStatusCollector(repo);
				wcsc.setBaseRevisionCollector(sc);
				wcsc.walk(startRevision, mediator);
			} else {
				if (startRevision == TIP) {
					sc.change(endRevision, mediator);
				} else {
					sc.walk(startRevision, endRevision, mediator);
				}
			}
		} finally {
			mediator.done();
			handler = null;
		}
	}

	public interface Handler {
		void handleStatus(HgStatus s);
	}

	public static class HgStatus {
		public enum Kind {
			Modified, Added, Removed, Unknown, Missing, Clean, Ignored
		};
		private final Kind kind;
		private final Path path;
		private final Path origin;
		
		HgStatus(Kind kind, Path path) {
			this(kind, path, null);
		}

		HgStatus(Kind kind, Path path, Path copyOrigin) {
			this.kind = kind;
			this.path  = path;
			origin = copyOrigin;
		}

		public Kind getKind() {
			return kind;
		}

		public Path getPath() {
			return path;
		}

		public Path getOriginalPath() {
			return origin;
		}

		public boolean isCopy() {
			return origin != null;
		}
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
		Matcher matcher;

		Mediator() {
		}
		
		public void start() {
			
		}
		public void done() {
		}

		public void modified(Path fname) {
			if (needModified) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Modified, fname));
				}
			}
		}
		public void added(Path fname) {
			if (needAdded) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Added, fname));
				}
			}
		}
		public void removed(Path fname) {
			if (needRemoved) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Removed, fname));
				}
			}
		}
		public void copied(Path fnameOrigin, Path fnameAdded) {
			if (needCopies) {
				if (matcher == null || matcher.accept(fnameAdded)) {
					handler.handleStatus(new HgStatus(Kind.Added, fnameAdded, fnameOrigin));
				}
			}
		}
		public void missing(Path fname) {
			if (needMissing) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Missing, fname));
				}
			}
		}
		public void unknown(Path fname) {
			if (needUnknown) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Unknown, fname));
				}
			}
		}
		public void clean(Path fname) {
			if (needClean) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Clean, fname));
				}
			}
		}
		public void ignored(Path fname) {
			if (needIgnored) {
				if (matcher == null || matcher.accept(fname)) {
					handler.handleStatus(new HgStatus(Ignored, fname));
				}
			}
		}
	}
}
