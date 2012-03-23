/*
 * Copyright (c) 2010-2012 TMate Software Ltd
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
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgManifestCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Manifest {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, asSet("--debug", "-v", "--verbose"));
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		final boolean debug = cmdLineOpts.getBoolean("--debug");
		final boolean verbose = cmdLineOpts.getBoolean("-v", "--verbose");
		HgManifestCommand.Handler h = new HgManifestCommand.Handler() {
			
			public void begin(Nodeid manifestRevision) {
			}
			public void dir(Path p) {
			}
			public void file(HgFileRevision fileRevision) {
				try {
					if (debug) {
						System.out.print(fileRevision.getRevision());;
					}
					if (debug || verbose) {
						HgManifest.Flags flags = fileRevision.getFileFlags();
						Object s;
						if (flags == HgManifest.Flags.RegularFile) {
							s = Integer.toOctalString(0644);
						} else if (flags == HgManifest.Flags.Exec) {
							s = Integer.toOctalString(0755);
						} else if (flags == HgManifest.Flags.Link) {
							s = "lnk";
						} else {
							s = String.valueOf(flags);
						}
						System.out.printf(" %s   ", s);
					}
					System.out.println(fileRevision.getPath());
				} catch (HgInvalidControlFileException e) {
					e.printStackTrace();
				} catch (HgInvalidRevisionException e) {
					e.printStackTrace();
				}
			}
			
			public void end(Nodeid manifestRevision) {
			}
		};
		int rev = cmdLineOpts.getSingleInt(TIP, "-r", "--rev");
		new HgManifestCommand(hgRepo).dirs(false).revision(rev).execute(h); 
	}
}
