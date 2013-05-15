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

import java.io.File;
import java.io.IOException;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInvalidStateException;

/**
 * Implementation strategies possible:<ul>
 * <li> Get a copy, write changes to origin, keep copy as backup till #commit
 *   <p>(-) doesn't break hard links 
 * <li> Get a copy, write changes to a copy, on commit rename copy to origin. 
 *   <p>(-) What if we read newly written data (won't find it);
 *   <p>(-) complex #commit
 *   <p>(+) simple rollback
 * <li> Get a copy, rename origin to backup (breaks hard links), rename copy to origin, write changes 
 *   <p>(+) Modified file is in place right away;
 *   <p>(+) easy #commit
 * <li> Do not copy, just record file size, truncate to that size on rollback
 * <li> ...?
 * </ul> 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public abstract class Transaction {
	/**
	 * Record the file is going to be modified during this transaction, obtain actual
	 * destination to write to.
	 */
	public abstract File prepare(File f) throws HgIOException;
	/**
	 * overwrites backup if exists, backup is kept after successful {@link #commit()}
	 */
	public abstract File prepare(File origin, File backup) throws HgIOException;
	/**
	 * Tell that file was successfully processed
	 */
	public abstract void done(File f) throws HgIOException;
	/**
	 * optional?
	 */
	public abstract void failure(File f, IOException ex);
	/**
	 * Complete the transaction
	 */
	public abstract void commit() throws HgIOException;
	/**
	 * Undo all the changes
	 */
	public abstract void rollback() throws HgIOException;

	public interface Factory {
		public Transaction create(SessionContext.Source ctxSource);
	}

	public static class NoRollback extends Transaction {

		@Override
		public File prepare(File f) throws HgIOException {
			return f;
		}

		@Override
		public File prepare(File origin, File backup) throws HgIOException {
			return origin;
		}

		@Override
		public void done(File f) throws HgIOException {
			// no-op
		}

		@Override
		public void failure(File f, IOException ex) {
			// no-op
		}

		@Override
		public void commit() throws HgIOException {
			// no-op
		}

		@Override
		public void rollback() throws HgIOException {
			throw new HgInvalidStateException("This transaction doesn't support rollback");
		}
	}
}
