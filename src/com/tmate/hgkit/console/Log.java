/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.Changeset;
import com.tmate.hgkit.ll.HgDataFile;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;

/**
 * @author artem
 */
public class Log {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		final Dump dump = new Dump(hgRepo);
		dump.complete = false; //cmdLineOpts;
		dump.reverseOrder = true;
		if (cmdLineOpts.files.isEmpty()) {
			// no revisions and no limit
			if (cmdLineOpts.limit == -1) {
				hgRepo.getChangelog().all(dump);
			} else {
				int[] r = new int[] { 0, hgRepo.getChangelog().getRevisionCount() };
				if (fixRange(r, dump.reverseOrder, cmdLineOpts.limit) == 0) {
					System.out.println("No changes");
					return;
				}
				hgRepo.getChangelog().range(r[0], r[1], dump);
			}
			dump.complete();
		} else {
			for (String fname : cmdLineOpts.files) {
				HgDataFile f1 = hgRepo.getFileNode(fname);
				System.out.println("History of the file: " + f1.getPath());
				if (cmdLineOpts.limit == -1) {
					f1.history(dump);
				} else {
					int[] r = new int[] { 0, f1.getRevisionCount() };
					if (fixRange(r, dump.reverseOrder, cmdLineOpts.limit) == 0) {
						System.out.println("No changes");
						continue;
					}
					f1.history(r[0], r[1], dump);
				}
				dump.complete();
			}
		}
		//
//		System.out.println("\n\n=========================");
//		System.out.println("Range 1-3:");
//		f1.history(1,3, callback);
		//
		//new ChangelogWalker().setFile("hello.c").setRevisionRange(1, 4).accept(new Visitor);
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

	private static final class Dump implements Changeset.Inspector {
		// params
		boolean complete = false;
		boolean reverseOrder = false;
		// own
		private LinkedList<String> l = new LinkedList<String>();
		private final HgRepository repo;

		public Dump(HgRepository hgRepo) {
			this.repo = hgRepo;
		}

		public void next(int revisionNumber, Nodeid nodeid, Changeset cset) {
			final String s = print(revisionNumber, nodeid, cset);
			if (reverseOrder) {
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
		}

		private String print(int revNumber, Nodeid csetNodeid, Changeset cset) {
			StringBuilder sb = new StringBuilder();
			Formatter f = new Formatter(sb);
			f.format("changeset:   %d:%s\n", revNumber, complete ? csetNodeid : csetNodeid.shortNotation());
			if (complete) {
				f.format("parent:      %s\nparent:      %s\nmanifest:    %s\n", "-1", "-1", cset.manifest());
			}
			f.format("user:        %s\ndate:        %s\n", cset.user(), cset.dateString());
			if (complete) {
				final List<String> files = cset.files();
				sb.append("files:      ");
				for (String s : files) {
					sb.append(' ');
					sb.append(s);
				}
				f.format("\ndescription:\n%s\n\n", cset.comment());
			} else {
				f.format("summary:     %s\n\n", cset.comment());
			}
			if (cset.extras() != null) {
				f.format("extra:    " + cset.extras()); // TODO
			}
			return sb.toString();
		}
	}
}
