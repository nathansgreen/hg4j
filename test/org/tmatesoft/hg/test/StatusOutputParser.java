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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.test;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StatusOutputParser implements OutputParser {

	private final Pattern pattern;
	private List<String> modified, added, removed, clean, missing, unknown, ignored;
	private Map<String, String> copied;
	private final boolean winPathSeparator;

	public StatusOutputParser() {
//		pattern = Pattern.compile("^([MAR?IC! ]) ([\\w \\.-/\\\\]+)$", Pattern.MULTILINE);
		pattern = Pattern.compile("^([MAR?IC! ]) (.+)$", Pattern.MULTILINE);
		winPathSeparator = File.separatorChar == '\\';
	}

	public void reset() {
		modified = added = removed = clean = missing = unknown = ignored = null;
		copied = null;
	}

	public void parse(CharSequence seq) {
		Matcher m = pattern.matcher(seq);
		while (m.find()) {
			String fname = m.group(2);
			switch ((int) m.group(1).charAt(0)) {
			case (int) 'M' : {
				modified = doAdd(modified, fname);
				break;
			}
			case (int) 'A' : {
				added = doAdd(added, fname);
				break;
			}
			case (int) 'R' : {
				removed = doAdd(removed, fname);
				break;
			}
			case (int) '?' : {
				unknown = doAdd(unknown, fname);
				break;
			}
			case (int) 'I' : {
				ignored = doAdd(ignored, fname);
				break;
			}
			case (int) 'C' : {
				clean = doAdd(clean, fname);
				break;
			}
			case (int) '!' : {
				missing = doAdd(missing, fname);
				break;
			}
			case (int) ' ' : {
				if (copied == null) {
					copied = new TreeMap<String, String>();
				}
				// last added is copy destination
				// to get or to remove it - depends on what StatusCollector does in this case
				copied.put(fname, added.get(added.size() - 1));
				break;
			}
			}
		}
	}

	// 
	public List<String> getModified() {
		return proper(modified);
	}

	public List<String> getAdded() {
		return proper(added);
	}

	public List<String> getRemoved() {
		return proper(removed);
	}

	public Map<String,String> getCopied() {
		if (copied == null) {
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(copied);
	}

	public List<String> getClean() {
		return proper(clean);
	}

	public List<String> getMissing() {
		return proper(missing);
	}

	public List<String> getUnknown() {
		return proper(unknown);
	}

	public List<String> getIgnored() {
		return proper(ignored);
	}
	
	private List<String> proper(List<String> l) {
		if (l == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(l);
	}

	private List<String> doAdd(List<String> l, String s) {
		if (l == null) {
			l = new LinkedList<String>();
		}
		if (winPathSeparator) {
			// Java impl always give slashed path, while Hg uses local, os-specific convention
			s = s.replace('\\', '/'); 
		}
		l.add(s);
		return l;
	}
}
