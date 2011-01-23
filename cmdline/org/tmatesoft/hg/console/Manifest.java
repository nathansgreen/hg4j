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

import static com.tmate.hgkit.ll.HgRepository.TIP;

import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.core.RepositoryTreeWalker;
import org.tmatesoft.hg.core.LogCommand.FileRevision;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgManifest;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Manifest {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		hgRepo.getManifest().walk(0, TIP, new Dump());
		//
		new RepositoryTreeWalker(hgRepo).dirs(true).walk(new RepositoryTreeWalker.Handler() {
			
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
