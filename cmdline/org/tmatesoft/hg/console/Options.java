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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgLookup;

/**
 * Parse command-line options
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class Options {

	public String repoLocation;
	public List<String> files;
	public int limit = -1;
	public Set<String> users;
	public Set<String> branches;
	
	public HgRepository findRepository() throws Exception {
		if (repoLocation != null) {
			return new HgLookup().detect(repoLocation);
		}
		return new HgLookup().detectFromWorkingDir();
	}


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