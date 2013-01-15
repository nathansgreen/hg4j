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
package org.tmatesoft.hg.repo;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;


/**
 * @see http://mercurial.selenic.com/wiki/DirState
 * @see http://mercurial.selenic.com/wiki/FileFormats#dirstate
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgDirstate /* XXX RepoChangeListener */{
	
	public enum EntryKind {
		Normal, Added, Removed, Merged, // order is being used in code of this class, don't change unless any use is checked
	}

	private final Internals repo;
	private final Path.Source pathPool;
	private final PathRewrite canonicalPathRewrite;
	private Map<Path, Record> normal;
	private Map<Path, Record> added;
	private Map<Path, Record> removed;
	private Map<Path, Record> merged;
	/* map of canonicalized file names to their originals from dirstate file.
	 * Note, only those canonical names that differ from their dirstate counterpart are recorded here
	 */
	private Map<Path, Path> canonical2dirstateName; 
	private Pair<Nodeid, Nodeid> parents;
	
	// canonicalPath may be null if we don't need to check for names other than in dirstate
	/*package-local*/ HgDirstate(Internals hgRepo, Path.Source pathSource, PathRewrite canonicalPath) {
		repo = hgRepo;
		pathPool = pathSource;
		canonicalPathRewrite = canonicalPath;
	}

	/*package-local*/ void read() throws HgInvalidControlFileException {
		normal = added = removed = merged = Collections.<Path, Record>emptyMap();
		parents = new Pair<Nodeid,Nodeid>(Nodeid.NULL, Nodeid.NULL);
		if (canonicalPathRewrite != null) {
			canonical2dirstateName = new HashMap<Path,Path>();
		} else {
			canonical2dirstateName = Collections.emptyMap();
		}
		// not sure linked is really needed here, just for ease of debug
		normal = new LinkedHashMap<Path, Record>();
		added = new LinkedHashMap<Path, Record>();
		removed = new LinkedHashMap<Path, Record>();
		merged = new LinkedHashMap<Path, Record>();

		DirstateReader dirstateReader = new DirstateReader(repo, pathPool);
		dirstateReader.readInto(new Inspector() {
			
			public boolean next(EntryKind kind, Record r) {
				if (canonicalPathRewrite != null) {
					Path canonicalPath = pathPool.path(canonicalPathRewrite.rewrite(r.name()));
					if (canonicalPath != r.name()) { // == as they come from the same pool
						assert !canonical2dirstateName.containsKey(canonicalPath); // otherwise there's already a file with same canonical name
						// which can't happen for case-insensitive file system (or there's erroneous PathRewrite, perhaps doing smth else)
						canonical2dirstateName.put(canonicalPath, r.name());
					}
					if (r.copySource() != null) {
						// not sure I need copy origin in the map, I don't seem to use it anywhere,
						// but I guess I'll have to use it some day.
						canonicalPath = pathPool.path(canonicalPathRewrite.rewrite(r.copySource()));
						if (canonicalPath != r.copySource()) {
							canonical2dirstateName.put(canonicalPath, r.copySource());
						}
					}
				}
				switch (kind) {
				case Normal : normal.put(r.name(), r); break;
				case Added :  added.put(r.name(), r); break;
				case Removed : removed.put(r.name(), r); break;
				case Merged : merged.put(r.name1, r); break;
				default: throw new HgInvalidStateException(String.format("Unexpected entry in the dirstate: %s", kind));
				}
				return true;
			}
		});
		parents = dirstateReader.parents();
	}

	/**
	 * @return pair of working copy parents, with {@link Nodeid#NULL} for missing values.
	 */
	public Pair<Nodeid,Nodeid> parents() {
		assert parents != null; // instance not initialized with #read()
		return parents;
	}
	
	// new, modifiable collection
	/*package-local*/ TreeSet<Path> all() {
		assert normal != null;
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
		return internalCheck(normal, fname);
	}

	/*package-local*/ Record checkAdded(Path fname) {
		return internalCheck(added, fname);
	}
	/*package-local*/ Record checkRemoved(Path fname) {
		return internalCheck(removed, fname);
	}
	/*package-local*/ Record checkMerged(Path fname) {
		return internalCheck(merged, fname);
	}

	
	// return non-null if fname is known, either as is, or its canonical form. in latter case, this canonical form is return value
	/*package-local*/ Path known(Path fname) {
		Path fnameCanonical = null;
		if (canonicalPathRewrite != null) {
			fnameCanonical = pathPool.path(canonicalPathRewrite.rewrite(fname).toString());
			if (fnameCanonical != fname && canonical2dirstateName.containsKey(fnameCanonical)) {
				// we know right away there's name in dirstate with alternative canonical form
				return canonical2dirstateName.get(fnameCanonical);
			}
		}
		@SuppressWarnings("unchecked")
		Map<Path, Record>[] all = new Map[] { normal, added, removed, merged };
		for (int i = 0; i < all.length; i++) {
			if (all[i].containsKey(fname)) {
				return fname;
			}
			if (fnameCanonical != null && all[i].containsKey(fnameCanonical)) {
				return fnameCanonical;
			}
		}
		return null;
	}

	private Record internalCheck(Map<Path, Record> map, Path fname) {
		Record rv = map.get(fname);
		if (rv != null || canonicalPathRewrite == null) {
			return rv;
		}
		Path fnameCanonical = pathPool.path(canonicalPathRewrite.rewrite(fname).toString());
		if (fnameCanonical != fname) {
			// case when fname = /a/B/c, and dirstate is /a/b/C 
			if (canonical2dirstateName.containsKey(fnameCanonical)) {
				return map.get(canonical2dirstateName.get(fnameCanonical));
			}
			// try canonical directly, fname = /a/B/C, dirstate has /a/b/c
			if ((rv = map.get(fnameCanonical)) != null) {
				return rv;
			}
		}
		return null;
	}

	public void walk(Inspector inspector) {
		assert normal != null;
		@SuppressWarnings("unchecked")
		Map<Path, Record>[] all = new Map[] { normal, added, removed, merged };
		for (int i = 0; i < all.length; i++) {
			EntryKind k = EntryKind.values()[i];
			for (Record r : all[i].values()) {
				if (!inspector.next(k, r)) {
					return;
				}
			}
		}
	}

	public interface Inspector {
		/**
		 * Invoked for each entry in the directory state file
		 * @param kind file record kind
		 * @param entry file record. Note, do not cache instance as it may be reused between the calls
		 * @return <code>true</code> to indicate further records are still of interest, <code>false</code> to stop iteration
		 */
		boolean next(EntryKind kind, Record entry);
	}

	public static final class Record implements Cloneable {
		private final int mode, size, time;
		// Dirstate keeps local file size (i.e. that with any filters already applied). 
		// Thus, can't compare directly to HgDataFile.length()
		private final Path name1, name2;

		public Record(int fmode, int fsize, int ftime, Path name1, Path name2) {
			mode = fmode;
			size = fsize;
			time = ftime;
			this.name1 = name1;
			this.name2 = name2;
			
		}

		public Path name() {
			return name1;
		}

		/**
		 * @return non-<code>null</code> for copy/move
		 */
		public Path copySource() {
			return name2;
		}

		public int modificationTime() {
			return time;
		}

		public int size() {
			return size;
		}
		
		public int mode() {
			return mode;
		}
		
		@Override
		public Record clone()  {
			try {
				return (Record) super.clone();
			} catch (CloneNotSupportedException ex) {
				throw new InternalError(ex.toString());
			}
		}
	}
}
