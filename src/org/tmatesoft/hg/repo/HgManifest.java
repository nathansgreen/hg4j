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

import static org.tmatesoft.hg.core.Nodeid.NULL;
import static org.tmatesoft.hg.repo.HgRepository.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.Info;

import java.io.IOException;
import java.util.Arrays;

import org.tmatesoft.hg.core.HgChangesetFileSneaker;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteVector;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.EncodingHelper;
import org.tmatesoft.hg.internal.IdentityPool;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.internal.IterateControlMediator;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.RevisionLookup;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.LogFacility.Severity;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;


/**
 * Representation of Mercurial manifest file (list of file names and their revisions in a particular changeset)
 * 
 * @see http://mercurial.selenic.com/wiki/Manifest
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgManifest extends Revlog {
	private RevisionMapper revisionMap;
	private final EncodingHelper encodingHelper;
	private final Path.Source pathFactory; 
	private final RevlogStream.Observer revisionMapCleaner = new RevlogStream.Observer() {
		public void reloaded(RevlogStream src) {
			revisionMap = null;
			// TODO RevlogDerivedCache<T> class, to wrap revisionMap and super.revisionLookup
			// and their respective cleanup observers, or any other all-in-one alternative
			// not to keep both field and it's cleaner
		}
	};
	
	/**
	 * File flags recorded in manifest
	 */
	public enum Flags {
		/**
		 * Executable bit set
		 */
		Exec,
		/**
		 * Symbolic link
		 */
		Link,
		/**
		 * Regular file
		 */
		RegularFile; 
		
		static Flags parse(String flags) {
			if ("x".equalsIgnoreCase(flags)) {
				return Exec;
			}
			if ("l".equalsIgnoreCase(flags)) {
				return Link;
			}
			if (flags == null) {
				return RegularFile;
			}
			throw new IllegalStateException(flags);
		}

		static Flags parse(byte[] data, int start, int length) {
			if (length == 0) {
				return RegularFile;
			}
			if (length == 1) {
				if (data[start] == 'x') {
					return Exec;
				}
				if (data[start] == 'l') {
					return Link;
				}
				// FALL THROUGH
			}
			throw new IllegalStateException(new String(data, start, length));
		}
		
		static Flags parse(int dirstateFileMode) {
			// source/include/linux/stat.h
			final int S_IFLNK = 0120000, S_IXUSR = 00100;
			if ((dirstateFileMode & S_IFLNK) == S_IFLNK) {
				return Link;
			}
			if ((dirstateFileMode & S_IXUSR) == S_IXUSR) {
				return Exec;
			}
			return RegularFile;
		}
		
		String nativeString() {
			if (this == Exec) {
				return "x";
			}
			if (this == Link) {
				return "l";
			}
			if (this == RegularFile) {
				return "";
			}
			throw new IllegalStateException(toString());
		}
		
		public int fsMode() {
			if (this == Exec) {
				return 0755;
			}
			return 0644;
		}
	}

	/*package-local*/ HgManifest(HgRepository hgRepo, RevlogStream content, EncodingHelper eh) {
		super(hgRepo, content, true);
		encodingHelper = eh;
		pathFactory = hgRepo.getSessionContext().getPathFactory();
	}

	/**
	 * Walks manifest revisions that correspond to specified range of changesets. The order in which manifest versions get reported
	 * to the inspector corresponds to physical order of manifest revisions, not that of changesets (with few exceptions as noted below).
	 * That is, for cset-manifest revision pairs:
	 * <pre>
	 *   3  8
	 *   4  7
	 *   5  9
	 * </pre>
	 * call <code>walk(3,5, insp)</code> would yield (4,7), (3,8) and (5,9) to the inspector; 
	 * different order of arguments, <code>walk(5, 3, insp)</code>, makes no difference.
	 * 
	 * <p>Physical layout of mercurial files (revlog) doesn't impose any restriction on whether manifest and changeset revisions shall go 
	 * incrementally, nor it mandates presence of manifest version for a changeset. Thus, there might be changesets that record {@link Nodeid#NULL}
	 * as corresponding manifest revision. This situation is deemed exceptional now and what would <code>inspector</code> get depends on whether 
	 * <code>start</code> or <code>end</code> arguments point to such changeset, or such changeset happen to be somewhere inside the range 
	 * <code>[start..end]</code>. Implementation does it best to report empty manifests 
	 * (<code>Inspector.begin(HgRepository.NO_REVISION, NULL, csetRevIndex);</code>
	 * followed immediately by <code>Inspector.end(HgRepository.NO_REVISION)</code> 
	 * when <code>start</code> and/or <code>end</code> point to changeset with no associated 
	 * manifest revision. However, if changeset-manifest revision pairs look like:
	 * <pre>
	 *   3  8
	 *   4  -1 (cset records null revision for manifest)
	 *   5  9
	 * </pre>
	 * call <code>walk(3,5, insp)</code> would yield only (3,8) and (5,9) to the inspector, without additional empty 
	 * <code>Inspector.begin(); Inspector.end()</code> call pair.   
	 * 
	 * @see HgRepository#NO_REVISION
	 * @param start changelog (not manifest!) revision to begin with
	 * @param end changelog (not manifest!) revision to stop, inclusive.
	 * @param inspector manifest revision visitor, can't be <code>null</code>
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 * @throws IllegalArgumentException if inspector callback is <code>null</code>
	 */
	public void walk(int start, int end, final Inspector inspector) throws HgRuntimeException, IllegalArgumentException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		final int csetFirst = start <= end ? start : end, csetLast = start > end ? start : end;
		int manifestFirst, manifestLast, i = 0;
		do {
			manifestFirst = fromChangelog(csetFirst+i);
			if (manifestFirst == BAD_REVISION) {
				inspector.begin(NO_REVISION, NULL, csetFirst+i);
				inspector.end(NO_REVISION);
			}
			i++;
		} while (manifestFirst == BAD_REVISION && csetFirst+i <= csetLast);
		if (manifestFirst == BAD_REVISION) {
			getRepo().getSessionContext().getLog().dump(getClass(), Info, "None of changesets [%d..%d] have associated manifest revision", csetFirst, csetLast);
			// we ran through all revisions in [start..end] and none of them had manifest.
			// we reported that to inspector and proceeding is done now.
			return;
		}
		i = 0;
		do {
			manifestLast = fromChangelog(csetLast-i);
			if (manifestLast == BAD_REVISION) {
				inspector.begin(NO_REVISION, NULL, csetLast-i);
				inspector.end(NO_REVISION);
			}
			i++;
		} while (manifestLast == BAD_REVISION && csetLast-i >= csetFirst);
		if (manifestLast == BAD_REVISION) {
			// hmm, manifestFirst != BAD_REVISION here, hence there's i from [csetFirst..csetLast] for which manifest entry exists, 
			// and thus it's impossible to run into manifestLast == BAD_REVISION. Nevertheless, never hurts to check.
			throw new HgInvalidStateException(String.format("Manifest %d-%d(!) for cset range [%d..%d] ", manifestFirst, manifestLast, csetFirst, csetLast));
		}
		if (manifestLast < manifestFirst) {
			// there are tool-constructed repositories that got order of changeset revisions completely different from that of manifest
			int x = manifestLast;
			manifestLast = manifestFirst;
			manifestFirst = x;
		}
		content.iterate(manifestFirst, manifestLast, true, new ManifestParser(inspector));
	}
	
	/**
	 * "Sparse" iteration of the manifest, more effective than accessing revisions one by one.
	 * <p> Inspector is invoked for each changeset revision supplied, even when there's no manifest
	 * revision associated with a changeset (@see {@link #walk(int, int, Inspector)} for more details when it happens). Order inspector
	 * gets invoked doesn't resemble order of changeset revisions supplied, manifest revisions are reported in the order they appear 
	 * in manifest revlog (with exception of changesets with missing manifest that may be reported in any order).   
	 * 
	 * @param inspector manifest revision visitor, can't be <code>null</code>
	 * @param revisionIndexes local indexes of changesets to visit, non-<code>null</code>
	 * @throws HgInvalidRevisionException if method argument specifies non-existent revision index. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 * @throws InvalidArgumentException if supplied arguments are <code>null</code>s
	 */
	public void walk(final Inspector inspector, int... revisionIndexes) throws HgRuntimeException, IllegalArgumentException {
		if (inspector == null || revisionIndexes == null) {
			throw new IllegalArgumentException();
		}
		int[] manifestRevs = toManifestRevisionIndexes(revisionIndexes, inspector);
		content.iterate(manifestRevs, true, new ManifestParser(inspector));
	}
	
	// 
	/**
	 * Tells manifest revision number that corresponds to the given changeset. May return {@link HgRepository#BAD_REVISION} 
	 * if changeset has no associated manifest (cset records NULL nodeid for manifest).
	 * @return manifest revision index, non-negative, or {@link HgRepository#BAD_REVISION}.
	 * @throws HgInvalidRevisionException if method argument specifies non-existent revision index. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	/*package-local*/ int fromChangelog(int changesetRevisionIndex) throws HgRuntimeException {
		if (HgInternals.wrongRevisionIndex(changesetRevisionIndex)) {
			throw new HgInvalidRevisionException(changesetRevisionIndex);
		}
		if (changesetRevisionIndex == HgRepository.WORKING_COPY || changesetRevisionIndex == HgRepository.BAD_REVISION) {
			throw new HgInvalidRevisionException("Can't use constants like WORKING_COPY or BAD_REVISION", null, changesetRevisionIndex);
		}
		// revisionNumber == TIP is processed by RevisionMapper 
		if (revisionMap == null || content.shallDropDerivedCaches()) {
			content.detach(revisionMapCleaner);
			final boolean buildOwnLookup = super.revisionLookup == null;
			RevisionMapper rmap = new RevisionMapper(buildOwnLookup);
			content.iterate(0, TIP, false, rmap);
			rmap.fixReusedManifests();
			if (buildOwnLookup && super.useRevisionLookup) {
				// reuse RevisionLookup if there's none yet
				super.setRevisionLookup(rmap.manifestNodeids);
			}
			rmap.manifestNodeids = null;
			revisionMap = rmap;
			// although in most cases modified manifest is accessed through one of the methods in this class
			// and hence won't have a chance till this moment to be reloaded via revisionMapCleaner
			// (RevlogStream sends events on attempt to read revlog, and so far we haven't tried to read anything,
			// it's still reasonable to have this cleaner attached, just in case any method from Revlog base class
			// has been called (e.g. getLastRevision())
			content.attach(revisionMapCleaner);
		}
		return revisionMap.at(changesetRevisionIndex);
	}
	
	/**
	 * Extracts file revision as it was known at the time of given changeset.
	 * <p>For more thorough details about file at specific changeset, use {@link HgChangesetFileSneaker}.
	 * <p>To visit few changesets for the same file, use {@link #walkFileRevisions(Path, Inspector, int...)}
	 * 
	 * @see #walkFileRevisions(Path, Inspector, int...)
	 * @see HgChangesetFileSneaker
	 * @param changelogRevisionIndex local changeset index 
	 * @param file path to file in question
	 * @return file revision or <code>null</code> if manifest at specified revision doesn't list such file
	 * @throws HgInvalidRevisionException if supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public Nodeid getFileRevision(int changelogRevisionIndex, final Path file) throws HgRuntimeException {
		// there's no need for HgDataFile to own this method, or get a delegate
		// as most of HgDataFile API is using file revision indexes, and there's easy step from file revision index to
		// both file revision and changeset revision index. But there's no easy way to go from changesetRevisionIndex to
		// file revision (the task this method solves), except for HgFileInformer
		// I feel methods dealing with changeset indexes shall be more exposed in HgChangelog and HgManifest API.
		// TODO need tests (e.g. pass TIP here to see resMap.get(-1) doesn't fail)
		int manifestRevIndex = fromChangelog(changelogRevisionIndex);
		if (manifestRevIndex == BAD_REVISION) {
			return null;
		}
		IntMap<Nodeid> resMap = new IntMap<Nodeid>(3);
		FileLookupInspector parser = new FileLookupInspector(encodingHelper, file, resMap, null);
		parser.walk(manifestRevIndex, content);
		assert resMap.size() <= 1; // size() == 0 if file wasn't found
		// can't use changelogRevisionIndex as key - it might have been TIP
		return resMap.size() == 0 ? null : resMap.get(resMap.firstKey());
	}
	
	/**
	 * Visit file revisions as they were recorded at the time of given changesets. Same file revision may be reported as many times as 
	 * there are changesets that refer to that revision. Both {@link Inspector#begin(int, Nodeid, int)} and {@link Inspector#end(int)}
	 * with appropriate values are invoked around {@link Inspector#next(Nodeid, Path, Flags)} call for the supplied file
	 * 
	 * <p>NOTE, this method doesn't respect return values from callback (i.e. to stop iteration), as it's lookup of a single file
	 * and canceling it seems superfluous. However, this may change in future and it's recommended to return <code>true</code> from
	 * all {@link Inspector} methods. 
	 * 
	 * @see #getFileRevision(int, Path)
	 * @param file path of interest
	 * @param inspector callback to receive details about selected file
	 * @param changelogRevisionIndexes changeset indexes to visit
	 * @throws HgInvalidRevisionException if supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void walkFileRevisions(Path file, Inspector inspector, int... changelogRevisionIndexes) throws HgRuntimeException {
		if (file == null || inspector == null || changelogRevisionIndexes == null) {
			throw new IllegalArgumentException();
		}
		// TODO [post-1.0] need tests. There's Main#checkWalkFileRevisions that may be a starting point
		int[] manifestRevIndexes = toManifestRevisionIndexes(changelogRevisionIndexes, null);
		FileLookupInspector parser = new FileLookupInspector(encodingHelper, file, inspector);
		parser.walk(manifestRevIndexes, content);
	}

	/**
	 * Extract file {@link Flags flags} as they were recorded in appropriate manifest version. 
	 *  
	 * @see HgDataFile#getFlags(int)
	 * @param changesetRevIndex changeset revision index
	 * @param file path to look up
	 * @return one of predefined enum values, or <code>null</code> if file was not known in the specified revision
	 * @throws HgInvalidRevisionException if supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public Flags getFileFlags(int changesetRevIndex, Path file) throws HgRuntimeException {
		int manifestRevIdx = fromChangelog(changesetRevIndex);
		IntMap<Flags> resMap = new IntMap<Flags>(2);
		FileLookupInspector parser = new FileLookupInspector(encodingHelper, file, null, resMap);
		parser.walk(manifestRevIdx, content);
		assert resMap.size() <= 1; // size() == 0 if not found
		// can't use changesetRevIndex as key - it might have been TIP
		return resMap.size() == 0 ? null : resMap.get(resMap.firstKey());
	}


	/*package-local*/ void dropCachesOnChangelogChange() {
		// sort of a hack as it may happen that #fromChangelog()
		// is invoked for modified repository where revisionMap still points to an old state
		// Since there's no access to RevlogStream in #fromChangelog() if there's revisionMap 
		// in place, there's no chance for RevlogStream to detect the change and to dispatch 
		// change notification so that revisionMap got cleared.
		revisionMap = null;
	}

	/**
	 * @param changelogRevisionIndexes non-null
	 * @param inspector may be null if reporting of missing manifests is not needed
	 * @throws HgInvalidRevisionException if supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	private int[] toManifestRevisionIndexes(int[] changelogRevisionIndexes, Inspector inspector) throws HgRuntimeException {
		int[] manifestRevs = new int[changelogRevisionIndexes.length];
		boolean needsSort = false;
		int j = 0;
		for (int i = 0; i < changelogRevisionIndexes.length; i++) {
			final int manifestRevisionIndex = fromChangelog(changelogRevisionIndexes[i]);
			if (manifestRevisionIndex == BAD_REVISION) {
				if (inspector != null) {
					inspector.begin(NO_REVISION, NULL, changelogRevisionIndexes[i]);
					inspector.end(NO_REVISION);
				}
				// othrwise, ignore changeset without manifest
			} else {
				manifestRevs[j] = manifestRevisionIndex;
				if (j > 0 && manifestRevs[j-1] > manifestRevisionIndex) {
					needsSort = true;
				}
				j++;
			}
		}
		if (needsSort) {
			Arrays.sort(manifestRevs, 0, j);
		}
		if (j == manifestRevs.length) {
			return manifestRevs;
		} else {
			int[] rv = new int[j];
			//Arrays.copyOfRange
			System.arraycopy(manifestRevs, 0, rv, 0, j);
			return rv;
		}
	}

	@Callback
	public interface Inspector {
		/**
		 * Denotes entering specific manifest revision, separate entries are
		 * reported with subsequence {@link #next(Nodeid, Path, Flags)} calls.
		 * 
		 * @param manifestRevisionIndex  local revision index of the inspected revision
		 * @param manifestRevision revision of the manifest we're about to iterate through
		 * @param changelogRevisionIndex local revision index of changelog this manifest points to 
		 * @return <code>true</code> to continue iteration, <code>false</code> to stop
		 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
		 */
		boolean begin(int manifestRevisionIndex, Nodeid manifestRevision, int changelogRevisionIndex) throws HgRuntimeException;

		
		/**
		 * Reports each manifest entry
		 *  
		 * @param nid file revision
		 * @param fname file name
		 * @param flags one of {@link HgManifest.Flags} constants, not <code>null</code>
		 * @return <code>true</code> to continue iteration, <code>false</code> to stop
		 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
		 */
		boolean next(Nodeid nid, Path fname, Flags flags) throws HgRuntimeException;

		/**
		 * Denotes leaving specific manifest revision, after all entries were reported using {@link #next(Nodeid, Path, Flags)}
		 *   
		 * @param manifestRevisionIndex indicates manifest revision, corresponds to opening {@link #begin(int, Nodeid, int)}
		 * @return <code>true</code> to continue iteration, <code>false</code> to stop
		 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
		 */
		boolean end(int manifestRevisionIndex) throws HgRuntimeException;
	}
	
	/**
	 * When Pool uses Strings directly,
	 * ManifestParser creates new String instance with new char[] value, and does byte->char conversion.
	 * For cpython repo, walk(0..10k), there are over 16 million filenames, of them only 3020 unique.
	 * This means there are 15.9 million useless char[] instances and byte->char conversions  
	 * 
	 * When String (Path) is wrapped into {@link PathProxy}, there's extra overhead of byte[] representation
	 * of the String, but these are only for unique Strings (Paths) (3020 in the example above). Besides, I save
	 * useless char[] and byte->char conversions. 
	 */
	private final class PathProxy {
		private byte[] data;
		private int start; 
		private final int hash, length;
		private Path result;

		public PathProxy(byte[] data, int start, int length) {
			this.data = data;
			this.start = start;
			this.length = length;

			// copy from String.hashCode(). In fact, not necessarily match result of String(data).hashCode
			// just need some nice algorithm here
			int h = 0;
			byte[] d = data;
			for (int i = 0, off = start, len = length; i < len; i++) {
				h = 31 * h + d[off++];
			}
			hash = h;
		}

		@Override
		public boolean equals(Object obj) {
			if (false == obj instanceof PathProxy) {
				return false;
			}
			PathProxy o = (PathProxy) obj;
			if (o.result != null && result != null) {
				return result.equals(o.result);
			}
			if (o.length != length || o.hash != hash) {
				return false;
			}
			for (int i = 0, x = o.start, y = start; i < length; i++) {
				if (o.data[x++] != data[y++]) {
					return false;
				}
			}
			return true;
		}
		@Override
		public int hashCode() {
			return hash;
		}
		
		public Path freeze() {
			if (result == null) {
				Path.Source pf = HgManifest.this.pathFactory;
				result = pf.path(HgManifest.this.encodingHelper.fromManifest(data, start, length));
				// release reference to bigger data array, make a copy of relevant part only
				// use original bytes, not those from String above to avoid cache misses due to different encodings 
				byte[] d = new byte[length];
				System.arraycopy(data, start, d, 0, length);
				data = d;
				start = 0;
			}
			return result;
		}
	}

	private class ManifestParser implements RevlogStream.Inspector, Lifecycle {
		private final Inspector inspector;
		private IdentityPool<Nodeid> nodeidPool, thisRevPool;
		private final IdentityPool<PathProxy> fnamePool;
		private byte[] nodeidLookupBuffer = new byte[20]; // get reassigned each time new Nodeid is added to pool
		private final ProgressSupport progressHelper;
		private IterateControlMediator iterateControl;
		
		public ManifestParser(Inspector delegate) {
			assert delegate != null;
			inspector = delegate;
			nodeidPool = new IdentityPool<Nodeid>();
			fnamePool = new IdentityPool<PathProxy>();
			thisRevPool = new IdentityPool<Nodeid>();
			progressHelper = ProgressSupport.Factory.get(delegate);
		}
		
		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) throws HgRuntimeException {
			try {
				if (!inspector.begin(revisionNumber, new Nodeid(nodeid, true), linkRevision)) {
					iterateControl.stop();
					return;
				}
				if (!da.isEmpty()) {
					// although unlikely, manifest entry may be empty, when all files have been deleted from the repository
					Path fname = null;
					Flags flags = null;
					Nodeid nid = null;
					int i;
					byte[] data = da.byteArray();
					for (i = 0; i < actualLen; i++) {
						int x = i;
						for( ; data[i] != '\n' && i < actualLen; i++) {
							if (fname == null && data[i] == 0) {
								PathProxy px = fnamePool.unify(new PathProxy(data, x, i - x));
								// if (cached = fnamePool.unify(px))== px then cacheMiss, else cacheHit
								// cpython 0..10k: hits: 15 989 152, misses: 3020
								fname = px.freeze();
								x = i+1;
							}
						}
						if (i < actualLen) {
							assert data[i] == '\n'; 
							int nodeidLen = i - x < 40 ? i-x : 40; // if > 40, there are flags
							DigestHelper.ascii2bin(data, x, nodeidLen, nodeidLookupBuffer); // ignore return value as it's unlikely to have NULL in manifest
							nid = new Nodeid(nodeidLookupBuffer, false); // this Nodeid is for pool lookup only, mock object
							Nodeid cached = nodeidPool.unify(nid);
							if (cached == nid) {
								// buffer now belongs to the cached nodeid
								nodeidLookupBuffer = new byte[20];
							} else {
								nid = cached; // use existing version, discard the lookup object
							} // for cpython 0..10k, cache hits are 15 973 301, vs 18871 misses.
							thisRevPool.record(nid); // memorize revision for the next iteration. 
							if (nodeidLen + x < i) {
								// 'x' and 'l' for executable bits and symlinks?
								// hg --debug manifest shows 644 for each regular file in my repo
								// for cpython 0..10k, there are 4361062 flag checks, and there's only 1 unique flag
								flags = Flags.parse(data, x + nodeidLen, i-x-nodeidLen);
							} else {
								flags = Flags.RegularFile;
							}
							boolean good2go = inspector.next(nid, fname, flags);
							if (!good2go) {
								iterateControl.stop();
								return;
							}
						}
						nid = null;
						fname = null;
						flags = null;
					}
				}
				if (!inspector.end(revisionNumber)) {
					iterateControl.stop();
					return;
				}
				//
				// keep only actual file revisions, found at this version 
				// (next manifest is likely to refer to most of them, although in specific cases 
				// like commit in another branch a lot may be useless)
				nodeidPool.clear();
				IdentityPool<Nodeid> t = nodeidPool;
				nodeidPool = thisRevPool;
				thisRevPool = t;
				iterateControl.checkCancelled();
				progressHelper.worked(1);
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Failed reading manifest", ex, null).setRevisionIndex(revisionNumber);
			}
		}

		public void start(int count, Callback callback, Object token) {
			CancelSupport cs = CancelSupport.Factory.get(inspector, null);
			iterateControl = new IterateControlMediator(cs, callback);
			progressHelper.start(count);
		}

		public void finish(Object token) {
			progressHelper.done();
		}
	}
	
	private class RevisionMapper implements RevlogStream.Inspector, Lifecycle {
		
		private final int changelogRevisionCount;
		private int[] changelog2manifest;
		RevisionLookup manifestNodeids;

		private RevisionMapper(boolean useOwnRevisionLookup) throws HgRuntimeException {
			changelogRevisionCount = HgManifest.this.getRepo().getChangelog().getRevisionCount();
			if (useOwnRevisionLookup) {
				manifestNodeids = new RevisionLookup(HgManifest.this.content);
			}
		}
		
		/**
		 * Get index of manifest revision that corresponds to specified changeset
		 * @param changesetRevisionIndex non-negative index of changelog revision, or {@link HgRepository#TIP}
		 * @return index of manifest revision, or {@link HgRepository#BAD_REVISION} if changeset doesn't reference a valid manifest
		 * @throws HgInvalidRevisionException if method argument specifies non-existent revision index
		 */
		public int at(int changesetRevisionIndex) throws HgInvalidRevisionException {
			if (changesetRevisionIndex == TIP) {
				changesetRevisionIndex = changelogRevisionCount - 1;
			}
			if (changesetRevisionIndex >= changelogRevisionCount) {
				throw new HgInvalidRevisionException(changesetRevisionIndex);
			}
			if (changelog2manifest != null) {
				return changelog2manifest[changesetRevisionIndex];
			}
			return changesetRevisionIndex;
		}

		// XXX can be replaced with Revlog.RevisionInspector, but I don't want Nodeid instances
		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) throws HgInvalidRevisionException {
			if (linkRevision >= changelogRevisionCount) {
				String storeLock = HgManifest.this.getRepo().getStoreLock().readLockInfo();
				String message = String.format("Manifest revision %d references changeset %d, which is beyond known scope [0..%d). Lock: %s", revisionNumber, linkRevision, changelogRevisionCount, storeLock);
				throw new HgInvalidRevisionException(message, null, linkRevision);
			}
			if (changelog2manifest != null) {
				// next assertion is not an error, rather assumption check, which is too development-related to be explicit exception - 
				// I just wonder if there are manifests that have two entries pointing to single changeset. It seems unrealistic, though -
				// changeset records one and only one manifest nodeid
				assert changelog2manifest[linkRevision] == BAD_REVISION : String.format("revision:%d, link:%d, already linked to revision:%d", revisionNumber, linkRevision, changelog2manifest[linkRevision]);
				changelog2manifest[linkRevision] = revisionNumber;
			} else {
				if (revisionNumber != linkRevision) {
					changelog2manifest = new int[changelogRevisionCount];
					Arrays.fill(changelog2manifest, BAD_REVISION);
					for (int i = 0; i < revisionNumber; changelog2manifest[i] = i, i++)
						;
					changelog2manifest[linkRevision] = revisionNumber;
				}
			}
			if (manifestNodeids != null) {
				manifestNodeids.next(revisionNumber, nodeid);
			}
		}
		
		public void start(int count, Callback callback, Object token) {
			if (count != changelogRevisionCount) {
				assert count < changelogRevisionCount; // no idea what to do if manifest has more revisions than changelog
				// the way how manifest may contain more revisions than changelog, as I can imagine, is a result of  
				// some kind of an import tool (e.g. from SVN or CVS), that creates manifest and changelog independently.
				// Note, it's pure guess, I didn't see such repository yet (although the way manifest revisions
				// in cpython repo are numbered makes me think aforementioned way) 
				changelog2manifest = new int[changelogRevisionCount];
				Arrays.fill(changelog2manifest, BAD_REVISION);
			}
			if (manifestNodeids != null) {
				manifestNodeids.prepare(count);
			}
		}

		public void finish(Object token) {
			// it's not a nice idea to fix changesets that reuse existing manifest entries from inside
			// #finish, as the manifest read operation is not complete at the moment.
		}
		
		public void fixReusedManifests() throws HgRuntimeException {
			if (changelog2manifest == null) {
				// direct, 1-1 mapping of changeset indexes to manifest
				return;
			}
			// I assume there'd be not too many revisions we don't know manifest of
			IntVector undefinedChangelogRevision = new IntVector();
			for (int i = 0; i < changelog2manifest.length; i++) {
				if (changelog2manifest[i] == BAD_REVISION) {
					undefinedChangelogRevision.add(i);
				}
			}
			if (undefinedChangelogRevision.size() > 0) {
				final IntMap<Nodeid> missingCsetToManifest = new IntMap<Nodeid>(undefinedChangelogRevision.size());
				int[] undefinedClogRevs = undefinedChangelogRevision.toArray();
				// undefinedChangelogRevision is sorted by the nature it's created
				HgManifest.this.getRepo().getChangelog().rangeInternal(new HgChangelog.Inspector() {
					
					public void next(int revisionIndex, Nodeid nodeid, RawChangeset cset) {
						missingCsetToManifest.put(revisionIndex, cset.manifest());
					}
				}, undefinedClogRevs);
				assert missingCsetToManifest.size() == undefinedChangelogRevision.size();
				for (int u : undefinedClogRevs) {
					Nodeid manifest = missingCsetToManifest.get(u);
					if (manifest == null || manifest.isNull()) {
						HgManifest.this.getRepo().getSessionContext().getLog().dump(getClass(), Severity.Warn, "Changeset %d has no associated manifest entry", u);
						// keep BAD_REVISION in the changelog2manifest map.
						continue;
					}
					if (manifestNodeids != null) {
						int manifestRevIndex = manifestNodeids.findIndex(manifest);
						// mimic HgManifest#getRevisionIndex() to keep behavior the same 
						if (manifestRevIndex == BAD_REVISION) {
							throw new HgInvalidRevisionException(String.format("Can't find index of revision %s", manifest.shortNotation()), manifest, null);
						}
						changelog2manifest[u] = manifestRevIndex;
					} else {
						changelog2manifest[u] = HgManifest.this.getRevisionIndex(manifest);
					}
				}
			}
		}
	}
	
	/**
	 * Look up specified file in possibly multiple manifest revisions, collect file revision and flags.
	 */
	private static class FileLookupInspector implements RevlogStream.Inspector {
		
		private final Path filename;
		private final byte[] filenameAsBytes;
		private final IntMap<Nodeid> csetIndex2FileRev;
		private final IntMap<Flags> csetIndex2Flags;
		private final Inspector delegate;

		public FileLookupInspector(EncodingHelper eh, Path fileToLookUp, IntMap<Nodeid> csetIndex2FileRevMap, IntMap<Flags> csetIndex2FlagsMap) {
			assert fileToLookUp != null;
			// need at least one map for the inspector to make any sense
			assert csetIndex2FileRevMap != null || csetIndex2FlagsMap != null;
			filename = fileToLookUp;
			filenameAsBytes = eh.toManifest(fileToLookUp.toString());
			delegate = null;
			csetIndex2FileRev = csetIndex2FileRevMap;
			csetIndex2Flags = csetIndex2FlagsMap;
		}
		
		public FileLookupInspector(EncodingHelper eh, Path fileToLookUp, Inspector delegateInspector) {
			assert fileToLookUp != null;
			assert delegateInspector != null;
			filename = fileToLookUp;
			filenameAsBytes = eh.toManifest(fileToLookUp.toString());
			delegate = delegateInspector;
			csetIndex2FileRev = null;
			csetIndex2Flags = null;
		}
		
		void walk(int manifestRevIndex, RevlogStream content) throws HgRuntimeException {
			content.iterate(manifestRevIndex, manifestRevIndex, true, this); 
		}

		void walk(int[] manifestRevIndexes, RevlogStream content) throws HgRuntimeException {
			content.iterate(manifestRevIndexes, true, this);
		}
		
		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) throws HgRuntimeException {
			ByteVector byteVector = new ByteVector(256, 128); // allocate for long paths right away
			try {
				byte b;
				while (!data.isEmpty() && (b = data.readByte()) != '\n') {
					if (b != 0) {
						byteVector.add(b);
					} else {
						if (byteVector.equalsTo(filenameAsBytes)) {
							Nodeid fileRev = null;
							Flags flags = null;
							if (csetIndex2FileRev != null || delegate != null) {
								byte[] nid = new byte[40];  
								data.readBytes(nid, 0, 40);
								fileRev = Nodeid.fromAscii(nid, 0, 40);
							} else {
								data.skip(40);
							}
							if (csetIndex2Flags != null || delegate != null) {
								byteVector.clear();
								while (!data.isEmpty() && (b = data.readByte()) != '\n') {
									byteVector.add(b);
								}
								if (byteVector.size() == 0) {
									flags = Flags.RegularFile;
								} else {
									flags = Flags.parse(byteVector.toByteArray(), 0, byteVector.size());
								}
							}
							if (delegate != null) {
								assert flags != null;
								assert fileRev != null;
								delegate.begin(revisionNumber, Nodeid.fromBinary(nodeid, 0), linkRevision);
								delegate.next(fileRev, filename, flags);
								delegate.end(revisionNumber);
								
							} else {
								if (csetIndex2FileRev != null) {
									csetIndex2FileRev.put(linkRevision, fileRev);
								}
								if (csetIndex2Flags != null) {
									csetIndex2Flags.put(linkRevision, flags);
								}
							}
							break;
						} else {
							data.skip(40);
						}
						// else skip to the end of line
						while (!data.isEmpty() && (b = data.readByte()) != '\n')
							;

						byteVector.clear();
					}
				}
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Failed reading manifest", ex, null);
			}
		}
	}
}
