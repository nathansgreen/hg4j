/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 *
 * @author artem
 */
public class HgIgnore {

	private final LocalHgRepo repo;
	private Set<String> entries;

	public HgIgnore(LocalHgRepo localRepo) {
		this.repo = localRepo;
	}

	private void read() {
		entries = Collections.emptySet();
		File hgignoreFile = new File(repo.getRepositoryRoot().getParentFile(), ".hgignore");
		if (!hgignoreFile.exists()) {
			return;
		}
		entries = new TreeSet<String>();
		try {
			BufferedReader fr = new BufferedReader(new FileReader(hgignoreFile));
			String line;
			while ((line = fr.readLine()) != null) {
				// FIXME need to detect syntax:glob and other parameters
				entries.add(line.trim()); // shall I account for local paths in the file (i.e. back-slashed on windows)?
			}
		} catch (IOException ex) {
			ex.printStackTrace(); // log warn
		}
	}

	public void reset() {
		// FIXME does anyone really need to clear HgIgnore? Perhaps, repo may return new instance each time,
		// which is used throughout invocation and then discarded?
		entries = null;
	}

	public boolean isIgnored(String path) {
		if (entries == null) {
			read();
		}
		if (entries.contains(path)) {
			// easy part
			return true;
		}
		// substrings are memory-friendly 
		int x = 0, i = path.indexOf('/', 0);
		while (i != -1) {
			if (entries.contains(path.substring(x, i))) {
				return true;
			}
			// try one with ending slash
			if (entries.contains(path.substring(x, i+1))) { // even if i is last index, i+1 is safe here
				return true;
			}
			x = i+1;
			i = path.indexOf('/', x);
		}
		return false;
	}
}
