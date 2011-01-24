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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.core;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.repo.HgRepository.WORKING_COPY;

import org.tmatesoft.hg.core.Path.Matcher;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.StatusCollector;
import org.tmatesoft.hg.repo.WorkingCopyStatusCollector;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StatusCommand {
	private final HgRepository repo;

	private boolean needModified;
	private boolean needAdded;
	private boolean needRemoved;
	private boolean needUnknown;
	private boolean needMissing;
	private boolean needClean;
	private boolean needIgnored;
	private Matcher matcher;
	private int startRevision = TIP;
	private int endRevision = WORKING_COPY; 
	private boolean visitSubRepo = true;

	public StatusCommand(HgRepository hgRepo) { 
		repo = hgRepo;
		defaults();
	}

	public StatusCommand defaults() {
		needModified = needAdded = needRemoved = needUnknown = needMissing = true;
		needClean = needIgnored = false;
		return this;
	}
	public StatusCommand all() {
		needModified = needAdded = needRemoved = needUnknown = needMissing = true;
		needClean = needIgnored = true;
		return this;
	}
	

	public StatusCommand modified(boolean include) {
		needModified = include;
		return this;
	}
	public StatusCommand added(boolean include) {
		needAdded = include;
		return this;
	}
	public StatusCommand removed(boolean include) {
		needRemoved = include;
		return this;
	}
	public StatusCommand deleted(boolean include) {
		needMissing = include;
		return this;
	}
	public StatusCommand unknown(boolean include) {
		needUnknown = include;
		return this;
	}
	public StatusCommand clean(boolean include) {
		needClean = include;
		return this;
	}
	public StatusCommand ignored(boolean include) {
		needIgnored = include;
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
	
	public StatusCommand match(Path.Matcher pathMatcher) {
		matcher = pathMatcher;
		return this;
	}

	public StatusCommand subrepo(boolean visit) {
		visitSubRepo  = visit;
		throw HgRepository.notImplemented();
	}
	
	public void execute(StatusCollector.Inspector inspector) {
		StatusCollector sc = new StatusCollector(repo); // TODO from CommandContext
//		StatusCollector.Record r = new StatusCollector.Record(); // XXX use own inspector not to collect entries that
		// are not interesting or do not match name
		if (endRevision == WORKING_COPY) {
			WorkingCopyStatusCollector wcsc = new WorkingCopyStatusCollector(repo);
			wcsc.setBaseRevisionCollector(sc);
			wcsc.walk(startRevision, inspector);
		} else {
			if (startRevision == TIP) {
				sc.change(endRevision, inspector);
			} else {
				sc.walk(startRevision, endRevision, inspector);
			}
		}
//		PathPool pathHelper = new PathPool(repo.getPathHelper()); // TODO from CommandContext
	}
}
