/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.core.Nodeid.NULL;
import static org.tmatesoft.hg.repo.HgRepositoryFiles.Branch;
import static org.tmatesoft.hg.repo.HgRepositoryFiles.Dirstate;
import static org.tmatesoft.hg.util.LogFacility.Severity.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDirstate;
import org.tmatesoft.hg.repo.HgDirstate.EntryKind;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.LogFacility.Severity;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;


/**
 * Parse dirstate file
 * 
 * @see http://mercurial.selenic.com/wiki/DirState
 * @see http://mercurial.selenic.com/wiki/FileFormats#dirstate
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class DirstateReader {
	// dirstate read code originally lived in org.tmatesoft.hg.repo.HgDirstate
	
	private final Internals repo;
	private final Path.Source pathPool;
	private Pair<Nodeid, Nodeid> parents;
	
	public DirstateReader(Internals hgRepo, Path.Source pathSource) {
		repo = hgRepo;
		pathPool = pathSource;
	}

	public void readInto(HgDirstate.Inspector target) throws HgInvalidControlFileException {
		EncodingHelper encodingHelper = repo.buildFileNameEncodingHelper();
		parents = new Pair<Nodeid,Nodeid>(Nodeid.NULL, Nodeid.NULL);
		File dirstateFile = getDirstateFile(repo);
		if (dirstateFile == null || !dirstateFile.exists()) {
			return;
		}
		DataAccess da = repo.getDataAccess().create(dirstateFile);
		try {
			if (da.isEmpty()) {
				return;
			}
			parents = internalReadParents(da);
			// hg init; hg up produces an empty repository where dirstate has parents (40 bytes) only
			while (!da.isEmpty()) {
				final byte state = da.readByte();
				final int fmode = da.readInt();
				final int size = da.readInt();
				final int time = da.readInt();
				final int nameLen = da.readInt();
				String fn1 = null, fn2 = null;
				byte[] name = new byte[nameLen];
				da.readBytes(name, 0, nameLen);
				for (int i = 0; i < nameLen; i++) {
					if (name[i] == 0) {
						fn1 = encodingHelper.fromDirstate(name, 0, i);
						fn2 = encodingHelper.fromDirstate(name, i+1, nameLen - i - 1);
						break;
					}
				}
				if (fn1 == null) {
					fn1 = encodingHelper.fromDirstate(name, 0, nameLen);
				}
				HgDirstate.Record r = new HgDirstate.Record(fmode, size, time, pathPool.path(fn1), fn2 == null ? null : pathPool.path(fn2));
				if (state == 'n') {
					target.next(EntryKind.Normal, r);
				} else if (state == 'a') {
					target.next(EntryKind.Added, r);
				} else if (state == 'r') {
					target.next(EntryKind.Removed, r);
				} else if (state == 'm') {
					target.next(EntryKind.Merged, r);
				} else {
					repo.getSessionContext().getLog().dump(getClass(), Severity.Warn, "Dirstate record for file %s (size: %d, tstamp:%d) has unknown state '%c'", r.name(), r.size(), r.modificationTime(), state);
				}
			}
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Dirstate read failed", ex, dirstateFile); 
		} finally {
			da.done();
		}
	}

	private static Pair<Nodeid, Nodeid> internalReadParents(DataAccess da) throws IOException {
		byte[] parents = new byte[40];
		da.readBytes(parents, 0, 40);
		Nodeid n1 = Nodeid.fromBinary(parents, 0);
		Nodeid n2 = Nodeid.fromBinary(parents, 20);
		parents = null;
		return new Pair<Nodeid, Nodeid>(n1, n2);
	}
	
	/**
	 * @return pair of working copy parents, with {@link Nodeid#NULL} for missing values.
	 */
	public Pair<Nodeid,Nodeid> parents() {
		assert parents != null; // instance not initialized with #read()
		return parents;
	}
	
	private static File getDirstateFile(Internals repo) {
		return repo.getFileFromRepoDir(Dirstate.getName());
	}

	/**
	 * @return pair of parents, both {@link Nodeid#NULL} if dirstate is not available
	 */
	public static Pair<Nodeid, Nodeid> readParents(Internals internalRepo) throws HgInvalidControlFileException {
		// do not read whole dirstate if all we need is WC parent information
		File dirstateFile = getDirstateFile(internalRepo);
		if (dirstateFile == null || !dirstateFile.exists()) {
			return new Pair<Nodeid,Nodeid>(NULL, NULL);
		}
		DataAccess da = internalRepo.getDataAccess().create(dirstateFile);
		try {
			if (da.isEmpty()) {
				return new Pair<Nodeid,Nodeid>(NULL, NULL);
			}
			return internalReadParents(da);
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Error reading working copy parents from dirstate", ex, dirstateFile);
		} finally {
			da.done();
		}
	}
	
	/**
	 * TODO [post-1.0] it's really not a proper place for the method, need WorkingCopyContainer or similar
	 * @return branch associated with the working directory
	 */
	public static String readBranch(Internals internalRepo) throws HgInvalidControlFileException {
		File branchFile = internalRepo.getRepositoryFile(Branch);
		String branch = HgRepository.DEFAULT_BRANCH_NAME;
		if (branchFile.exists()) {
			try {
				// branch file is UTF-8 encoded, see http://mercurial.selenic.com/wiki/EncodingStrategy#UTF-8_strings
				// shall not use system-default encoding (FileReader) when reading it!
				// Perhaps, shall use EncodingHelper.fromBranch and InputStream instead, for uniformity?
				// Since whole file is in UTF8, InputStreamReader is a convenience over InputStream,
				// which we use elsewhere (together with EncodingHelper) - other files are usually a mix of binary data 
				// and encoded text (hence, InputStreamReader with charset is not an option there)
				BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(branchFile), EncodingHelper.getUTF8()));
				String b = r.readLine();
				if (b != null) {
					b = b.trim().intern();
				}
				branch = b == null || b.length() == 0 ? HgRepository.DEFAULT_BRANCH_NAME : b;
				r.close();
			} catch (FileNotFoundException ex) {
				internalRepo.getSessionContext().getLog().dump(HgDirstate.class, Debug, ex, null); // log verbose debug, exception might be legal here 
				// IGNORE
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Error reading file with branch information", ex, branchFile);
			}
		}
		return branch;
	}
}
