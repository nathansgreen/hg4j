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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInvalidStateException;

/**
 * This transaction strategy makes a copy of original file and breaks origin hard links, if any.
 * Changes are directed to actual repository files.
 * 
 * On commit, remove all backup copies
 * On rollback, move all backup files in place of original
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class COWTransaction extends Transaction {
	
	private final FileUtils fileHelper;
	private final List<RollbackEntry> entries = new LinkedList<RollbackEntry>();
	
	public COWTransaction(SessionContext.Source ctxSource) {
		fileHelper = new FileUtils(ctxSource.getSessionContext().getLog(), this);
	}

	@Override
	public File prepare(File f) throws HgIOException {
		if (known(f)) {
			return f;
		}
		if (!f.exists()) {
			return recordNonExistent(f);
		}
		final File parentDir = f.getParentFile();
		assert parentDir.canWrite();
		File copy = new File(parentDir, f.getName() + ".hg4j.copy");
		fileHelper.copy(f, copy);
		final long lm = f.lastModified();
		copy.setLastModified(lm);
		File backup = new File(parentDir, f.getName() + ".hg4j.orig");
		if (backup.exists()) {
			backup.delete();
		}
		if (!f.renameTo(backup)) {
			throw new HgIOException(String.format("Failed to backup %s to %s", f.getName(), backup.getName()), backup);
		}
		if (!copy.renameTo(f)) {
			throw new HgIOException(String.format("Failed to bring on-write copy in place (%s to %s)", copy.getName(), f.getName()), copy);
		}
		f.setLastModified(lm);
		record(f, backup);
		return f;
	}

	@Override
	public File prepare(File origin, File backup) throws HgIOException {
		if (known(origin)) {
			return origin;
		}
		if (!origin.exists()) {
			return recordNonExistent(origin);
		}
		fileHelper.copy(origin, backup);
		final RollbackEntry e = record(origin, backup);
		e.keepBackup = true;
		return origin;
	}

	@Override
	public void done(File f) throws HgIOException {
		find(f).success = true;
	}

	@Override
	public void failure(File f, IOException ex) {
		find(f).failure = ex;
	}

	// XXX custom exception for commit and rollback to hold information about files rolled back
	
	@Override
	public void commit() throws HgIOException {
		for (Iterator<RollbackEntry> it = entries.iterator(); it.hasNext();) {
			RollbackEntry e = it.next();
			if (!e.success) {
				throw new HgInvalidStateException(String.format("Attempt to commit transaction without successful clearance of file %s", e.origin));
			}
			if (e.failure != null) {
				throw new HgIOException("Can't close transaction with a failure.", e.failure, e.origin);
			}
			if (!e.keepBackup && e.backup != null) {
				e.backup.delete();
			}
			it.remove();
		}
	}

	@Override
	public void rollback() throws HgIOException {
		LinkedList<RollbackEntry> success = new LinkedList<RollbackEntry>();
		for (Iterator<RollbackEntry> it = entries.iterator(); it.hasNext();) {
			RollbackEntry e = it.next();
			e.origin.delete();
			if (e.backup != null) {
				if (!e.backup.renameTo(e.origin)) {
					String msg = String.format("Transaction rollback failed, could not rename backup %s back to %s", e.backup.getName(), e.origin.getName());
					throw new HgIOException(msg, e.origin);
				}
				// renameTo() doesn't update timestamp, while the rest of the code relies
				// on file timestamp to detect revlog changes. Rollback *is* a change,
				// even if it brings the old state.
				e.origin.setLastModified(System.currentTimeMillis());
			}
			success.add(e);
			it.remove();
		}
	}

	private File recordNonExistent(File f) throws HgIOException {
		record(f, null);
		try {
			f.getParentFile().mkdirs();
			f.createNewFile();
			return f;
		} catch (IOException ex) {
			throw new HgIOException("Failed to create new file", ex, f);
		}
	}
	
	private RollbackEntry record(File origin, File backup) {
		final RollbackEntry e = new RollbackEntry(origin, backup);
		entries.add(e);
		return e;
	}

	private boolean known(File f) {
		RollbackEntry e = lookup(f);
		return e != null;
	}

	private RollbackEntry find(File f) {
		RollbackEntry e = lookup(f);
		if (e != null) {
			return e;
		}
		assert false;
		return new RollbackEntry(f,f);
	}
	
	private RollbackEntry lookup(File f) {
		for (RollbackEntry e : entries) {
			if (e.origin.equals(f)) {
				return e;
			}
		}
		return null;
	}
	
	private static class RollbackEntry {
		public final File origin;
		public final File backup; // may be null to indicate file didn't exist
		public boolean success = false;
		public IOException failure = null;
		public boolean keepBackup = false;
		
		public RollbackEntry(File o, File b) {
			origin = o;
			backup = b;
		}
	}
	
	public static class Factory implements Transaction.Factory {

		public Transaction create(SessionContext.Source ctxSource) {
			return new COWTransaction(ctxSource);
		}
		
	}
}
