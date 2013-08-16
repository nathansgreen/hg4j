/*
 * Copyright (c) 2013 TMate Software Ltd
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

import java.util.Arrays;
import java.util.HashSet;

import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgMergeCommand;
import org.tmatesoft.hg.core.HgMergeCommand.Resolver;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.core.Nodeid;

/**
 * Command-line frontend for merge command, 'hg merge' counterpart.
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Merge {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, new HashSet<String>(Arrays.asList("--dry-run")));
		HgRepoFacade hgRepo = new HgRepoFacade();
		if (!hgRepo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getRepository().getLocation());
			return;
		}
		HgMergeCommand.Mediator m = null;
		if (cmdLineOpts.getBoolean("--dry-run") || Boolean.TRUE.booleanValue()) {
			m = new Dump();
		}
		final String revParam = cmdLineOpts.getSingle("-r", "--rev");
		final HgMergeCommand cmd = hgRepo.createMergeCommand();
		if (revParam.trim().length() == Nodeid.SIZE_ASCII) {
			cmd.changeset(Nodeid.fromAscii(revParam.trim()));
		} else {
			cmd.changeset(Integer.parseInt(revParam));
		}
		cmd.execute(m);
	}

	static class Dump implements HgMergeCommand.Mediator {

		public void same(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Unchanged %s:%s\n", rev.getPath(), rev.getRevision().shortNotation());
		}

		public void onlyA(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Left in first trunk only %s:%s\n", rev.getPath(), rev.getRevision().shortNotation());
		}

		public void onlyB(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Left in second trunk only %s:%s\n", rev.getPath(), rev.getRevision().shortNotation());
		}

		public void newInA(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Introduced in first trunk %s:%s\n", rev.getPath(), rev.getRevision().shortNotation());
		}

		public void newInB(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Introduced in second trunk %s:%s\n", rev.getPath(), rev.getRevision().shortNotation());
		}

		public void fastForwardA(HgFileRevision base, HgFileRevision first, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Changed in first trunk only %s: %s..%s\n", first.getPath(), base.getRevision().shortNotation(), first.getRevision().shortNotation());
		}

		public void fastForwardB(HgFileRevision base, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Changed in second trunk only %s: %s..%s\n", second.getPath(), base.getRevision().shortNotation(), second.getRevision().shortNotation());
		}

		public void resolve(HgFileRevision base, HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
			System.out.printf("Changed in boths trunks %s: %s and %s from %s\n", first.getPath(), first.getRevision().shortNotation(), second.getRevision().shortNotation(), base.getRevision().shortNotation());
		}
	}
}
