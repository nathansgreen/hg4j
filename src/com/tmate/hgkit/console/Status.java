/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.LocalHgRepo;

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
		//hgRepo.status(TIP, TIP, new StatusDump());
		final StatusDump dump = new StatusDump();
		dump.showIgnored = false;
		dump.showClean = false;
		((LocalHgRepo) hgRepo).statusLocal(TIP, dump);
	}

	private static class StatusDump implements HgRepository.StatusInspector {
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
