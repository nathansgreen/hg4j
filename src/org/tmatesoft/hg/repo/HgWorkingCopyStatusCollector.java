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

import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.tmatesoft.hg.repo.HgRepository.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.FilterByteChannel;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.PathPool;
import org.tmatesoft.hg.internal.PathScope;
import org.tmatesoft.hg.internal.Preview;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Convertor;
import org.tmatesoft.hg.util.FileInfo;
import org.tmatesoft.hg.util.FileIterator;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;
import org.tmatesoft.hg.util.RegularFileInfo;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgWorkingCopyStatusCollector {

	private final HgRepository repo;
	private final FileIterator repoWalker;
	private HgDirstate dirstate;
	private HgStatusCollector baseRevisionCollector;
	private Convertor<Path> pathPool;
	private ManifestRevision dirstateParentManifest;

	/**
	 * Collector that iterates over complete working copy
	 */
	public HgWorkingCopyStatusCollector(HgRepository hgRepo) {
		this(hgRepo, new HgInternals(hgRepo).createWorkingDirWalker(null));
	}

	/**
	 * Collector may analyze and report status for any arbitrary sub-tree of the working copy.
	 * File iterator shall return names of the files relative to the repository root.
	 * 
	 * @param hgRepo status target repository
	 * @param workingCopyWalker iterator over files in the working copy
	 */
	public HgWorkingCopyStatusCollector(HgRepository hgRepo, FileIterator workingCopyWalker) {
		repo = hgRepo;
		repoWalker = workingCopyWalker;
	}
	
	/**
	 * Optionally, supply a collector instance that may cache (or have already cached) base revision
	 * @param sc may be null
	 */
	public void setBaseRevisionCollector(HgStatusCollector sc) {
		baseRevisionCollector = sc;
	}

	/*package-local*/ Convertor<Path> getPathPool() {
		if (pathPool == null) {
			if (baseRevisionCollector == null) {
				pathPool = new PathPool(new PathRewrite.Empty());
			} else {
				return baseRevisionCollector.getPathPool();
			}
		}
		return pathPool;
	}

	public void setPathPool(Convertor<Path> pathConvertor) {
		pathPool = pathConvertor;
	}

	/**
	 * Access to directory state information this collector uses.
	 * @return directory state holder, never <code>null</code> 
	 */
	public HgDirstate getDirstate() throws HgInvalidControlFileException {
		if (dirstate == null) {
			Convertor<Path> pp = getPathPool();
			Path.Source ps;
			if (pp instanceof Path.Source) {
				ps = (Path.Source) pp;
			} else {
				ps = new Path.SimpleSource(new PathRewrite.Empty(), pp);
			}
			dirstate = repo.loadDirstate(ps);
		}
		return dirstate;
	}
	
	private HgDirstate getDirstateImpl() {
		return dirstate;
	}
	
	private ManifestRevision getManifest(int changelogLocalRev) throws HgInvalidControlFileException {
		assert changelogLocalRev >= 0;
		ManifestRevision mr;
		if (baseRevisionCollector != null) {
			mr = baseRevisionCollector.raw(changelogLocalRev);
		} else {
			mr = new ManifestRevision(null, null);
			repo.getManifest().walk(changelogLocalRev, changelogLocalRev, mr);
		}
		return mr;
	}

	private void initDirstateParentManifest() throws HgInvalidControlFileException {
		Nodeid dirstateParent = getDirstateImpl().parents().first();
		if (dirstateParent.isNull()) {
			dirstateParentManifest = baseRevisionCollector != null ? baseRevisionCollector.raw(NO_REVISION) : HgStatusCollector.createEmptyManifestRevision();
		} else {
			int changeloRevIndex = repo.getChangelog().getRevisionIndex(dirstateParent);
			dirstateParentManifest = getManifest(changeloRevIndex);
		}
	}

	// WC not necessarily points to TIP, but may be result of update to any previous revision.
	// In such case, we need to compare local files not to their TIP content, but to specific version at the time of selected revision
	private ManifestRevision getDirstateParentManifest() {
		return dirstateParentManifest;
	}
	
	/**
	 * Walk working copy, analyze status for each file found and missing.
	 * May be invoked few times.
	 * 
	 * <p>There's no dedicated constant to for working copy parent, at least now. 
	 * Use {@link HgRepository#WORKING_COPY} to indicate comparison 
	 * shall be run against working copy parent. Although a bit confusing, single case doesn't 
	 * justify a dedicated constant.
	 * 
	 * @param baseRevision revision index to check against, or {@link HgRepository#WORKING_COPY}. Note, {@link HgRepository#TIP} is not supported.
	 * @param inspector callback to receive status information
	 * @throws IOException to propagate IO errors from {@link FileIterator}
	 * @throws CancelledException if operation execution was cancelled
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void walk(int baseRevision, HgStatusInspector inspector) throws IOException, CancelledException, HgRuntimeException {
		if (HgInternals.wrongRevisionIndex(baseRevision) || baseRevision == BAD_REVISION) {
			throw new HgInvalidRevisionException(baseRevision);
		}
		if (getDirstateImpl() == null) {
				getDirstate();
		}
		if (getDirstateParentManifest() == null) {
			initDirstateParentManifest();
		}
		// XXX NOTE, use of TIP for working copy parent is questionable, at least. Instead, TIP shall mean latest cset or not allowed at all
		ManifestRevision collect = null; // non null indicates we compare against base revision
		Set<Path> baseRevFiles = Collections.emptySet(); // files from base revision not affected by status calculation 
		if (baseRevision != TIP && baseRevision != WORKING_COPY) {
			collect = getManifest(baseRevision);
			baseRevFiles = new TreeSet<Path>(collect.files());
		}
		if (inspector instanceof HgStatusCollector.Record) {
			HgStatusCollector sc = baseRevisionCollector == null ? new HgStatusCollector(repo) : baseRevisionCollector;
			// nodeidAfterChange(dirstate's parent) doesn't make too much sense,
			// because the change might be actually in working copy. Nevertheless, 
			// as long as no nodeids can be provided for WC, seems reasonable to report
			// latest known nodeid change (although at the moment this is not used and
			// is done mostly not to leave stale initialization in the Record)
			int rev1,rev2 = getDirstateParentManifest().changesetLocalRev();
			if (baseRevision == TIP || baseRevision == WORKING_COPY) {
				rev1 = rev2 - 1; // just use revision prior to dirstate's parent
			} else {
				rev1 = baseRevision;
			}
			((HgStatusCollector.Record) inspector).init(rev1, rev2, sc);
		}
		final CancelSupport cs = CancelSupport.Factory.get(inspector);
		final HgIgnore hgIgnore = repo.getIgnore();
		repoWalker.reset();
		TreeSet<Path> processed = new TreeSet<Path>(); // names of files we handled as they known to Dirstate (not FileIterator)
		final HgDirstate ds = getDirstateImpl();
		TreeSet<Path> knownEntries = ds.all(); // here just to get dirstate initialized
		while (repoWalker.hasNext()) {
			cs.checkCancelled();
			repoWalker.next();
			final Path fname = getPathPool().mangle(repoWalker.name());
			FileInfo f = repoWalker.file();
			Path knownInDirstate;
			if (!f.exists()) {
				// file coming from iterator doesn't exist.
				if ((knownInDirstate = ds.known(fname)) != null) {
					// found in dirstate
					processed.add(knownInDirstate);
					if (ds.checkRemoved(knownInDirstate) == null) {
						inspector.missing(knownInDirstate);
					} else {
						inspector.removed(knownInDirstate);
					}
					// do not report it as removed later
					if (collect != null) {
						baseRevFiles.remove(knownInDirstate);
					}
				} else {
					// chances are it was known in baseRevision. We may rely
					// that later iteration over baseRevFiles leftovers would yield correct Removed,
					// but it doesn't hurt to be explicit (provided we know fname *is* inScope of the FileIterator
					if (collect != null && baseRevFiles.remove(fname)) {
						inspector.removed(fname);
					} else {
						// not sure I shall report such files (i.e. arbitrary name coming from FileIterator)
						// as unknown. Command-line HG aborts "system can't find the file specified"
						// in similar case (against wc), or just gives nothing if --change <rev> is specified.
						// however, as it's unlikely to get unexisting files from FileIterator, and
						// its better to see erroneous file status rather than not to see any (which is too easy
						// to overlook), I think unknown() is reasonable approach here
						inspector.unknown(fname);
					}
				}
				continue;
			}
			if ((knownInDirstate = ds.known(fname)) != null) {
				// tracked file.
				// modified, added, removed, clean
				processed.add(knownInDirstate);
				if (collect != null) { // need to check against base revision, not FS file
					checkLocalStatusAgainstBaseRevision(baseRevFiles, collect, baseRevision, knownInDirstate, f, inspector);
				} else {
					checkLocalStatusAgainstFile(knownInDirstate, f, inspector);
				}
			} else {
				if (hgIgnore.isIgnored(fname)) { // hgignore shall be consulted only for non-tracked files
					inspector.ignored(fname);
				} else {
					inspector.unknown(fname);
				}
				// the file is not tracked. Even if it's known at baseRevision, we don't need to remove it
				// from baseRevFiles, it might need to be reported as removed as well (cmdline client does
				// yield two statuses for the same file)
			}
		}
		if (collect != null) {
			for (Path fromBase : baseRevFiles) {
				if (repoWalker.inScope(fromBase)) {
					inspector.removed(fromBase);
					cs.checkCancelled();
				}
			}
		}
		knownEntries.removeAll(processed);
		for (Path m : knownEntries) {
			if (!repoWalker.inScope(m)) {
				// do not report as missing/removed those FileIterator doesn't care about.
				continue;
			}
			cs.checkCancelled();
			// missing known file from a working dir  
			if (ds.checkRemoved(m) == null) {
				// not removed from the repository = 'deleted'  
				inspector.missing(m);
			} else {
				// removed from the repo
				// if we check against non-tip revision, do not report files that were added past that revision and now removed.
				if (collect == null || baseRevFiles.contains(m)) {
					inspector.removed(m);
				}
			}
		}
	}

	/**
	 * A {@link #walk(int, HgStatusInspector)} that records all the status information in the {@link HgStatusCollector.Record} object.
	 * 
	 * @see #walk(int, HgStatusInspector)
	 * @param baseRevision revision index to check against, or {@link HgRepository#WORKING_COPY}. Note, {@link HgRepository#TIP} is not supported.
	 * @return information object that describes change between the revisions
	 * @throws IOException to propagate IO errors from {@link FileIterator}
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgStatusCollector.Record status(int baseRevision) throws IOException, HgRuntimeException {
		HgStatusCollector.Record rv = new HgStatusCollector.Record();
		try {
			walk(baseRevision, rv);
		} catch (CancelledException ex) {
			// can't happen as long our Record class doesn't implement CancelSupport
			HgInvalidStateException t = new HgInvalidStateException("Internal error");
			t.initCause(ex);
			throw t;
		}
		return rv;
	}

	//********************************************

	
	private void checkLocalStatusAgainstFile(Path fname, FileInfo f, HgStatusInspector inspector) {
		HgDirstate.Record r;
		if ((r = getDirstateImpl().checkNormal(fname)) != null) {
			// either clean or modified
			final boolean timestampEqual = f.lastModified() == r.modificationTime(), sizeEqual = r.size() == f.length();
			if (timestampEqual && sizeEqual) {
				// if flags change (chmod -x), timestamp does not change
				if (checkFlagsEqual(f, r.mode())) {
					inspector.clean(fname);
				} else {
					inspector.modified(fname); // flags are not the same
				}
			} else if (!sizeEqual && r.size() >= 0) {
				inspector.modified(fname);
			} else {
				// size is the same or unknown, and, perhaps, different timestamp
				// check actual content to avoid false modified files
				try {
					if (!checkFlagsEqual(f, r.mode())) {
						// flags modified, no need to do expensive content check
						inspector.modified(fname);
					} else {
						HgDataFile df = repo.getFileNode(fname);
						if (!df.exists()) {
							String msg = String.format("File %s known as normal in dirstate (%d, %d), doesn't exist at %s", fname, r.modificationTime(), r.size(), repo.getStoragePath(df));
							throw new HgInvalidFileException(msg, null).setFileName(fname);
						}
						Nodeid rev = getDirstateParentManifest().nodeid(fname);
						// rev might be null here if fname comes to dirstate as a result of a merge operation
						// where one of the parents (first parent) had no fname file, but second parent had.
						// E.g. fork revision 3, revision 4 gets .hgtags, few modifications and merge(3,12)
						// see Issue 14 for details
						if (rev == null || !areTheSame(f, df, rev)) {
							inspector.modified(df.getPath());
						} else {
							inspector.clean(df.getPath());
						}
					}
				} catch (HgRuntimeException ex) {
					repo.getContext().getLog().dump(getClass(), Warn, ex, null);
					inspector.invalid(fname, ex);
				}
			}
		} else if ((r = getDirstateImpl().checkAdded(fname)) != null) {
			if (r.copySource() == null) {
				inspector.added(fname);
			} else {
				inspector.copied(r.copySource(), fname);
			}
		} else if ((r = getDirstateImpl().checkRemoved(fname)) != null) {
			inspector.removed(fname);
		} else if ((r = getDirstateImpl().checkMerged(fname)) != null) {
			inspector.modified(fname);
		}
	}
	
	// XXX refactor checkLocalStatus methods in more OO way
	private void checkLocalStatusAgainstBaseRevision(Set<Path> baseRevNames, ManifestRevision collect, int baseRevision, Path fname, FileInfo f, HgStatusInspector inspector) {
		// fname is in the dirstate, either Normal, Added, Removed or Merged
		Nodeid nid1 = collect.nodeid(fname);
		HgManifest.Flags flags = collect.flags(fname);
		HgDirstate.Record r;
		if (nid1 == null) {
			// normal: added?
			// added: not known at the time of baseRevision, shall report
			// merged: was not known, report as added?
			if ((r = getDirstateImpl().checkNormal(fname)) != null) {
				try {
					Path origin = HgStatusCollector.getOriginIfCopy(repo, fname, baseRevNames, baseRevision);
					if (origin != null) {
						inspector.copied(getPathPool().mangle(origin), fname);
						return;
					}
				} catch (HgInvalidFileException ex) {
					// report failure and continue status collection
					inspector.invalid(fname, ex);
				}
			} else if ((r = getDirstateImpl().checkAdded(fname)) != null) {
				if (r.copySource() != null && baseRevNames.contains(r.copySource())) {
					baseRevNames.remove(r.copySource()); // XXX surely I shall not report rename source as Removed?
					inspector.copied(r.copySource(), fname);
					return;
				}
				// fall-through, report as added
			} else if (getDirstateImpl().checkRemoved(fname) != null) {
				// removed: removed file was not known at the time of baseRevision, and we should not report it as removed
				return;
			}
			inspector.added(fname);
		} else {
			// was known; check whether clean or modified
			Nodeid nidFromDirstate = getDirstateParentManifest().nodeid(fname);
			if ((r = getDirstateImpl().checkNormal(fname)) != null && nid1.equals(nidFromDirstate)) {
				// regular file, was the same up to WC initialization. Check if was modified since, and, if not, report right away
				// same code as in #checkLocalStatusAgainstFile
				final boolean timestampEqual = f.lastModified() == r.modificationTime(), sizeEqual = r.size() == f.length();
				boolean handled = false;
				if (timestampEqual && sizeEqual) {
					inspector.clean(fname);
					handled = true;
				} else if (!sizeEqual && r.size() >= 0) {
					inspector.modified(fname);
					handled = true;
				} else if (!checkFlagsEqual(f, flags)) {
					// seems like flags have changed, no reason to check content further
					inspector.modified(fname);
					handled = true;
				}
				if (handled) {
					baseRevNames.remove(fname); // consumed, processed, handled.
					return;
				}
				// otherwise, shall check actual content (size not the same, or unknown (-1 or -2), or timestamp is different,
				// or nodeid in dirstate is different, but local change might have brought it back to baseRevision state)
				// FALL THROUGH
			}
			if (r != null || (r = getDirstateImpl().checkMerged(fname)) != null || (r = getDirstateImpl().checkAdded(fname)) != null) {
				try {
					// check actual content to see actual changes
					// when added - seems to be the case of a file added once again, hence need to check if content is different
					// either clean or modified
					HgDataFile fileNode = repo.getFileNode(fname);
					if (areTheSame(f, fileNode, nid1)) {
						inspector.clean(fname);
					} else {
						inspector.modified(fname);
					}
				} catch (HgRuntimeException ex) {
					repo.getContext().getLog().dump(getClass(), Warn, ex, null);
					inspector.invalid(fname, ex);
				}
				baseRevNames.remove(fname); // consumed, processed, handled.
			} else if (getDirstateImpl().checkRemoved(fname) != null) {
				// was known, and now marked as removed, report it right away, do not rely on baseRevNames processing later
				inspector.removed(fname);
				baseRevNames.remove(fname); // consumed, processed, handled.
			}
			// only those left in baseRevNames after processing are reported as removed 
		}

		// TODO think over if content comparison may be done more effectively by e.g. calculating nodeid for a local file and comparing it with nodeid from manifest
		// we don't need to tell exact difference, hash should be enough to detect difference, and it doesn't involve reading historical file content, and it's relatively 
		// cheap to calc hash on a file (no need to keep it completely in memory). OTOH, if I'm right that the next approach is used for nodeids: 
		// changeset nodeid + hash(actual content) => entry (Nodeid) in the next Manifest
		// then it's sufficient to check parents from dirstate, and if they do not match parents from file's baseRevision (non matching parents means different nodeids).
		// The question is whether original Hg treats this case (same content, different parents and hence nodeids) as 'modified' or 'clean'
	}

	private boolean areTheSame(FileInfo f, HgDataFile dataFile, Nodeid revision) throws HgInvalidFileException {
		// XXX consider adding HgDataDile.compare(File/byte[]/whatever) operation to optimize comparison
		ByteArrayChannel bac = new ByteArrayChannel();
		try {
			int fileRevisionIndex = dataFile.getRevisionIndex(revision);
			// need content with metadata striped off - although theoretically chances are metadata may be different,
			// WC doesn't have it anyway 
			dataFile.content(fileRevisionIndex, bac);
		} catch (CancelledException ex) {
			// silently ignore - can't happen, ByteArrayChannel is not cancellable
		}
		return areTheSame(f, bac.toArray(), dataFile.getPath());
	}
	
	private boolean areTheSame(FileInfo f, final byte[] data, Path p) throws HgInvalidFileException {
		ReadableByteChannel is = null;
		class Check implements ByteChannel {
			final boolean debug = repo.getContext().getLog().isDebug(); 
			boolean sameSoFar = true;
			int x = 0;

			public int write(ByteBuffer buffer) {
				for (int i = buffer.remaining(); i > 0; i--, x++) {
					if (x >= data.length /*file has been appended*/ || data[x] != buffer.get()) {
						if (debug) {
							byte[] xx = new byte[15];
							if (buffer.position() > 5) {
								buffer.position(buffer.position() - 5);
							}
							buffer.get(xx, 0, min(xx.length, i-1 /*-1 for the one potentially read at buffer.get in if() */));
							String exp;
							if (x < data.length) {
								exp = new String(data, max(0, x - 4), min(data.length - x, 20));
							} else {
								int offset = max(0, x - 4);
								exp = new String(data, offset, min(data.length - offset, 20));
							}
							repo.getContext().getLog().dump(getClass(), Debug, "expected >>%s<< but got >>%s<<", exp, new String(xx));
						}
						sameSoFar = false;
						break;
					}
				}
				buffer.position(buffer.limit()); // mark as read
				return buffer.limit();
			}
			
			public boolean sameSoFar() {
				return sameSoFar;
			}
			public boolean ultimatelyTheSame() {
				return sameSoFar && x == data.length;
			}
		};
		Check check = new Check(); 
		try {
			is = f.newInputChannel();
			ByteBuffer fb = ByteBuffer.allocate(min(1 + data.length * 2 /*to fit couple of lines appended; never zero*/, 8192));
			FilterByteChannel filters = new FilterByteChannel(check, repo.getFiltersFromWorkingDirToRepo(p));
			Preview preview = Adaptable.Factory.getAdapter(filters, Preview.class, null);
			if (preview != null) {
				while (is.read(fb) != -1) {
					fb.flip();
					preview.preview(fb);
					fb.clear();
				}
				// reset channel to read once again
				try {
					is.close();
				} catch (IOException ex) {
					repo.getContext().getLog().dump(getClass(), Info, ex, null);
				}
				is = f.newInputChannel();
				fb.clear();
			}
			while (is.read(fb) != -1 && check.sameSoFar()) {
				fb.flip();
				filters.write(fb);
				fb.compact();
			}
			return check.ultimatelyTheSame();
		} catch (CancelledException ex) {
			repo.getContext().getLog().dump(getClass(), Warn, ex, "Unexpected cancellation");
			return check.ultimatelyTheSame();
		} catch (IOException ex) {
			throw new HgInvalidFileException("File comparison failed", ex).setFileName(p);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException ex) {
					repo.getContext().getLog().dump(getClass(), Info, ex, null);
				}
			}
		}
	}

	/**
	 * @return <code>true</code> if flags are the same
	 */
	private boolean checkFlagsEqual(FileInfo f, HgManifest.Flags originalManifestFlags) {
		boolean same = true;
		if (repoWalker.supportsLinkFlag()) {
			if (originalManifestFlags == HgManifest.Flags.Link) {
				return f.isSymlink();
			}
			// original flag is not link, hence flags are the same if file is not link, too.
			same = !f.isSymlink();
		} // otherwise treat flags the same
		if (repoWalker.supportsExecFlag()) {
			if (originalManifestFlags == HgManifest.Flags.Exec) {
				return f.isExecutable();
			}
			// original flag has no executable attribute, hence file shall not be executable, too
			same = same || !f.isExecutable();
		}
		return same;
	}
	
	private boolean checkFlagsEqual(FileInfo f, int dirstateFileMode) {
		// source/include/linux/stat.h
		final int S_IFLNK = 0120000, S_IXUSR = 00100;
		// TODO post-1.0 HgManifest.Flags.parse(int)
		if ((dirstateFileMode & S_IFLNK) == S_IFLNK) {
			return checkFlagsEqual(f, HgManifest.Flags.Link);
		}
		if ((dirstateFileMode & S_IXUSR) == S_IXUSR) {
			return checkFlagsEqual(f, HgManifest.Flags.Exec);
		}
		return checkFlagsEqual(f, HgManifest.Flags.RegularFile); // no flags
	}

	/**
	 * Configure status collector to consider only subset of a working copy tree. Tries to be as effective as possible, and to 
	 * traverse only relevant part of working copy on the filesystem.
	 * 
	 * @param hgRepo repository
	 * @param paths repository-relative files and/or directories. Directories are processed recursively. 
	 * 
	 * @return new instance of {@link HgWorkingCopyStatusCollector}, ready to {@link #walk(int, HgStatusInspector) walk} associated working copy 
	 */
	public static HgWorkingCopyStatusCollector create(HgRepository hgRepo, Path... paths) {
		ArrayList<Path> f = new ArrayList<Path>(5);
		ArrayList<Path> d = new ArrayList<Path>(5);
		for (Path p : paths) {
			if (p.isDirectory()) {
				d.add(p);
			} else {
				f.add(p);
			}
		}
//		final Path[] dirs = f.toArray(new Path[d.size()]);
		if (d.isEmpty()) {
			final Path[] files = f.toArray(new Path[f.size()]);
			FileIterator fi = new FileListIterator(hgRepo.getContext(), hgRepo.getWorkingDir(), files);
			return new HgWorkingCopyStatusCollector(hgRepo, fi);
		}
		//
		
		//FileIterator fi = file.isDirectory() ? new DirFileIterator(hgRepo, file) : new FileListIterator(, file);
		FileIterator fi = new HgInternals(hgRepo).createWorkingDirWalker(new PathScope(true, paths));
		return new HgWorkingCopyStatusCollector(hgRepo, fi);
	}
	
	/**
	 * Configure collector object to calculate status for matching files only. 
	 * This method may be less effective than explicit list of files as it iterates over whole repository 
	 * (thus supplied matcher doesn't need to care if directories to files in question are also in scope, 
	 * see {@link FileWalker#FileWalker(File, Path.Source, Path.Matcher)})
	 *  
	 * @return new instance of {@link HgWorkingCopyStatusCollector}, ready to {@link #walk(int, HgStatusInspector) walk} associated working copy
	 */
	public static HgWorkingCopyStatusCollector create(HgRepository hgRepo, Path.Matcher scope) {
		FileIterator w = new HgInternals(hgRepo).createWorkingDirWalker(null);
		FileIterator wf = (scope == null || scope instanceof Path.Matcher.Any) ? w : new FileIteratorFilter(w, scope);
		// the reason I need to iterate over full repo and apply filter is that I have no idea whatsoever about
		// patterns in the scope. I.e. if scope lists a file (PathGlobMatcher("a/b/c.txt")), FileWalker won't get deep
		// to the file unless matcher would also explicitly include "a/", "a/b/" in scope. Since I can't rely
		// users would write robust matchers, and I don't see a decent way to enforce that (i.e. factory to produce
		// correct matcher from Path is much like what PathScope does, and can be accessed directly with #create(repo, Path...)
		// method above/
		return new HgWorkingCopyStatusCollector(hgRepo, wf);
	}

	private static class FileListIterator implements FileIterator {
		private final File dir;
		private final Path[] paths;
		private int index;
		private RegularFileInfo nextFile;
		private final boolean execCap, linkCap;
		private final SessionContext sessionContext;

		public FileListIterator(SessionContext ctx, File startDir, Path... files) {
			sessionContext = ctx;
			dir = startDir;
			paths = files;
			reset();
			execCap = Internals.checkSupportsExecutables(startDir);
			linkCap = Internals.checkSupportsSymlinks(startDir);
		}

		public void reset() {
			index = -1;
			nextFile = new RegularFileInfo(sessionContext, execCap, linkCap);
		}

		public boolean hasNext() {
			return paths.length > 0 && index < paths.length-1;
		}

		public void next() {
			index++;
			if (index == paths.length) {
				throw new NoSuchElementException();
			}
			nextFile.init(new File(dir, paths[index].toString()));
		}

		public Path name() {
			return paths[index];
		}

		public FileInfo file() {
			return nextFile;
		}

		public boolean inScope(Path file) {
			for (int i = 0; i < paths.length; i++) {
				if (paths[i].equals(file)) {
					return true;
				}
			}
			return false;
		}
		
		public boolean supportsExecFlag() {
			return execCap;
		}
		
		public boolean supportsLinkFlag() {
			return linkCap;
		}
	}
	
	private static class FileIteratorFilter implements FileIterator {
		private final Path.Matcher filter;
		private final FileIterator walker;
		private boolean didNext = false;

		public FileIteratorFilter(FileIterator fileWalker, Path.Matcher filterMatcher) {
			assert fileWalker != null;
			assert filterMatcher != null;
			filter = filterMatcher;
			walker = fileWalker;
		}

		public void reset() throws IOException {
			walker.reset();
		}

		public boolean hasNext() throws IOException {
			while (walker.hasNext()) {
				walker.next();
				if (filter.accept(walker.name())) {
					didNext = true;
					return true;
				}
			}
			return false;
		}

		public void next() throws IOException {
			if (didNext) {
				didNext = false;
			} else {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
			}
		}

		public Path name() {
			return walker.name();
		}

		public FileInfo file() {
			return walker.file();
		}

		public boolean inScope(Path file) {
			return filter.accept(file);
		}
		
		public boolean supportsExecFlag() {
			return walker.supportsExecFlag();
		}
		
		public boolean supportsLinkFlag() {
			return walker.supportsLinkFlag();
		}
	}
}
