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

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.core.HgStatus;
import org.tmatesoft.hg.core.HgStatus.Kind;
import org.tmatesoft.hg.core.HgStatusCommand;
import org.tmatesoft.hg.util.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Status {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepoFacade hgRepo = new HgRepoFacade();
		if (!hgRepo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getRepository().getLocation());
			return;
		}
		//
		HgStatusCommand cmd = hgRepo.createStatusCommand();
		if (cmdLineOpts.getBoolean("-A", "--all")) {
			cmd.all();
		} else {
			// default: mardu
			cmd.modified(cmdLineOpts.getBoolean(true, "-m", "--modified"));
			cmd.added(cmdLineOpts.getBoolean(true, "-a", "--added"));
			cmd.removed(cmdLineOpts.getBoolean(true, "-r", "--removed"));
			cmd.deleted(cmdLineOpts.getBoolean(true, "-d", "--deleted"));
			cmd.unknown(cmdLineOpts.getBoolean(true, "-u", "--unknonwn"));
			cmd.clean(cmdLineOpts.getBoolean("-c", "--clean"));
			cmd.ignored(cmdLineOpts.getBoolean("-i", "--ignored"));
		}
//		cmd.subrepo(cmdLineOpts.getBoolean("-S", "--subrepos"))
		final boolean noStatusPrefix = cmdLineOpts.getBoolean("-n", "--no-status");
		final boolean showCopies = cmdLineOpts.getBoolean("-C", "--copies");
		class StatusHandler implements HgStatusCommand.Handler {
			
			final EnumMap<HgStatus.Kind, List<Path>> data = new EnumMap<HgStatus.Kind, List<Path>>(HgStatus.Kind.class);
			final Map<Path, Path> copies = showCopies ? new HashMap<Path,Path>() : null;
			
			public void handleStatus(HgStatus s) {
				List<Path> l = data.get(s.getKind());
				if (l == null) {
					l = new LinkedList<Path>();
					data.put(s.getKind(), l);
				}
				l.add(s.getPath());
				if (s.isCopy() && showCopies) {
					copies.put(s.getPath(), s.getOriginalPath());
				}
			}
			
			public void dump() {
				sortAndPrint('M', data.get(Kind.Modified), null);
				sortAndPrint('A', data.get(Kind.Added), copies);
				sortAndPrint('R', data.get(Kind.Removed), null);
				sortAndPrint('?', data.get(Kind.Unknown), null);
				sortAndPrint('I', data.get(Kind.Ignored), null);
				sortAndPrint('C', data.get(Kind.Clean), null);
				sortAndPrint('!', data.get(Kind.Missing), null);
			}

			private void sortAndPrint(char prefix, List<Path> ul, Map<Path, Path> copies) {
				if (ul == null) {
					return;
				}
				ArrayList<Path> sortList = new ArrayList<Path>(ul);
				Collections.sort(sortList);
				for (Path s : sortList)  {
					if (!noStatusPrefix) {
						System.out.print(prefix);
						System.out.print(' ');
					}
					System.out.println(s);
					if (copies != null && copies.containsKey(s)) {
						System.out.println("  " + copies.get(s));
					}
				}
			}
		};

		StatusHandler statusHandler = new StatusHandler(); 
		int changeRev = cmdLineOpts.getSingleInt(BAD_REVISION, "--change");
		if (changeRev != BAD_REVISION) {
			cmd.change(changeRev);
		} else {
			List<String> revisions = cmdLineOpts.getList("--rev");
			int size = revisions.size();
			if (size > 1) {
				cmd.base(Integer.parseInt(revisions.get(size - 2))).revision(Integer.parseInt(revisions.get(size - 1)));
			} else if (size > 0) {
				cmd.base(Integer.parseInt(revisions.get(0)));
			}
		}
		cmd.execute(statusHandler);
		statusHandler.dump();
	}
}
