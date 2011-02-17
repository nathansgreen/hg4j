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

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * Parse command-line options. Primitive implementation that recognizes options with 0 or 1 argument.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class Options {

	public final Map<String,List<String>> opt2values = new HashMap<String, List<String>>();

	public boolean getBoolean(String... aliases) {
		return getBoolean(false, aliases);
	}

	public boolean getBoolean(boolean def, String... aliases) {
		for (String s : aliases) {
			if (opt2values.containsKey(s)) {
				return true;
			}
		}
		return def;
	}

	public String getSingle(String... aliases) {
		String rv = null;
		for (String s : aliases) {
			List<String> values = opt2values.get(s);
			if (values != null && values.size() > 0) {
				rv = values.get(values.size() - 1); // take last one, most recent
			}
		}
		return rv;
	}
	
	public int getSingleInt(int def, String... aliases) {
		String r = getSingle(aliases);
		if (r == null) {
			return def;
		}
		return Integer.parseInt(r);
	}

	public List<String> getList(String... aliases) {
		LinkedList<String> rv = new LinkedList<String>();
		for (String s : aliases) {
			List<String> values = opt2values.get(s);
			if (values != null) {
				rv.addAll(values);
			}
		}
		return rv;
	}
	
	public HgRepository findRepository() throws Exception {
		String repoLocation = getSingle("-R", "--repository");
		if (repoLocation != null) {
			return new HgLookup().detect(repoLocation);
		}
		return new HgLookup().detectFromWorkingDir();
	}


	public static Options parse(String[] commandLineArgs) {
		Options rv = new Options();
		List<String> values = new LinkedList<String>();
		rv.opt2values.put("", values); // values with no options
		for (String arg : commandLineArgs) {
			if (arg.charAt(0) == '-') {
				// option
				if (arg.length() == 1) {
					throw new IllegalArgumentException("Bad option: -");
				}
				values = rv.opt2values.get(arg);
				if (values == null) {
					rv.opt2values.put(arg, values = new LinkedList<String>());
				}
				// next value, if any, gets into the values list for arg option.
			} else {
				values.add(arg);
				values = rv.opt2values.get("");
			}
		}
		return rv;
	}
}