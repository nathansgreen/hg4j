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

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import org.tmatesoft.hg.core.HgRepositoryLockException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryLock;
import org.tmatesoft.hg.util.LogFacility;

/**
 * Helper to lock both storage and working directory
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class CompleteRepoLock {

	private final HgRepository repo;
	private HgRepositoryLock wdLock, storeLock;

	public CompleteRepoLock(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public void acquire() throws HgRepositoryLockException {
		wdLock = repo.getWorkingDirLock();
		storeLock = repo.getStoreLock();
		wdLock.acquire();
		try {
			storeLock.acquire();
		} catch (HgRepositoryLockException ex) {
			try {
				wdLock.release();
			} catch (HgRepositoryLockException e2) {
				final LogFacility log = repo.getSessionContext().getLog();
				log.dump(getClass(), Error, e2, "Nested exception ignored once failed to acquire store lock");
			}
			throw ex;
		}

	}
	
	public void release() throws HgRepositoryLockException {
		try {
			storeLock.release();
		} catch (HgRepositoryLockException ex) {
			try {
				wdLock.release();
			} catch (HgRepositoryLockException e2) {
				final LogFacility log = repo.getSessionContext().getLog();
				log.dump(getClass(), Error, e2, "Nested exception ignored when releasing working directory lock");
			}
			throw ex;
		}
		wdLock.release();
	}
}
