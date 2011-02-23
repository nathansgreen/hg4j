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
import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.FilterByteChannel;
import org.tmatesoft.hg.repo.HgStatusCollector.ManifestRevisionInspector;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.FileIterator;
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
		this(hgRepo, hgRepo.createWorkingDirWalker());
	}

	HgWorkingCopyStatusCollector(HgRepository hgRepo, FileIterator hgRepoWalker) {
		this.repo = hgRepo;
		this.repoWalker = hgRepoWalker;
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
		final HgIgnore hgIgnore = repo.getIgnore();
		TreeSet<String> knownEntries = getDirstate().all();
		final boolean isTipBase;
		if (baseRevision == TIP) {
			baseRevision = repo.getManifest().getRevisionCount() - 1;
			isTipBase = true;
		} else {
			isTipBase = baseRevision == repo.getManifest().getRevisionCount() - 1;
		}
		HgStatusCollector.ManifestRevisionInspector collect = null;
		Set<String> baseRevFiles = Collections.emptySet();
		if (!isTipBase) {
			if (baseRevisionCollector != null) {
				collect = baseRevisionCollector.raw(baseRevision);
			} else {
				collect = new HgStatusCollector.ManifestRevisionInspector();
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
			Path fname = repoWalker.name();
			File f = repoWalker.file();
			if (hgIgnore.isIgnored(fname)) {
				inspector.ignored(pp.path(fname));
			} else if (knownEntries.remove(fname.toString())) {
				// modified, added, removed, clean
				if (collect != null) { // need to check against base revision, not FS file
					checkLocalStatusAgainstBaseRevision(baseRevFiles, collect, baseRevision, fname, f, inspector);
					baseRevFiles.remove(fname.toString());
				} else {
					checkLocalStatusAgainstFile(fname, f, inspector);
				}
			} else {
				inspector.unknown(pp.path(fname));
			}
		}
		if (collect != null) {
			for (String r : baseRevFiles) {
				inspector.removed(pp.path(r));
			}
		}
		for (String m : knownEntries) {
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
				if (!areTheSame(f, df.content(), df.getPath())) {
					inspector.modified(df.getPath());
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
				Path origin = HgStatusCollector.getOriginIfCopy(repo, fname, baseRevNames, baseRevision);
				if (origin != null) {
					inspector.copied(getPathPool().path(origin), getPathPool().path(fname));
					return;
				}
			} else if ((r = getDirstate().checkAdded(fname)) != null) {
				if (r.name2 != null && baseRevNames.contains(r.name2)) {
					baseRevNames.remove(r.name2); // XXX surely I shall not report rename source as Removed?
					inspector.copied(getPathPool().path(r.name2), getPathPool().path(fname));
					return;
				}
				// fall-through, report as added
			} else if (getDirstate().checkRemoved(fname) != null) {
				// removed: removed file was not known at the time of baseRevision, and we should not report it as removed
				return;
			}
			inspector.added(getPathPool().path(fname));
		} else {
			// was known; check whether clean or modified
			// when added - seems to be the case of a file added once again, hence need to check if content is different
			if ((r = getDirstate().checkNormal(fname)) != null || (r = getDirstate().checkMerged(fname)) != null || (r = getDirstate().checkAdded(fname)) != null) {
				// either clean or modified
				HgDataFile fileNode = repo.getFileNode(fname);
				final int lengthAtRevision = fileNode.length(nid1);
				if (r.size /* XXX File.length() ?! */ != lengthAtRevision || flags != todoGenerateFlags(fname /*java.io.File*/)) {
					inspector.modified(getPathPool().path(fname));
				} else {
					// check actual content to see actual changes
					// XXX consider adding HgDataDile.compare(File/byte[]/whatever) operation to optimize comparison
					if (areTheSame(f, fileNode.content(nid1), fileNode.getPath())) {
						inspector.clean(getPathPool().path(fname));
					} else {
						inspector.modified(getPathPool().path(fname));
					}
				}
			}
			// only those left in idsMap after processing are reported as removed 
		}

		// TODO think over if content comparison may be done more effectively by e.g. calculating nodeid for a local file and comparing it with nodeid from manifest
		// we don't need to tell exact difference, hash should be enough to detect difference, and it doesn't involve reading historical file content, and it's relatively 
		// cheap to calc hash on a file (no need to keep it completely in memory). OTOH, if I'm right that the next approach is used for nodeids: 
		// changeset nodeid + hash(actual content) => entry (Nodeid) in the next Manifest
		// then it's sufficient to check parents from dirstate, and if they do not match parents from file's baseRevision (non matching parents means different nodeids).
		// The question is whether original Hg treats this case (same content, different parents and hence nodeids) as 'modified' or 'clean'
	}

	private boolean areTheSame(File f, final byte[] data, Path p) {
		FileInputStream fis = null;
		try {
			try {
				fis = new FileInputStream(f);
				FileChannel fc = fis.getChannel();
				ByteBuffer fb = ByteBuffer.allocate(min(data.length, 8192));
				final boolean[] checkValue = new boolean[] { true };
				ByteChannel check = new ByteChannel() {
					int x = 0;
					final boolean debug = false; // XXX may want to add global variable to allow clients to turn 
					public int write(ByteBuffer buffer) {
						for (int i = buffer.remaining(); i > 0; i--, x++) {
							if (data[x] != buffer.get()) {
								if (debug) {
									byte[] xx = new byte[15];
									if (buffer.position() > 5) {
										buffer.position(buffer.position() - 5);
									}
									buffer.get(xx);
									System.out.print("expected >>" + new String(data, max(0, x - 4), 20) + "<< but got >>");
									System.out.println(new String(xx) + "<<");
								}
								checkValue[0] = false;
								break;
							}
						}
						buffer.position(buffer.limit()); // mark as read
						return buffer.limit();
					}
				};
				FilterByteChannel filters = new FilterByteChannel(check, repo.getFiltersFromWorkingDirToRepo(p));
				while (fc.read(fb) != -1 && checkValue[0]) {
					fb.flip();
					filters.write(fb);
					fb.compact();
				}
				return checkValue[0];
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

}
