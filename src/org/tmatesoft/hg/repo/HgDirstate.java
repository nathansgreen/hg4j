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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.util.Path;


/**
 * @see http://mercurial.selenic.com/wiki/DirState
 * @see http://mercurial.selenic.com/wiki/FileFormats#dirstate
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class HgDirstate {

	private final DataAccessProvider accessProvider;
	private final File dirstateFile;
	// deliberate String, not Path as it seems useless to keep Path here
	private Map<String, Record> normal;
	private Map<String, Record> added;
	private Map<String, Record> removed;
	private Map<String, Record> merged;
	private Nodeid p1, p2;

	/*package-local*/ HgDirstate() {
		// empty instance
		accessProvider = null;
		dirstateFile = null;
	}

	public HgDirstate(DataAccessProvider dap, File dirstate) {
		accessProvider = dap;
		dirstateFile = dirstate;
	}

	private void read() {
		normal = added = removed = merged = Collections.<String, Record>emptyMap();
		if (dirstateFile == null || !dirstateFile.exists()) {
			return;
		}
		DataAccess da = accessProvider.create(dirstateFile);
		if (da.isEmpty()) {
			return;
		}
		// not sure linked is really needed here, just for ease of debug
		normal = new LinkedHashMap<String, Record>();
		added = new LinkedHashMap<String, Record>();
		removed = new LinkedHashMap<String, Record>();
		merged = new LinkedHashMap<String, Record>();
		try {
			byte[] parents = new byte[40];
			da.readBytes(parents, 0, 40);
			p1 = Nodeid.fromBinary(parents, 0);
			p2 = Nodeid.fromBinary(parents, 20);
			parents = null;
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
				Record r = new Record(fmode, size, time, fn1, fn2);
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

	// do not read whole dirstate if all we need is WC parent information
	private void readParents() {
		if (dirstateFile == null || !dirstateFile.exists()) {
			return;
		}
		DataAccess da = accessProvider.create(dirstateFile);
		if (da.isEmpty()) {
			return;
		}
		try {
			byte[] parents = new byte[40];
			da.readBytes(parents, 0, 40);
			p1 = Nodeid.fromBinary(parents, 0);
			p2 = Nodeid.fromBinary(parents, 20);
			parents = null;
		} catch (IOException ex) {
			throw new HgBadStateException(ex); // XXX in fact, our exception is not the best solution here.
		} finally {
			da.done();
		}
	}
	
	/**
	 * @return array of length 2 with working copy parents, non null.
	 */
	public Nodeid[] parents() {
		if (p1 == null) {
			readParents();
		}
		Nodeid[] rv = new Nodeid[2];
		rv[0] = p1;
		rv[1] = p2;
		return rv;
	}

	// new, modifiable collection
	/*package-local*/ TreeSet<String> all() {
		read();
		TreeSet<String> rv = new TreeSet<String>();
		@SuppressWarnings("unchecked")
		Map<String, Record>[] all = new Map[] { normal, added, removed, merged };
		for (int i = 0; i < all.length; i++) {
			for (Record r : all[i].values()) {
				rv.add(r.name1);
			}
		}
		return rv;
	}
	
	/*package-local*/ Record checkNormal(Path fname) {
		return normal.get(fname.toString());
	}

	/*package-local*/ Record checkAdded(Path fname) {
		return added.get(fname.toString());
	}
	/*package-local*/ Record checkRemoved(Path fname) {
		return removed.get(fname.toString());
	}
	/*package-local*/ Record checkRemoved(String fname) {
		return removed.get(fname);
	}
	/*package-local*/ Record checkMerged(Path fname) {
		return merged.get(fname.toString());
	}




	/*package-local*/ void dump() {
		read();
		@SuppressWarnings("unchecked")
		Map<String, Record>[] all = new Map[] { normal, added, removed, merged };
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
		final int size;
		final int time;
		final String name1;
		final String name2;

		public Record(int fmode, int fsize, int ftime, String name1, String name2) {
			mode = fmode;
			size = fsize;
			time = ftime;
			this.name1 = name1;
			this.name2 = name2;
			
		}
	}
}
