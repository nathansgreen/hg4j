/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.LocalHgRepo;

/**
 * @author artem
 */
public class RepositoryLookup {

	public HgRepository detect(Options opts) throws Exception {
		if (opts.repoLocation != null) {
			return detect(opts.repoLocation);
		}
		return detectFromWorkingDir();
	}

	public HgRepository detect(String[] commandLineArgs) throws Exception {
		return detect(Options.parse(commandLineArgs));
	}

	public HgRepository detectFromWorkingDir() throws Exception {
		return detect(System.getProperty("user.dir"));
	}

	public HgRepository detect(String location) throws Exception /*FIXME Exception type, RepoInitException? */ {
		File dir = new File(location);
		File repository;
		do {
			repository = new File(dir, ".hg");
			if (repository.exists() && repository.isDirectory()) {
				break;
			}
			repository = null;
			dir = dir.getParentFile();
			
		} while(dir != null);
		if (repository == null) {
			return new LocalHgRepo(location);
		}
		return new LocalHgRepo(repository);
	}

	public static class Options {
	
		public String repoLocation;
		public List<String> files;
		public int limit = -1;
		public Set<String> users;
		public Set<String> branches;

		public static Options parse(String[] commandLineArgs) {
			Options rv = new Options();
			List<String> args = Arrays.asList(commandLineArgs);
			LinkedList<String> files = new LinkedList<String>();
			for (Iterator<String> it = args.iterator(); it.hasNext(); ) {
				String arg = it.next();
				if (arg.charAt(0) == '-') {
					// option
					if (arg.length() == 1) {
						throw new IllegalArgumentException("Bad option: -");
					}
					switch ((int) arg.charAt(1)) {
						case (int) 'R' : {
							if (! it.hasNext()) {
								throw new IllegalArgumentException("Need repo location");
							}
							rv.repoLocation = it.next();
							break;
						}
						case (int) 'l' : {
							if (!it.hasNext()) {
								throw new IllegalArgumentException();
							}
							rv.limit = Integer.parseInt(it.next());
							break;
						}
						case (int) 'u' : {
							if (rv.users == null) {
								rv.users = new LinkedHashSet<String>();
							}
							rv.users.add(it.next());
							break;
						}
						case (int) 'b' : {
							if (rv.branches == null) {
								rv.branches = new LinkedHashSet<String>();
							}
							rv.branches.add(it.next());
							break;
						}
					}
				} else {
					// filename
					files.add(arg);
				}
			}
			if (!files.isEmpty()) {
				rv.files = new ArrayList<String>(files);
			} else {
				rv.files = Collections.emptyList();
			}
			return rv;
		}
	}
}
