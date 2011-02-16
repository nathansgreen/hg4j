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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.core.HgLogCommand.FileRevision;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.core.HgManifestCommand;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Manifest {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		hgRepo.getManifest().walk(0, TIP, new Dump());
		//
		new HgManifestCommand(hgRepo).dirs(true).walk(new HgManifestCommand.Handler() {
			
			public void begin(Nodeid manifestRevision) {
				System.out.println(">> " + manifestRevision);
			}
			public void dir(Path p) {
				System.out.println(p);
			}
			public void file(FileRevision fileRevision) {
				System.out.print(fileRevision.getRevision());;
				System.out.print("   ");
				System.out.println(fileRevision.getPath());
			}
			
			public void end(Nodeid manifestRevision) {
				System.out.println();
			}
		}); 
	}

	public static final class Dump implements HgManifest.Inspector {
		public boolean begin(int revision, Nodeid nid) {
			System.out.printf("%d : %s\n", revision, nid);
			return true;
		}

		public boolean next(Nodeid nid, String fname, String flags) {
			System.out.println(nid + "\t" + fname + "\t\t" + flags);
			return true;
		}

		public boolean end(int revision) {
			System.out.println();
			return true;
		}
	}
}
