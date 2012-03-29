/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import java.util.Collection;
import java.util.TreeMap;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.util.Convertor;
import org.tmatesoft.hg.util.Path;

/**
 * Specific revision of the manifest. 
 * Note, suited to keep single revision only ({@link #changeset()}).
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class ManifestRevision implements HgManifest.Inspector {
	private final TreeMap<Path, Nodeid> idsMap;
	private final TreeMap<Path, HgManifest.Flags> flagsMap;
	private final Convertor<Nodeid> idsPool;
	private final Convertor<Path> namesPool;
	private Nodeid changeset;
	private int changelogRev; 

	// optional pools for effective management of nodeids and filenames (they are likely
	// to be duplicated among different manifest revisions
	public ManifestRevision(Pool<Nodeid> nodeidPool, Convertor<Path> filenamePool) {
		idsPool = nodeidPool;
		namesPool = filenamePool;
		idsMap = new TreeMap<Path, Nodeid>();
		flagsMap = new TreeMap<Path, HgManifest.Flags>();
	}
	
	public Collection<Path> files() {
		return idsMap.keySet();
	}

	public Nodeid nodeid(Path fname) {
		return idsMap.get(fname);
	}

	public HgManifest.Flags flags(Path fname) {
		HgManifest.Flags f = flagsMap.get(fname);
		return f == null ? HgManifest.Flags.RegularFile : f;
	}

	/**
	 * @return identifier of the changeset this manifest revision corresponds to.
	 */
	public Nodeid changeset() {
		return changeset;
	}
	
	public int changesetLocalRev() {
		return changelogRev;
	}
	
	//

	public boolean next(Nodeid nid, Path fname, HgManifest.Flags flags) {
		if (namesPool != null) {
			fname = namesPool.mangle(fname);
		}
		if (idsPool != null) {
			nid = idsPool.mangle(nid);
		}
		idsMap.put(fname, nid);
		if (flags != HgManifest.Flags.RegularFile) {
			// TreeMap$Entry takes 32 bytes. No reason to keep regular file attribute (in fact, no flags state) 
			// for such price
			// Alternatively, Map<Path, Pair<Nodeid, Flags>> might come as a solution
			// however, with low rate of elements with flags this would consume more memory
			// than two distinct maps (sizeof(Pair) == 16).  
			// Map<Pair>: n*(32+16)
			// 2 Maps:    n*32 + m*32  <-- consumes more with m>n/2 
			flagsMap.put(fname, flags);
		}
		return true;
	}

	public boolean end(int revision) {
		// in fact, this class cares about single revision
		return false; 
	}

	public boolean begin(int revision, Nodeid nid, int changelogRevision) {
		if (changeset != null) {
			idsMap.clear();
			flagsMap.clear();
		}
		changeset = nid;
		changelogRev = changelogRevision;
		return true;
	}
}