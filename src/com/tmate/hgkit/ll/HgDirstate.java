/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.tmate.hgkit.fs.DataAccess;
import com.tmate.hgkit.fs.DataAccessProvider;

/**
 * @see http://mercurial.selenic.com/wiki/DirState
 * @see http://mercurial.selenic.com/wiki/FileFormats#dirstate
 * @author artem
 */
public class HgDirstate {

	private final LocalHgRepo repo;
	private final File dirstateFile;
	private Map<String, Record> normal;
	private Map<String, Record> added;
	private Map<String, Record> removed;
	private Map<String, Record> merged;

	public HgDirstate(LocalHgRepo hgRepo, File dirstate) {
		this.repo = hgRepo;
		this.dirstateFile = dirstate;
	}

	private void read() {
		normal = added = removed = merged = Collections.<String, Record>emptyMap();
		if (!dirstateFile.exists()) {
			return;
		}
		DataAccessProvider dap = repo.getDataAccess();
		DataAccess da = dap.create(dirstateFile);
		if (da.isEmpty()) {
			return;
		}
		// not sure linked is really needed here, just for ease of debug
		normal = new LinkedHashMap<String, Record>();
		added = new LinkedHashMap<String, Record>();
		removed = new LinkedHashMap<String, Record>();
		merged = new LinkedHashMap<String, Record>();
		try {
			// XXX skip(40) if we don't need these? 
			byte[] parents = new byte[40];
			da.readBytes(parents, 0, 40);
			parents = null;
			do {
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
			} while (!da.isEmpty());
		} catch (IOException ex) {
			ex.printStackTrace(); // FIXME log error, clean dirstate?
		} finally {
			da.done();
		}
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
	
	/*package-local*/ Record checkNormal(String fname) {
		return normal.get(fname);
	}

	/*package-local*/ Record checkAdded(String fname) {
		return added.get(fname);
	}
	/*package-local*/ Record checkRemoved(String fname) {
		return removed.get(fname);
	}
	/*package-local*/ Record checkMerged(String fname) {
		return merged.get(fname);
	}




	public void dump() {
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
