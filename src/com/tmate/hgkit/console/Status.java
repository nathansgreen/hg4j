/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgDataFile;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.LocalHgRepo;
import com.tmate.hgkit.ll.Nodeid;
import com.tmate.hgkit.ll.StatusCollector;

/**
 *
 * @author artem
 */
public class Status {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		((LocalHgRepo) hgRepo).loadDirstate().dump();
		final StatusDump dump = new StatusDump();
		dump.showIgnored = false;
		dump.showClean = false;
		StatusCollector sc = new StatusCollector(hgRepo);
		final int r1 = 0, r2 = 3;
		System.out.printf("Status for changes between revision %d and %d:\n", r1, r2);
		sc.walk(r1, r2, dump);
		// 
		System.out.println("\n\nSame, but sorted in the way hg status does:");
		StatusCollector.Record r = sc.status(r1, r2);
		sortAndPrint('M', r.getModified());
		sortAndPrint('A', r.getAdded());
		sortAndPrint('R', r.getRemoved());
		//
		System.out.println("\n\nTry hg status --change <rev>:");
		sc.change(0, dump);
//		System.out.println("\nStatus against working dir:");
//		((LocalHgRepo) hgRepo).statusLocal(TIP, dump);
//		System.out.println();
//		System.out.printf("Manifest of the revision %d:\n", r2);
//		hgRepo.getManifest().walk(r2, r2, new Manifest.Dump());
//		System.out.println();
//		System.out.printf("\nStatus of working dir against %d:\n", r2);
//		((LocalHgRepo) hgRepo).statusLocal(r2, dump);
	}
	
	private static void sortAndPrint(char prefix, List<String> ul) {
		ArrayList<String> sortList = new ArrayList<String>(ul);
		Collections.sort(sortList);
		for (String s : sortList)  {
			System.out.print(prefix);
			System.out.print(' ');
			System.out.println(s);
		}
	}
	
	protected static void testStatusInternals(HgRepository hgRepo) {
		HgDataFile n = hgRepo.getFileNode("design.txt");
		for (String s : new String[] {"011dfd44417c72bd9e54cf89b82828f661b700ed", "e5529faa06d53e06a816e56d218115b42782f1ba", "c18e7111f1fc89a80a00f6a39d51288289a382fc"}) {
			// expected: 359, 2123, 3079
			byte[] b = s.getBytes();
			final Nodeid nid = Nodeid.fromAscii(b, 0, b.length);
			System.out.println(s + " : " + n.length(nid));
		}
	}

	private static class StatusDump implements StatusCollector.Inspector {
		public boolean hideStatusPrefix = false; // hg status -n option
		public boolean showCopied = true; // -C
		public boolean showIgnored = true; // -i
		public boolean showClean = true; // -c

		public void modified(String fname) {
			print('M', fname);
		}

		public void added(String fname) {
			print('A', fname);
		}

		public void copied(String fnameOrigin, String fnameAdded) {
			added(fnameAdded);
			if (showCopied) {
				print(' ', fnameOrigin);
			}
		}

		public void removed(String fname) {
			print('R', fname);
		}

		public void clean(String fname) {
			if (showClean) {
				print('C', fname);
			}
		}

		public void missing(String fname) {
			print('!', fname);
		}

		public void unknown(String fname) {
			print('?', fname);
		}

		public void ignored(String fname) {
			if (showIgnored) {
				print('I', fname);
			}
		}
		
		private void print(char status, String fname) {
			if (!hideStatusPrefix) {
				System.out.print(status);
				System.out.print(' ');
			}
			System.out.println(fname);
		}
	}
}
