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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusInspector;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgStatusCollector.Record;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Status {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		//
//		bunchOfTests(hgRepo);
		//
//		new Internals(hgRepo).dumpDirstate();
		//
		//statusWorkingCopy(hgRepo);
		statusRevVsWorkingCopy(hgRepo);
	}

	private static void statusWorkingCopy(HgRepository hgRepo) {
		HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(hgRepo);
		HgStatusCollector.Record r = new HgStatusCollector.Record();
		wcc.walk(TIP, r);
		mardu(r);
	}

	private static void mardu(Record r) {
		sortAndPrint('M', r.getModified());
		sortAndPrint('A', r.getAdded(), r.getCopied());
		sortAndPrint('R', r.getRemoved());
		sortAndPrint('?', r.getUnknown());
//		sortAndPrint('I', r.getIgnored());
//		sortAndPrint('C', r.getClean());
		sortAndPrint('!', r.getMissing());
	}
	
	private static void statusRevVsWorkingCopy(HgRepository hgRepo) {
		HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(hgRepo);
		HgStatusCollector.Record r = new HgStatusCollector.Record();
		wcc.walk(3, r);
		mardu(r);
	}

	private static void bunchOfTests(HgRepository hgRepo) throws Exception {
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
		sortAndPrint('M', r.getModified());
		sortAndPrint('A', r.getAdded());
		sortAndPrint('R', r.getRemoved());
		//
		System.out.println("\n\nTry hg status --change <rev>:");
		sc.change(0, dump);
		System.out.println("\nStatus against working dir:");
		HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(hgRepo);
		wcc.walk(TIP, dump);
		System.out.println();
		System.out.printf("Manifest of the revision %d:\n", r2);
		hgRepo.getManifest().walk(r2, r2, new Manifest.Dump());
		System.out.println();
		System.out.printf("\nStatus of working dir against %d:\n", r2);
		r = wcc.status(r2);
		sortAndPrint('M', r.getModified());
		sortAndPrint('A', r.getAdded(), r.getCopied());
		sortAndPrint('R', r.getRemoved());
		sortAndPrint('?', r.getUnknown());
		sortAndPrint('I', r.getIgnored());
		sortAndPrint('C', r.getClean());
		sortAndPrint('!', r.getMissing());
	}
	
	private static void sortAndPrint(char prefix, List<Path> ul) {
		sortAndPrint(prefix, ul, null);
	}
	private static void sortAndPrint(char prefix, List<Path> ul, Map<Path, Path> copies) {
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
	
	protected static void testStatusInternals(HgRepository hgRepo) {
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
