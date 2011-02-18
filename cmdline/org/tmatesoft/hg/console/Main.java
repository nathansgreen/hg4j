/*
 * Copyright (c) 2011 TMate Software Ltd
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgLogCommand.FileRevision;
import org.tmatesoft.hg.core.HgManifestCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgStatusInspector;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;
import org.tmatesoft.hg.util.Path;

/**
 * Various debug dumps. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Main {
	
	private Options cmdLineOpts;
	private HgRepository hgRepo;

	public Main(String[] args) throws Exception {
		cmdLineOpts = Options.parse(args);
		hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println("REPO:" + hgRepo.getLocation());
	}

	public static void main(String[] args) throws Exception {
		Main m = new Main(args);
		m.dumpIgnored();
		m.dumpDirstate();
		m.testStatusInternals();
		m.catCompleteHistory();
		m.dumpCompleteManifestLow();
		m.dumpCompleteManifestHigh();
		m.bunchOfTests();
	}
	
	private void dumpIgnored() {
		HgInternals debug = new HgInternals(hgRepo);
		String[] toCheck = new String[] {"design.txt", "src/com/tmate/hgkit/ll/Changelog.java", "src/Extras.java", "bin/com/tmate/hgkit/ll/Changelog.class"};
		boolean[] checkResult = debug.checkIgnored(toCheck);
		for (int i = 0; i < toCheck.length; i++) {
			System.out.println("Ignored " + toCheck[i] + ": " + checkResult[i]);
		}
	}
	
	private void dumpDirstate() {
		new HgInternals(hgRepo).dumpDirstate();
	}

	
	private void catCompleteHistory() {
		DigestHelper dh = new DigestHelper();
		for (String fname : cmdLineOpts.getList("")) {
			System.out.println(fname);
			HgDataFile fn = hgRepo.getFileNode(fname);
			if (fn.exists()) {
				int total = fn.getRevisionCount();
				System.out.printf("Total revisions: %d\n", total);
				for (int i = 0; i < total; i++) {
					byte[] content = fn.content(i);
					System.out.println("==========>");
					System.out.println(new String(content));
					int[] parentRevisions = new int[2];
					byte[] parent1 = new byte[20];
					byte[] parent2 = new byte[20];
					fn.parents(i, parentRevisions, parent1, parent2);
					System.out.println(dh.sha1(parent1, parent2, content).asHexString());
				}
			} else {
				System.out.println(">>>Not found!");
			}
		}
	}

	private void dumpCompleteManifestLow() {
		hgRepo.getManifest().walk(0, TIP, new ManifestDump());
	}

	public static final class ManifestDump implements HgManifest.Inspector {
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

	private void dumpCompleteManifestHigh() {
		new HgManifestCommand(hgRepo).dirs(true).execute(new HgManifestCommand.Handler() {
			
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

	private void bunchOfTests() throws Exception {
		HgInternals debug = new HgInternals(hgRepo);
		debug.dumpDirstate();
		final StatusDump dump = new StatusDump();
		dump.showIgnored = false;
		dump.showClean = false;
		HgStatusCollector sc = new HgStatusCollector(hgRepo);
		final int r1 = 0, r2 = 3;
		System.out.printf("Status for changes between revision %d and %d:\n", r1, r2);
		sc.walk(r1, r2, dump);
		// 
		System.out.println("\n\nSame, but sorted in the way hg status does:");
		HgStatusCollector.Record r = sc.status(r1, r2);
		sortAndPrint('M', r.getModified(), null);
		sortAndPrint('A', r.getAdded(), null);
		sortAndPrint('R', r.getRemoved(), null);
		//
		System.out.println("\n\nTry hg status --change <rev>:");
		sc.change(0, dump);
		System.out.println("\nStatus against working dir:");
		HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(hgRepo);
		wcc.walk(TIP, dump);
		System.out.println();
		System.out.printf("Manifest of the revision %d:\n", r2);
		hgRepo.getManifest().walk(r2, r2, new ManifestDump());
		System.out.println();
		System.out.printf("\nStatus of working dir against %d:\n", r2);
		r = wcc.status(r2);
		sortAndPrint('M', r.getModified(), null);
		sortAndPrint('A', r.getAdded(), r.getCopied());
		sortAndPrint('R', r.getRemoved(), null);
		sortAndPrint('?', r.getUnknown(), null);
		sortAndPrint('I', r.getIgnored(), null);
		sortAndPrint('C', r.getClean(), null);
		sortAndPrint('!', r.getMissing(), null);
	}
	
	private void sortAndPrint(char prefix, List<Path> ul, Map<Path, Path> copies) {
		ArrayList<Path> sortList = new ArrayList<Path>(ul);
		Collections.sort(sortList);
		for (Path s : sortList)  {
			System.out.print(prefix);
			System.out.print(' ');
			System.out.println(s);
			if (copies != null && copies.containsKey(s)) {
				System.out.println("  " + copies.get(s));
			}
		}
	}


	private void testStatusInternals() {
		HgDataFile n = hgRepo.getFileNode(Path.create("design.txt"));
		for (String s : new String[] {"011dfd44417c72bd9e54cf89b82828f661b700ed", "e5529faa06d53e06a816e56d218115b42782f1ba", "c18e7111f1fc89a80a00f6a39d51288289a382fc"}) {
			// expected: 359, 2123, 3079
			byte[] b = s.getBytes();
			final Nodeid nid = Nodeid.fromAscii(b, 0, b.length);
			System.out.println(s + " : " + n.length(nid));
		}
	}

	private static class StatusDump implements HgStatusInspector {
		public boolean hideStatusPrefix = false; // hg status -n option
		public boolean showCopied = true; // -C
		public boolean showIgnored = true; // -i
		public boolean showClean = true; // -c

		public void modified(Path fname) {
			print('M', fname);
		}

		public void added(Path fname) {
			print('A', fname);
		}

		public void copied(Path fnameOrigin, Path fnameAdded) {
			added(fnameAdded);
			if (showCopied) {
				print(' ', fnameOrigin);
			}
		}

		public void removed(Path fname) {
			print('R', fname);
		}

		public void clean(Path fname) {
			if (showClean) {
				print('C', fname);
			}
		}

		public void missing(Path fname) {
			print('!', fname);
		}

		public void unknown(Path fname) {
			print('?', fname);
		}

		public void ignored(Path fname) {
			if (showIgnored) {
				print('I', fname);
			}
		}
		
		private void print(char status, Path fname) {
			if (!hideStatusPrefix) {
				System.out.print(status);
				System.out.print(' ');
			}
			System.out.println(fname);
		}
	}
}