/*
 * Copyright (c) 2011 TMate Software Ltd
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.core.HgDataStreamException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FilterByteChannel;
import org.tmatesoft.hg.internal.PathScope;
import org.tmatesoft.hg.repo.HgStatusCollector.ManifestRevisionInspector;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.FileIterator;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathPool;
import org.tmatesoft.hg.util.PathRewrite;

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
	private PathPool pathPool;

	public HgWorkingCopyStatusCollector(HgRepository hgRepo) {
		this(hgRepo, new HgInternals(hgRepo).createWorkingDirWalker(null));
	}

	// FIXME document cons
	public HgWorkingCopyStatusCollector(HgRepository hgRepo, FileIterator hgRepoWalker) {
		repo = hgRepo;
		repoWalker = hgRepoWalker;
	}
	
	/**
	 * Optionally, supply a collector instance that may cache (or have already cached) base revision
	 * @param sc may be null
	 */
	public void setBaseRevisionCollector(HgStatusCollector sc) {
		baseRevisionCollector = sc;
	}

	/*package-local*/ PathPool getPathPool() {
		if (pathPool == null) {
			if (baseRevisionCollector == null) {
				pathPool = new PathPool(new PathRewrite.Empty());
			} else {
				return baseRevisionCollector.getPathPool();
			}
		}
		return pathPool;
	}

	public void setPathPool(PathPool pathPool) {
		this.pathPool = pathPool;
	}

	
	private HgDirstate getDirstate() {
		if (dirstate == null) {
			dirstate = repo.loadDirstate();
		}
		return dirstate;
	}

	// may be invoked few times
	public void walk(int baseRevision, HgStatusInspector inspector) {
		if (HgInternals.wrongLocalRevision(baseRevision) || baseRevision == BAD_REVISION || baseRevision == WORKING_COPY) {
			throw new IllegalArgumentException(String.valueOf(baseRevision));
		}
		final HgIgnore hgIgnore = repo.getIgnore();
		TreeSet<String> knownEntries = getDirstate().all();
		final boolean isTipBase;
		if (baseRevision == TIP) {
			baseRevision = repo.getChangelog().getLastRevision();
			isTipBase = true;
		} else {
			isTipBase = baseRevision == repo.getChangelog().getLastRevision();
		}
		HgStatusCollector.ManifestRevisionInspector collect = null;
		Set<String> baseRevFiles = Collections.emptySet(); // files from base revision not affected by status calculation 
		if (!isTipBase) {
			if (baseRevisionCollector != null) {
				collect = baseRevisionCollector.raw(baseRevision);
			} else {
				collect = new HgStatusCollector.ManifestRevisionInspector(null, null);
				repo.getManifest().walk(baseRevision, baseRevision, collect);
			}
			baseRevFiles = new TreeSet<String>(collect.files());
		}
		if (inspector instanceof HgStatusCollector.Record) {
			HgStatusCollector sc = baseRevisionCollector == null ? new HgStatusCollector(repo) : baseRevisionCollector;
			((HgStatusCollector.Record) inspector).init(baseRevision, BAD_REVISION, sc);
		}
		repoWalker.reset();
		final PathPool pp = getPathPool();
		while (repoWalker.hasNext()) {
			repoWalker.next();
			Path fname = pp.path(repoWalker.name());
			File f = repoWalker.file();
			if (!f.exists()) {
				// file coming from iterator doesn't exist.
				if (knownEntries.remove(fname.toString())) {
					if (getDirstate().checkRemoved(fname) == null) {
						inspector.missing(fname);
					} else {
						inspector.removed(fname);
					}
					// do not report it as removed later
					if (collect != null) {
						baseRevFiles.remove(fname.toString());
					}
				} else {
					// chances are it was known in baseRevision. We may rely
					// that later iteration over baseRevFiles leftovers would yield correct Removed,
					// but it doesn't hurt to be explicit (provided we know fname *is* inScope of the FileIterator
					if (collect != null && baseRevFiles.remove(fname.toString())) {
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
			assert f.isFile();
			if (knownEntries.remove(fname.toString())) {
				// tracked file.
				// modified, added, removed, clean
				if (collect != null) { // need to check against base revision, not FS file
					checkLocalStatusAgainstBaseRevision(baseRevFiles, collect, baseRevision, fname, f, inspector);
				} else {
					checkLocalStatusAgainstFile(fname, f, inspector);
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
			for (String r : baseRevFiles) {
				final Path fromBase = pp.path(r);
				if (repoWalker.inScope(fromBase)) {
					inspector.removed(fromBase);
				}
			}
		}
		for (String m : knownEntries) {
			if (!repoWalker.inScope(pp.path(m))) {
				// do not report as missing/removed those FileIterator doesn't care about.
				continue;
			}
			// missing known file from a working dir  
			if (getDirstate().checkRemoved(m) == null) {
				// not removed from the repository = 'deleted'  
				inspector.missing(pp.path(m));
			} else {
				// removed from the repo
				// if we check against non-tip revision, do not report files that were added past that revision and now removed.
				if (collect == null || baseRevFiles.contains(m)) {
					inspector.removed(pp.path(m));
				}
			}
		}
	}

	public HgStatusCollector.Record status(int baseRevision) {
		HgStatusCollector.Record rv = new HgStatusCollector.Record();
		walk(baseRevision, rv);
		return rv;
	}

	//********************************************

	
	private void checkLocalStatusAgainstFile(Path fname, File f, HgStatusInspector inspector) {
		HgDirstate.Record r;
		if ((r = getDirstate().checkNormal(fname)) != null) {
			// either clean or modified
			if (f.lastModified() / 1000 == r.time && r.size == f.length()) {
				inspector.clean(getPathPool().path(fname));
			} else {
				// check actual content to avoid false modified files
				HgDataFile df = repo.getFileNode(fname);
				if (!areTheSame(f, df, HgRepository.TIP)) {
					inspector.modified(df.getPath());
				} else {
					inspector.clean(df.getPath());
				}
			}
		} else if ((r = getDirstate().checkAdded(fname)) != null) {
			if (r.name2 == null) {
				inspector.added(getPathPool().path(fname));
			} else {
				inspector.copied(getPathPool().path(r.name2), getPathPool().path(fname));
			}
		} else if ((r = getDirstate().checkRemoved(fname)) != null) {
			inspector.removed(getPathPool().path(fname));
		} else if ((r = getDirstate().checkMerged(fname)) != null) {
			inspector.modified(getPathPool().path(fname));
		}
	}
	
	// XXX refactor checkLocalStatus methods in more OO way
	private void checkLocalStatusAgainstBaseRevision(Set<String> baseRevNames, ManifestRevisionInspector collect, int baseRevision, Path fname, File f, HgStatusInspector inspector) {
		// fname is in the dirstate, either Normal, Added, Removed or Merged
		Nodeid nid1 = collect.nodeid(fname.toString());
		String flags = collect.flags(fname.toString());
		HgDirstate.Record r;
		if (nid1 == null) {
			// normal: added?
			// added: not known at the time of baseRevision, shall report
			// merged: was not known, report as added?
			if ((r = getDirstate().checkNormal(fname)) != null) {
				try {
					Path origin = HgStatusCollector.getOriginIfCopy(repo, fname, baseRevNames, baseRevision);
					if (origin != null) {
						inspector.copied(getPathPool().path(origin), fname);
						return;
					}
				} catch (HgDataStreamException ex) {
					ex.printStackTrace();
					// FIXME report to a mediator, continue status collection
				}
			} else if ((r = getDirstate().checkAdded(fname)) != null) {
				if (r.name2 != null && baseRevNames.contains(r.name2)) {
					baseRevNames.remove(r.name2); // XXX surely I shall not report rename source as Removed?
					inspector.copied(getPathPool().path(r.name2), fname);
					return;
				}
				// fall-through, report as added
			} else if (getDirstate().checkRemoved(fname) != null) {
				// removed: removed file was not known at the time of baseRevision, and we should not report it as removed
				return;
			}
			inspector.added(fname);
		} else {
			// was known; check whether clean or modified
			// when added - seems to be the case of a file added once again, hence need to check if content is different
			if ((r = getDirstate().checkNormal(fname)) != null || (r = getDirstate().checkMerged(fname)) != null || (r = getDirstate().checkAdded(fname)) != null) {
				// either clean or modified
				HgDataFile fileNode = repo.getFileNode(fname);
				final int lengthAtRevision = fileNode.length(nid1);
				if (r.size /* XXX File.length() ?! */ != lengthAtRevision || flags != todoGenerateFlags(fname /*java.io.File*/)) {
					inspector.modified(fname);
				} else {
					// check actual content to see actual changes
					if (areTheSame(f, fileNode, fileNode.getLocalRevision(nid1))) {
						inspector.clean(fname);
					} else {
						inspector.modified(fname);
					}
				}
				baseRevNames.remove(fname.toString()); // consumed, processed, handled.
			} else if (getDirstate().checkRemoved(fname) != null) {
				// was known, and now marked as removed, report it right away, do not rely on baseRevNames processing later
				inspector.removed(fname);
				baseRevNames.remove(fname.toString()); // consumed, processed, handled.
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

	private boolean areTheSame(File f, HgDataFile dataFile, int localRevision) {
		// XXX consider adding HgDataDile.compare(File/byte[]/whatever) operation to optimize comparison
		ByteArrayChannel bac = new ByteArrayChannel();
		boolean ioFailed = false;
		try {
			// need content with metadata striped off - although theoretically chances are metadata may be different,
			// WC doesn't have it anyway 
			dataFile.content(localRevision, bac);
		} catch (CancelledException ex) {
			// silently ignore - can't happen, ByteArrayChannel is not cancellable
		} catch (IOException ex) {
			ioFailed = true;
		} catch (HgException ex) {
			ioFailed = true;
		}
		return !ioFailed && areTheSame(f, bac.toArray(), dataFile.getPath());
	}
	
	private boolean areTheSame(File f, final byte[] data, Path p) {
		FileInputStream fis = null;
		try {
			try {
				fis = new FileInputStream(f);
				FileChannel fc = fis.getChannel();
				ByteBuffer fb = ByteBuffer.allocate(min(1 + data.length * 2 /*to fit couple of lines appended; never zero*/, 8192));
				class Check implements ByteChannel {
					final boolean debug = false; // XXX may want to add global variable to allow clients to turn 
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
									buffer.get(xx);
									System.out.print("expected >>" + new String(data, max(0, x - 4), 20) + "<< but got >>");
									System.out.println(new String(xx) + "<<");
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
				FilterByteChannel filters = new FilterByteChannel(check, repo.getFiltersFromWorkingDirToRepo(p));
				while (fc.read(fb) != -1 && check.sameSoFar()) {
					fb.flip();
					filters.write(fb);
					fb.compact();
				}
				return check.ultimatelyTheSame();
			} catch (IOException ex) {
				if (fis != null) {
					fis.close();
				}
				ex.printStackTrace(); // log warn
			}
		} catch (/*TODO typed*/Exception ex) {
			ex.printStackTrace();
		}
		return false;
	}

	private static String todoGenerateFlags(Path fname) {
		// FIXME implement
		return null;
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
	@Experimental(reason="Provisional API")
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
			FileIterator fi = new FileListIterator(hgRepo.getWorkingDir(), files);
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
	@Experimental(reason="Provisional API. May add boolean strict argument for those who write smart matchers that can be used in FileWalker")
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
		private File nextFile; // cache file() in case it's called more than once

		public FileListIterator(File startDir, Path... files) {
			dir = startDir;
			paths = files;
			reset();
		}

		public void reset() {
			index = -1;
			nextFile = null;
		}

		public boolean hasNext() {
			return paths.length > 0 && index < paths.length-1;
		}

		public void next() {
			index++;
			if (index == paths.length) {
				throw new NoSuchElementException();
			}
			nextFile = new File(dir, paths[index].toString());
		}

		public Path name() {
			return paths[index];
		}

		public File file() {
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

		public void reset() {
			walker.reset();
		}

		public boolean hasNext() {
			while (walker.hasNext()) {
				walker.next();
				if (filter.accept(walker.name())) {
					didNext = true;
					return true;
				}
			}
			return false;
		}

		public void next() {
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

		public File file() {
			return walker.file();
		}

		public boolean inScope(Path file) {
			return filter.accept(file);
		}
	}
}
