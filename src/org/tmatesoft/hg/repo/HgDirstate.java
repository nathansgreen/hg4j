/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.core.Nodeid.NULL;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathPool;


/**
 * @see http://mercurial.selenic.com/wiki/DirState
 * @see http://mercurial.selenic.com/wiki/FileFormats#dirstate
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class HgDirstate /* XXX RepoChangeListener */{

	private final HgRepository repo;
	private final File dirstateFile;
	private final PathPool pathPool;
	private Map<Path, Record> normal;
	private Map<Path, Record> added;
	private Map<Path, Record> removed;
	private Map<Path, Record> merged;
	private Pair<Nodeid, Nodeid> parents;
	private String currentBranch;
	
	public HgDirstate(HgRepository hgRepo, File dirstate, PathPool pathPool) {
		repo = hgRepo;
		dirstateFile = dirstate; // XXX decide whether file names shall be kept local to reader (see #branches()) or passed from outside
		this.pathPool = pathPool;
	}

	private void read() {
		normal = added = removed = merged = Collections.<Path, Record>emptyMap();
		if (dirstateFile == null || !dirstateFile.exists()) {
			return;
		}
		DataAccess da = repo.getDataAccess().create(dirstateFile);
		if (da.isEmpty()) {
			return;
		}
		// not sure linked is really needed here, just for ease of debug
		normal = new LinkedHashMap<Path, Record>();
		added = new LinkedHashMap<Path, Record>();
		removed = new LinkedHashMap<Path, Record>();
		merged = new LinkedHashMap<Path, Record>();
		try {
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
						fn1 = new String(name, 0, i, "UTF-8"); // XXX unclear from documentation what encoding is used there
						fn2 = new String(name, i+1, nameLen - i - 1, "UTF-8"); // need to check with different system codepages
						break;
					}
				}
				if (fn1 == null) {
					fn1 = new String(name);
				}
				Record r = new Record(fmode, size, time, pathPool.path(fn1), fn2 == null ? null : pathPool.path(fn2));
				if (state == 'n') {
					normal.put(r.name1, r);
				} else if (state == 'a') {
					added.put(r.name1, r);
				} else if (state == 'r') {
					removed.put(r.name1, r);
				} else if (state == 'm') {
					merged.put(r.name1, r);
				} else {
					// FIXME log error?
				}
			}
		} catch (IOException ex) {
			ex.printStackTrace(); // FIXME log error, clean dirstate?
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
	 * @return array of length 2 with working copy parents, non null.
	 */
	public Pair<Nodeid,Nodeid> parents() {
		if (parents == null) {
			parents = readParents(repo, dirstateFile);
		}
		return parents;
	}
	
	/**
	 * @return pair of parents, both {@link Nodeid#NULL} if dirstate is not available
	 */
	public static Pair<Nodeid, Nodeid> readParents(HgRepository repo, File dirstateFile) {
		// do not read whole dirstate if all we need is WC parent information
		if (dirstateFile == null || !dirstateFile.exists()) {
			return new Pair<Nodeid,Nodeid>(NULL, NULL);
		}
		DataAccess da = repo.getDataAccess().create(dirstateFile);
		if (da.isEmpty()) {
			return new Pair<Nodeid,Nodeid>(NULL, NULL);
		}
		try {
			return internalReadParents(da);
		} catch (IOException ex) {
			throw new HgBadStateException(ex); // XXX in fact, our exception is not the best solution here.
		} finally {
			da.done();
		}
	}
	
	/**
	 * XXX is it really proper place for the method?
	 * @return branch associated with the working directory
	 */
	public String branch() {
		if (currentBranch == null) {
			currentBranch = readBranch(repo);
		}
		return currentBranch;
	}
	
	/**
	 * XXX is it really proper place for the method?
	 * @return branch associated with the working directory
	 */
	public static String readBranch(HgRepository repo) {
		String branch = HgRepository.DEFAULT_BRANCH_NAME;
		File branchFile = new File(repo.getRepositoryRoot(), "branch");
		if (branchFile.exists()) {
			try {
				BufferedReader r = new BufferedReader(new FileReader(branchFile));
				String b = r.readLine();
				if (b != null) {
					b = b.trim().intern();
				}
				branch = b == null || b.length() == 0 ? HgRepository.DEFAULT_BRANCH_NAME : b;
				r.close();
			} catch (IOException ex) {
				ex.printStackTrace(); // XXX log verbose debug, exception might be legal here (i.e. FileNotFound)
				// IGNORE
			}
		}
		return branch;
	}

	// new, modifiable collection
	/*package-local*/ TreeSet<Path> all() {
		read();
		TreeSet<Path> rv = new TreeSet<Path>();
		@SuppressWarnings("unchecked")
		Map<Path, Record>[] all = new Map[] { normal, added, removed, merged };
		for (int i = 0; i < all.length; i++) {
			for (Record r : all[i].values()) {
				rv.add(r.name1);
			}
		}
		return rv;
	}
	
	/*package-local*/ Record checkNormal(Path fname) {
		return normal.get(fname);
	}

	/*package-local*/ Record checkAdded(Path fname) {
		return added.get(fname);
	}
	/*package-local*/ Record checkRemoved(Path fname) {
		return removed.get(fname);
	}
	/*package-local*/ Record checkMerged(Path fname) {
		return merged.get(fname);
	}




	/*package-local*/ void dump() {
		read();
		@SuppressWarnings("unchecked")
		Map<Path, Record>[] all = new Map[] { normal, added, removed, merged };
		char[] x = new char[] {'n', 'a', 'r', 'm' };
		for (int i = 0; i < all.length; i++) {
			for (Record r : all[i].values()) {
				System.out.printf("%c %3o%6d %30tc\t\t%s", x[i], r.mode, r.size, (long) r.time * 1000, r.name1);
				if (r.name2 != null) {
					System.out.printf(" --> %s", r.name2);
				}
				System.out.println();
			}
			System.out.println();
		}
	}
	
	/*package-local*/ static class Record {
		final int mode;
		// it seems Dirstate keeps local file size (i.e. that with any filters already applied). 
		// Thus, can't compare directly to HgDataFile.length()
		final int size; 
		final int time;
		final Path name1;
		final Path name2;

		public Record(int fmode, int fsize, int ftime, Path name1, Path name2) {
			mode = fmode;
			size = fsize;
			time = ftime;
			this.name1 = name1;
			this.name2 = name2;
			
		}
	}
}
