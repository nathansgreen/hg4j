/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.util.Formatter;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.Changeset;
import com.tmate.hgkit.ll.HgDataFile;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;
import com.tmate.hgkit.ll.Revlog;

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
		dump.complete = true; //cmdLineOpts;
		dump.reverseOrder = true;
		dump.branches = cmdLineOpts.branches;
		if (cmdLineOpts.users != null) {
			dump.users = new LinkedHashSet<String>();
			for (String u : cmdLineOpts.users) {
				dump.users.add(u.toLowerCase());
			}
		}
		if (cmdLineOpts.files.isEmpty()) {
			if (cmdLineOpts.limit == -1) {
				// no revisions and no limit
				hgRepo.getChangelog().all(dump);
			} else {
				// in fact, external (to dump inspector) --limit processing yelds incorrect results when other args
				// e.g. -u or -b are used (i.e. with -u shall give <limit> csets with user, not check last <limit> csets for user 
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

	// Differences with standard hg log output
	//   - complete == true (--debug) files are not broke down to modified,+ and -
	private static final class Dump implements Changeset.Inspector {
		// params
		boolean complete = false;
		boolean reverseOrder = false;
		Set<String> branches;
		Set<String> users; // shall be lowercased
		// own
		private LinkedList<String> l = new LinkedList<String>();
		private final HgRepository repo;
		private Revlog.ParentWalker changelogWalker;
		private final int tip ; 

		public Dump(HgRepository hgRepo) {
			repo = hgRepo;
			tip = hgRepo.getChangelog().getRevisionCount() - 1;
		}

		public void next(int revisionNumber, Nodeid nodeid, Changeset cset) {
			if (branches != null && !branches.contains(cset.branch())) {
				return;
			}
			if (users != null) {
				String csetUser = cset.user().toLowerCase();
				boolean found = false;
				for (String u : users) {
					if (csetUser.indexOf(u) != -1) {
						found = true;
						break;
					}
				}
				if (!found) {
					return;
				}
			}
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
			changelogWalker = null;
		}

		private String print(int revNumber, Nodeid csetNodeid, Changeset cset) {
			StringBuilder sb = new StringBuilder();
			Formatter f = new Formatter(sb);
			f.format("changeset:   %d:%s\n", revNumber, complete ? csetNodeid : csetNodeid.shortNotation());
			if (revNumber == tip || repo.getTags().isTagged(csetNodeid)) {
				
				sb.append("tag:         ");
				for (String t : repo.getTags().tags(csetNodeid)) {
					sb.append(t);
					sb.append(' ');
				}
				if (revNumber == tip) {
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
				int p1x = p1 == Nodeid.NULL ? -1 : repo.getChangelog().getLocalRevisionNumber(p1);
				int p2x = p2 == Nodeid.NULL ? -1 : repo.getChangelog().getLocalRevisionNumber(p2);
				int mx = repo.getManifest().getLocalRevisionNumber(cset.manifest());
				f.format("parent:      %d:%s\nparent:      %d:%s\nmanifest:    %d:%s\n", p1x, p1, p2x, p2, mx, cset.manifest());
			}
			f.format("user:        %s\ndate:        %s\n", cset.user(), cset.dateString());
			if (complete) {
				final List<String> files = cset.files();
				sb.append("files:      ");
				for (String s : files) {
					sb.append(' ');
					sb.append(s);
				}
				if (cset.extras() != null) {
					sb.append("\nextra:      ");
					for (Map.Entry<String, String> e : cset.extras().entrySet()) {
						sb.append(' ');
						sb.append(e.getKey());
						sb.append('=');
						sb.append(e.getValue());
					}
				}
				f.format("\ndescription:\n%s\n\n", cset.comment());
			} else {
				f.format("summary:     %s\n\n", cset.comment());
			}
			return sb.toString();
		}
	}
}
