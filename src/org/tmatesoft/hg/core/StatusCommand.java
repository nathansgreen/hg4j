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

import org.tmatesoft.hg.core.Path.Matcher;

import com.tmate.hgkit.ll.HgRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StatusCommand {
	private final HgRepository repo;

	private boolean needClean = false;
	private boolean needIgnored = false;
	private Matcher matcher;
	private int startRevision;
	private Integer endRevision; // need three states, set, -1 or actual rev number
	private boolean visitSubRepo = true;

	public StatusCommand(HgRepository hgRepo) {
		this.repo = hgRepo;
	}

	public StatusCommand all() {
		needClean = true;
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
	
	// if set, either base:revision or base:workingdir
	public StatusCommand base(int revision) {
		startRevision = revision;
		return this;
	}
	
	// revision without base == --change
	public StatusCommand revision(int revision) {
		// XXX how to clear endRevision, if needed.
		// Perhaps, use of WC_REVISION or BAD_REVISION == -2 or Int.MIN_VALUE?
		endRevision = new Integer(revision);
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
	
	public void execute() {
		throw HgRepository.notImplemented();
	}
}
