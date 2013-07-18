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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.core.HgBadArgumentException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * Common code to keep changelog revision and to perform boundary check.
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class CsetParamKeeper {
	private final HgRepository repo;
	private int changelogRevisionIndex = HgRepository.BAD_REVISION;
	
	public CsetParamKeeper(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	public CsetParamKeeper set(Nodeid changeset) throws HgBadArgumentException {
		try {
			set(repo.getChangelog().getRevisionIndex(changeset));
		} catch (HgInvalidRevisionException ex) {
			throw new HgBadArgumentException("Can't find revision", ex).setRevision(changeset);
		} catch (HgRuntimeException ex) {
			throw new HgBadArgumentException(String.format("Can't initialize with revision %s", changeset.shortNotation()), ex);
		}
		return this;
	}
	
	public CsetParamKeeper set(int changelogRevIndex) throws HgBadArgumentException {
		try {
			int lastCsetIndex = repo.getChangelog().getLastRevision();
			if (changelogRevIndex == HgRepository.TIP) {
				changelogRevIndex = lastCsetIndex;
			}
			if (changelogRevIndex < 0 || changelogRevIndex > lastCsetIndex) {
				throw new HgBadArgumentException(String.format("Bad revision index %d, value from [0..%d] expected", changelogRevIndex, lastCsetIndex), null).setRevisionIndex(changelogRevIndex);
			}
			doSet(changelogRevIndex);
		} catch (HgRuntimeException ex) {
			throw new HgBadArgumentException(String.format("Can't initialize with revision index %d", changelogRevIndex), ex);
		}
		return this;
	}
	
	public void doSet(int changelogRevIndex) {
		changelogRevisionIndex = changelogRevIndex;
	}

	/**
	 * @return the value set, or {@link HgRepository#BAD_REVISION} otherwise
	 */
	public int get() {
		return changelogRevisionIndex;
	}

	/**
	 * @param defaultRevisionIndex value to return when no revision was set, may be {@link HgRepository#TIP} which gets translated to real index if used
	 * @return changelog revision index if set, or defaultRevisionIndex value otherwise
	 */
	public int get(int defaultRevisionIndex) throws HgRuntimeException {
		// XXX perhaps, shall translate other predefined constants (like WORKING COPY) here, too (e.g. for HgRevertCommand)
		if (changelogRevisionIndex != BAD_REVISION && changelogRevisionIndex != TIP) {
			return changelogRevisionIndex;
		}
		if (changelogRevisionIndex == TIP || defaultRevisionIndex == TIP) {
			return repo.getChangelog().getLastRevision();
		}
		return defaultRevisionIndex;
	}
}
