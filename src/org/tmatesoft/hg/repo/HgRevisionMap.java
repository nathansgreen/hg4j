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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Arrays;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.repo.Revlog.RevisionInspector;

/**
 * Effective int to Nodeid and vice versa translation. It's advised to use this class instead of 
 * multiple {@link Revlog#getRevisionIndex(Nodeid)} calls. Rule of thumb is 20+ calls (given 
 * initialization costs). It's also important to take into account memory consumption, for huge
 * repositories use of this class may pay off only when accessing greatest fraction of all revisions.  
 * 
 * <p>Next code snippet shows instantiation and sample use: 
 * <pre>
 *   RevisionMap<HgChangelog> clogMap = new RevisionMap<HgChangelog>(clog).init();
 *   RevisionMap<HgDataFile> fileMap = new RevisionMap<HgDataFile>(fileNode).init();
 *   
 *   int fileRevIndex = 0;
 *   Nodeid fileRev = fileMap.revision(fileRevIndex);
 *   int csetRevIndex = fileNode.getChangesetRevisionIndex(fileRevIndex);
 *   Nodeid csetRev = clogMap.revision(localCset);
 *   changesetToNodeidMap.put(csetRev, fileRev);
 * </pre>
 * 
 * <p>
 * {@link Revlog#getRevisionIndex(Nodeid)} with straightforward lookup approach performs O(n/2)
 * <p>
 * {@link HgRevisionMap#revisionIndex(Nodeid)} is log(n), plus initialization is O(n) (just once).
 * 
 * @see HgParentChildMap
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgRevisionMap<T extends Revlog> implements RevisionInspector {
	/*
	 * in fact, initialization is much slower as it instantiates Nodeids, while #getRevisionIndex
	 * compares directly against byte buffer. Measuring cpython with 70k+ gives 3 times difference (47 vs 171)
	 * for complete changelog iteration. 
	 */
	
	/*
	 * XXX 3 * (x * 4) bytes. Can I do better?
	 * It seems, yes. Don't need to keep sorted, always can emulate it with indirect access to sequential through sorted2natural.
	 * i.e. instead sorted[mid].compareTo(toFind), do sequential[sorted2natural[mid]].compareTo(toFind) 
	 */
	private Nodeid[] sequential; // natural repository order, childrenOf rely on ordering
	private Nodeid[] sorted; // for binary search
	private int[] sorted2natural;
	private final T revlog;

	public HgRevisionMap(T owner) {
		revlog = owner;
	}
	
	public HgRepository getRepo() {
		return revlog.getRepo();
	}
	
	public void next(int revisionIndex, Nodeid revision, int linkedRevision) {
		sequential[revisionIndex] = sorted[revisionIndex] = revision;
	}

	/**
	 * @return <code>this</code> for convenience.
	 */
	public HgRevisionMap<T> init(/*XXX Pool<Nodeid> to reuse nodeids, if possible. */) throws HgInvalidControlFileException{
		// XXX HgRepository.register((RepoChangeListener) this); // listen to changes in repo, re-init if needed?
		final int revisionCount = revlog.getRevisionCount();
		sequential = new Nodeid[revisionCount];
		sorted = new Nodeid[revisionCount];
		revlog.indexWalk(0, TIP, this);
		// next is alternative to Arrays.sort(sorted), and build sorted2natural looking up each element of sequential in sorted.
		// the way sorted2natural was build is O(n*log n).  
		final ArrayHelper ah = new ArrayHelper();
		ah.sort(sorted);
		// note, values in ArrayHelper#getReversed are 1-based indexes, not 0-based 
		sorted2natural = ah.getReverse();
		return this;
	}
	
	public Nodeid revision(int revisionIndex) {
		return sequential[revisionIndex];
	}
	public int revisionIndex(Nodeid revision) {
		if (revision == null || revision.isNull()) {
			return BAD_REVISION;
		}
		int x = Arrays.binarySearch(sorted, revision);
		if (x < 0) {
			return BAD_REVISION;
		}
		return sorted2natural[x]-1;
	}
}