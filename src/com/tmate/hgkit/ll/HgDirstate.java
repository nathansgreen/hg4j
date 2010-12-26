/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

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
	private List<Record> normal;
	private List<Record> added;
	private List<Record> removed;
	private List<Record> merged;

	public HgDirstate(LocalHgRepo hgRepo, File dirstate) {
		this.repo = hgRepo;
		this.dirstateFile = dirstate;
	}

	private void read() {
		normal = added = removed = merged = Collections.emptyList();
		if (!dirstateFile.exists()) {
			return;
		}
		DataAccessProvider dap = repo.getDataAccess();
		DataAccess da = dap.create(dirstateFile);
		if (da.isEmpty()) {
			return;
		}
		normal = new LinkedList<Record>();
		added = new LinkedList<Record>();
		removed = new LinkedList<Record>();
		merged = new LinkedList<Record>();
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
					normal.add(r);
				} else if (state == 'a') {
					added.add(r);
				} else if (state == 'r') {
					removed.add(r);
				} else if (state == 'm') {
					merged.add(r);
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

	public void dump() {
		read();
		@SuppressWarnings("unchecked")
		List<Record>[] all = new List[] { normal, added, removed, merged };
		char[] x = new char[] {'n', 'a', 'r', 'm' };
		for (int i = 0; i < all.length; i++) {
			for (Record r : all[i]) {
				System.out.printf("%c %3o%6d %30tc\t\t%s", x[i], r.mode, r.size, (long) r.time * 1000, r.name1);
				if (r.name2 != null) {
					System.out.printf(" --> %s", r.name2);
				}
				System.out.println();
			}
			System.out.println();
		}
	}
	
	private static class Record {
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
