/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
package org.tmatesoft.hg.console;

import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgLogCommand.FileRevision;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;


/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Log {

	// -agentlib:hprof=heap=sites,depth=10,etc might be handy to debug speed/memory issues
	
	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		final Dump dump = new Dump(hgRepo);
		dump.complete = cmdLineOpts.getBoolean("--debug");
		dump.verbose = cmdLineOpts.getBoolean("-v", "--verbose");
		dump.reverseOrder = true;
		HgLogCommand cmd = new HgLogCommand(hgRepo);
		for (String u : cmdLineOpts.getList("-u", "--user")) {
			cmd.user(u);
		}
		for (String b : cmdLineOpts.getList("-b", "--branches")) {
			cmd.branch(b);
		}
		int limit = cmdLineOpts.getSingleInt(-1, "-l", "--limit");
		if (limit != -1) {
			cmd.limit(limit);
		}
		List<String> files = cmdLineOpts.getList("");
		final long start = System.currentTimeMillis();
		if (files.isEmpty()) {
			if (limit == -1) {
				// no revisions and no limit
				cmd.execute(dump);
			} else {
				// in fact, external (to dump inspector) --limit processing yelds incorrect results when other args
				// e.g. -u or -b are used (i.e. with -u shall give <limit> csets with user, not check last <limit> csets for user 
				int[] r = new int[] { 0, hgRepo.getChangelog().getRevisionCount() };
				if (fixRange(r, dump.reverseOrder, limit) == 0) {
					System.out.println("No changes");
					return;
				}
				cmd.range(r[0], r[1]).execute(dump);
			}
			dump.complete();
		} else {
			for (String fname : files) {
				HgDataFile f1 = hgRepo.getFileNode(fname);
				System.out.println("History of the file: " + f1.getPath());
				if (limit == -1) {
					cmd.file(f1.getPath(), true).execute(dump);
				} else {
					int[] r = new int[] { 0, f1.getRevisionCount() };
					if (fixRange(r, dump.reverseOrder, limit) == 0) {
						System.out.println("No changes");
						continue;
					}
					cmd.range(r[0], r[1]).file(f1.getPath(), true).execute(dump);
				}
				dump.complete();
			}
		}
//		cmd = null;
		System.out.println("Total time:" + (System.currentTimeMillis() - start));
//		Main.force_gc();
	}
	
	private static int fixRange(int[] start_end, boolean reverse, int limit) {
		assert start_end.length == 2;
		if (limit < start_end[1]) {
			if (reverse) {
				// adjust left boundary of the range
				start_end[0] = start_end[1] - limit;
			} else {
				start_end[1] = limit; // adjust right boundary
			}
		}
		int rv = start_end[1] - start_end[0];
		start_end[1]--; // range needs index, not length
		return rv;
	}

	private static final class Dump implements HgLogCommand.FileHistoryHandler {
		// params
		boolean complete = false; // roughly --debug
		boolean reverseOrder = false;
		boolean verbose = true; // roughly -v
		// own
		private LinkedList<String> l = new LinkedList<String>();
		private final HgRepository repo;
//		private HgChangelog.ParentWalker changelogWalker;
		private final int tip ;

		public Dump(HgRepository hgRepo) {
			repo = hgRepo;
			tip = hgRepo.getChangelog().getLastRevision();
		}
		
		public void copy(FileRevision from, FileRevision to) {
			System.out.printf("Got notified that %s(%s) was originally known as %s(%s)\n", to.getPath(), to.getRevision(), from.getPath(), from.getRevision());
		}

		public void next(HgChangeset changeset) {
			final String s = print(changeset);
			if (reverseOrder) {
				// XXX in fact, need to insert s into l according to changeset.getRevision()
				// because when file history is being followed, revisions of the original file (with smaller revNumber)
				// are reported *after* revisions of present file and with addFirst appear above them
				l.addFirst(s);
			} else {
				System.out.print(s);
			}
		}
		
		public void complete() {
			if (!reverseOrder) {
				return;
			}
			for (String s : l) {
				System.out.print(s);
			}
			l.clear();
//			changelogWalker = null;
		}

		private String print(HgChangeset cset) {
			StringBuilder sb = new StringBuilder();
			Formatter f = new Formatter(sb);
			final Nodeid csetNodeid = cset.getNodeid();
			f.format("changeset:   %d:%s\n", cset.getRevision(), complete ? csetNodeid : csetNodeid.shortNotation());
			if (cset.getRevision() == tip || repo.getTags().isTagged(csetNodeid)) {
				
				sb.append("tag:         ");
				for (String t : repo.getTags().tags(csetNodeid)) {
					sb.append(t);
					sb.append(' ');
				}
				if (cset.getRevision() == tip) {
					sb.append("tip");
				}
				sb.append('\n');
			}
			if (complete) {
//				if (changelogWalker == null) {
//					changelogWalker = repo.getChangelog().new ParentWalker();
//					changelogWalker.init();
//				}
//				Nodeid p1 = changelogWalker.safeFirstParent(csetNodeid);
//				Nodeid p2 = changelogWalker.safeSecondParent(csetNodeid);
				Nodeid p1 = cset.getFirstParentRevision();
				Nodeid p2 = cset.getSecondParentRevision();
				int p1x = p1 == Nodeid.NULL ? -1 : repo.getChangelog().getLocalRevision(p1);
				int p2x = p2 == Nodeid.NULL ? -1 : repo.getChangelog().getLocalRevision(p2);
				int mx = repo.getManifest().getLocalRevision(cset.getManifestRevision());
				f.format("parent:      %d:%s\nparent:      %d:%s\nmanifest:    %d:%s\n", p1x, p1, p2x, p2, mx, cset.getManifestRevision());
			}
			f.format("user:        %s\ndate:        %s\n", cset.getUser(), cset.getDate());
			if (!complete && verbose) {
				final List<Path> files = cset.getAffectedFiles();
				sb.append("files:      ");
				for (Path s : files) {
					sb.append(' ');
					sb.append(s);
				}
				sb.append('\n');
			}
			if (complete) {
				if (!cset.getModifiedFiles().isEmpty()) {
					sb.append("files:      ");
					for (FileRevision s : cset.getModifiedFiles()) {
						sb.append(' ');
						sb.append(s.getPath());
					}
					sb.append('\n');
				}
				if (!cset.getAddedFiles().isEmpty()) {
					sb.append("files+:     ");
					for (FileRevision s : cset.getAddedFiles()) {
						sb.append(' ');
						sb.append(s.getPath());
					}
					sb.append('\n');
				}
				if (!cset.getRemovedFiles().isEmpty()) {
					sb.append("files-:     ");
					for (Path s : cset.getRemovedFiles()) {
						sb.append(' ');
						sb.append(s);
					}
					sb.append('\n');
				}
//				if (cset.extras() != null) {
//					sb.append("extra:      ");
//					for (Map.Entry<String, String> e : cset.extras().entrySet()) {
//						sb.append(' ');
//						sb.append(e.getKey());
//						sb.append('=');
//						sb.append(e.getValue());
//					}
//					sb.append('\n');
//				}
			}
			if (complete || verbose) {
				f.format("description:\n%s\n\n", cset.getComment());
			} else {
				f.format("summary:     %s\n\n", cset.getComment());
			}
			return sb.toString();
		}
	}
}
