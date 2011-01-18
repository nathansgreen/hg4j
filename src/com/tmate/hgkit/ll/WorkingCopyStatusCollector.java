/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import com.tmate.hgkit.fs.FileWalker;

/**
 *
 * @author artem
 */
public class WorkingCopyStatusCollector {

	private final HgRepository repo;
	private final FileWalker repoWalker;
	private HgDirstate dirstate;
	private StatusCollector baseRevisionCollector;

	public WorkingCopyStatusCollector(HgRepository hgRepo, FileWalker hgRepoWalker) {
		this.repo = hgRepo;
		this.repoWalker = hgRepoWalker;
	}
	
	/**
	 * Optionally, supply a collector instance that may cache (or have already cached) base revision
	 * @param sc may be null
	 */
	public void setBaseRevisionCollector(StatusCollector sc) {
		baseRevisionCollector = sc;
	}
	
	private HgDirstate getDirstate() {
		if (dirstate == null) {
			if (repo instanceof LocalHgRepo) {
				dirstate = ((LocalHgRepo) repo).loadDirstate();
			} else {
				dirstate = new HgDirstate();
			}
		}
		return dirstate;
	}

	// may be invoked few times
	public void walk(int baseRevision, StatusCollector.Inspector inspector) {
		final HgIgnore hgIgnore = ((LocalHgRepo) repo).loadIgnore(); // FIXME hack
		TreeSet<String> knownEntries = getDirstate().all();
		final boolean isTipBase = baseRevision == TIP || baseRevision == repo.getManifest().getRevisionCount();
		StatusCollector.ManifestRevisionInspector collect = null;
		Set<String> baseRevFiles = Collections.emptySet();
		if (!isTipBase) {
			if (baseRevisionCollector != null) {
				collect = baseRevisionCollector.raw(baseRevision);
			} else {
				collect = new StatusCollector.ManifestRevisionInspector(baseRevision, baseRevision);
				repo.getManifest().walk(baseRevision, baseRevision, collect);
			}
			baseRevFiles = new TreeSet<String>(collect.files(baseRevision));
		}
		repoWalker.reset();
		while (repoWalker.hasNext()) {
			repoWalker.next();
			String fname = repoWalker.name();
			File f = repoWalker.file();
			if (hgIgnore.isIgnored(fname)) {
				inspector.ignored(fname);
			} else if (knownEntries.remove(fname)) {
				// modified, added, removed, clean
				if (collect != null) { // need to check against base revision, not FS file
					Nodeid nid1 = collect.nodeid(baseRevision, fname);
					String flags = collect.flags(baseRevision, fname);
					checkLocalStatusAgainstBaseRevision(baseRevFiles, nid1, flags, fname, f, inspector);
					baseRevFiles.remove(fname);
				} else {
					checkLocalStatusAgainstFile(fname, f, inspector);
				}
			} else {
				inspector.unknown(fname);
			}
		}
		if (collect != null) {
			for (String r : baseRevFiles) {
				inspector.removed(r);
			}
		}
		for (String m : knownEntries) {
			// removed from the repository and missing from working dir shall not be reported as 'deleted' 
			if (getDirstate().checkRemoved(m) == null) {
				inspector.missing(m);
			}
		}
	}

	public StatusCollector.Record status(int baseRevision) {
		StatusCollector.Record rv = new StatusCollector.Record();
		walk(baseRevision, rv);
		return rv;
	}

	//********************************************

	
	private void checkLocalStatusAgainstFile(String fname, File f, StatusCollector.Inspector inspector) {
		HgDirstate.Record r;
		if ((r = getDirstate().checkNormal(fname)) != null) {
			// either clean or modified
			if (f.lastModified() / 1000 == r.time && r.size == f.length()) {
				inspector.clean(fname);
			} else {
				// FIXME check actual content to avoid false modified files
				inspector.modified(fname);
			}
		} else if ((r = getDirstate().checkAdded(fname)) != null) {
			if (r.name2 == null) {
				inspector.added(fname);
			} else {
				inspector.copied(fname, r.name2);
			}
		} else if ((r = getDirstate().checkRemoved(fname)) != null) {
			inspector.removed(fname);
		} else if ((r = getDirstate().checkMerged(fname)) != null) {
			inspector.modified(fname);
		}
	}
	
	// XXX refactor checkLocalStatus methods in more OO way
	private void checkLocalStatusAgainstBaseRevision(Set<String> baseRevNames, Nodeid nid1, String flags, String fname, File f, StatusCollector.Inspector inspector) {
		// fname is in the dirstate, either Normal, Added, Removed or Merged
		HgDirstate.Record r;
		if (nid1 == null) {
			// normal: added?
			// added: not known at the time of baseRevision, shall report
			// merged: was not known, report as added?
			if ((r = getDirstate().checkAdded(fname)) != null) {
				if (r.name2 != null && baseRevNames.contains(r.name2)) {
					baseRevNames.remove(r.name2);
					inspector.copied(r.name2, fname);
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
					// XXX consider adding HgDataDile.compare(File/byte[]/whatever) operation to optimize comparison
					if (areTheSame(f, fileNode.content(nid1))) {
						inspector.clean(fname);
					} else {
						inspector.modified(fname);
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

	private static String todoGenerateFlags(String fname) {
		// FIXME implement
		return null;
	}
	private static boolean areTheSame(File f, byte[] data) {
		try {
			BufferedInputStream is = new BufferedInputStream(new FileInputStream(f));
			int i = 0;
			while (i < data.length && data[i] == is.read()) {
				i++; // increment only for successful match, otherwise won't tell last byte in data was the same as read from the stream
			}
			return i == data.length && is.read() == -1; // although data length is expected to be the same (see caller), check that we reached EOF, no more data left.
		} catch (IOException ex) {
			ex.printStackTrace(); // log warn
		}
		return false;
	}

}