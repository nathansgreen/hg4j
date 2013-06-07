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

import static org.tmatesoft.hg.internal.RequiresFile.*;

import java.io.File;

import org.tmatesoft.hg.internal.RepoInitializer;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelledException;

/**
 * Initialize empty local repository. 
 * <p>
 * Two predefined alternatives are available, {@link #revlogV0() old} and {@link #revlogV1() new} mercurial format respectively.
 * <p>
 * Specific requirements may be turned off/on as needed if you know what you're doing.
 * 
 * @see http://mercurial.selenic.com/wiki/RequiresFile
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgInitCommand extends HgAbstractCommand<HgInitCommand> {
	private static final int V1_DEFAULT = REVLOGV1 | STORE | FNCACHE | DOTENCODE;
	
	private final HgLookup hgLookup;
	private File location;
	private int requiresFlags;
	
	public HgInitCommand() {
		this(null);
	}

	public HgInitCommand(HgLookup lookupEnv) {
		hgLookup = lookupEnv;
		requiresFlags = V1_DEFAULT;
	}
	
	public HgInitCommand location(File repoLoc) {
		location = repoLoc;
		return this;
	}
	
	public HgInitCommand revlogV0() {
		requiresFlags = REVLOGV0;
		return this;
	}
	
	public HgInitCommand revlogV1() {
		requiresFlags = V1_DEFAULT;
		return this;
	}
	
	public HgInitCommand store(boolean enable) {
		return switchFlag(STORE, enable);
	}
	
	public HgInitCommand fncache(boolean enable) {
		return switchFlag(FNCACHE, enable);
	}
	
	public HgInitCommand dotencode(boolean enable) {
		return switchFlag(DOTENCODE, enable);
	}

	public HgRepository execute() throws HgRepositoryNotFoundException, HgException, CancelledException {
		if (location == null) {
			throw new IllegalArgumentException();
		}
		File repoDir;
		if (".hg".equals(location.getName())) {
			repoDir = location;
		} else {
			repoDir = new File(location, ".hg");
		}
		new RepoInitializer().setRequires(requiresFlags).initEmptyRepository(repoDir);
		return getNewRepository();
	}
	
	public HgRepository getNewRepository() throws HgRepositoryNotFoundException {
		HgLookup l = hgLookup == null ? new HgLookup() : hgLookup;
		return l.detect(location);
	}
	
	private HgInitCommand switchFlag(int flag, boolean enable) {
		if (enable) {
			requiresFlags |= flag;
		} else {
			requiresFlags &= ~flag;
		}
		return this;
	}
}
