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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.console;

import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.Cset;
import org.tmatesoft.hg.core.LogCommand;
import org.tmatesoft.hg.core.LogCommand.FileRevision;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Log {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		final Dump dump = new Dump(hgRepo);
		dump.complete = true; //cmdLineOpts;
		dump.verbose = false; //cmdLineOpts;
		dump.reverseOrder = true;
		LogCommand cmd = new LogCommand(hgRepo);
		if (cmdLineOpts.users != null) {
			for (String u : cmdLineOpts.users) {
				cmd.user(u);
			}
		}
		if (cmdLineOpts.branches != null) {
			for (String b : cmdLineOpts.branches) {
				cmd.branch(b);
			}
		}
		if (cmdLineOpts.limit != -1) {
			cmd.limit(cmdLineOpts.limit);
			
		}
		if (cmdLineOpts.files.isEmpty()) {
			if (cmdLineOpts.limit == -1) {
				// no revisions and no limit
				cmd.execute(dump);
			} else {
				// in fact, external (to dump inspector) --limit processing yelds incorrect results when other args
				// e.g. -u or -b are used (i.e. with -u shall give <limit> csets with user, not check last <limit> csets for user 
				int[] r = new int[] { 0, hgRepo.getChangelog().getRevisionCount() };
				if (fixRange(r, dump.reverseOrder, cmdLineOpts.limit) == 0) {
					System.out.println("No changes");
					return;
				}
				cmd.range(r[0], r[1]).execute(dump);
			}
			dump.complete();
		} else {
			for (String fname : cmdLineOpts.files) {
				HgDataFile f1 = hgRepo.getFileNode(fname);
				System.out.println("History of the file: " + f1.getPath());
				String normalizesName = hgRepo.getPathHelper().rewrite(fname);
				if (cmdLineOpts.limit == -1) {
					cmd.file(Path.create(normalizesName), true).execute(dump);
				} else {
					int[] r = new int[] { 0, f1.getRevisionCount() };
					if (fixRange(r, dump.reverseOrder, cmdLineOpts.limit) == 0) {
						System.out.println("No changes");
						continue;
					}
					cmd.range(r[0], r[1]).file(Path.create(normalizesName), true).execute(dump);
				}
				dump.complete();
			}
		}
		//
		// XXX new ChangelogWalker().setFile("hello.c").setRevisionRange(1, 4).accept(new Visitor);
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

	private static final class Dump implements LogCommand.FileHistoryHandler {
		// params
		boolean complete = false; // roughly --debug
		boolean reverseOrder = false;
		boolean verbose = true; // roughly -v
		// own
		private LinkedList<String> l = new LinkedList<String>();
		private final HgRepository repo;
		private Changelog.ParentWalker changelogWalker;
		private final int tip ;

		public Dump(HgRepository hgRepo) {
			repo = hgRepo;
			tip = hgRepo.getChangelog().getRevisionCount() - 1;
		}
		
		public void copy(FileRevision from, FileRevision to) {
			System.out.printf("Got notified that %s(%s) was originally known as %s(%s)\n", to.getPath(), to.getRevision(), from.getPath(), from.getRevision());
		}

		public void next(Cset changeset) {
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
			changelogWalker = null;
		}

		private String print(Cset cset) {
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
				if (changelogWalker == null) {
					changelogWalker = repo.getChangelog().new ParentWalker();
					changelogWalker.init();
				}
				Nodeid p1 = changelogWalker.safeFirstParent(csetNodeid);
				Nodeid p2 = changelogWalker.safeSecondParent(csetNodeid);
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
