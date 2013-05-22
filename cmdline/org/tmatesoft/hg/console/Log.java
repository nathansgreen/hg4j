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
package org.tmatesoft.hg.console;

import static org.tmatesoft.hg.console.Options.asSet;

import java.util.List;

import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgFileRenameHandlerMixin;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.ProgressSupport;


/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Log {

	// -agentlib:hprof=heap=sites,depth=10,etc might be handy to debug speed/memory issues
	
	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, asSet("--debug", "-v", "--verbose", "--hg4j-order-direct"));
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		//
		// in fact, neither cancel nor progress of any use, need them just to check comamnd API
		final CancelSupport noCancel = CancelSupport.Factory.get(null);
		final ProgressSupport noProgress = ProgressSupport.Factory.get(null);
		//
		final Dump dump = new Dump(hgRepo);
		dump.complete(cmdLineOpts.getBoolean("--debug"));
		dump.verbose(cmdLineOpts.getBoolean("-v", "--verbose"));
		final boolean reverseOrder = !cmdLineOpts.getBoolean("--hg4j-order-direct");
		dump.reversed(reverseOrder);
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
		cmd.set(noCancel).set(noProgress);
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
				if (fixRange(r, reverseOrder, limit) == 0) {
					System.out.println("No changes");
					return;
				}
				cmd.range(r[0], r[1]).execute(dump);
			}
			dump.done();
		} else {
			for (String fname : files) {
				HgDataFile f1 = hgRepo.getFileNode(fname);
				System.out.println("History of the file: " + f1.getPath());
				if (limit == -1) {
					cmd.file(f1.getPath(), true).execute(dump);
				} else {
					int[] r = new int[] { 0, f1.getRevisionCount() };
					if (fixRange(r, reverseOrder, limit) == 0) {
						System.out.println("No changes");
						continue;
					}
					cmd.range(r[0], r[1]).file(f1.getPath(), true).execute(dump);
				}
				dump.done();
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

	private static final class Dump extends ChangesetDumpHandler implements HgChangesetHandler.WithCopyHistory {
		private final RenameDumpHandler renameHandlerDelegate;

		public Dump(HgRepository hgRepo) throws HgRuntimeException {
			super(hgRepo);
			renameHandlerDelegate = new RenameDumpHandler();
		}
		
		public void copy(HgFileRevision from, HgFileRevision to) {
			renameHandlerDelegate.copy(from, to);
		}
	}
	
	static class RenameDumpHandler implements HgFileRenameHandlerMixin {
		public void copy(HgFileRevision from, HgFileRevision to) {
			System.out.printf("Got notified that %s(%s) was originally known as %s(%s)\n", to.getPath(), to.getRevision(), from.getPath(), from.getRevision());
		}
	}
}
