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
package org.tmatesoft.hg.test;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tmatesoft.hg.internal.PathPool;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StatusOutputParser implements OutputParser {

	private final Pattern pattern;
	// although using StatusCollector.Record is not really quite honest for testing,
	// it's deemed acceptable as long as that class is primitive 'collect all results'
	private HgStatusCollector.Record result = new HgStatusCollector.Record();
	private final PathPool pathHelper;

	public StatusOutputParser() {
//		pattern = Pattern.compile("^([MAR?IC! ]) ([\\w \\.-/\\\\]+)$", Pattern.MULTILINE);
		pattern = Pattern.compile("^([MAR?IC! ]) (.+)$", Pattern.MULTILINE);
		pathHelper = new PathPool(new PathRewrite() {
			
			private final boolean winPathSeparator = File.separatorChar == '\\';

			public CharSequence rewrite(CharSequence s) {
				if (winPathSeparator) {
					// Java impl always give slashed path, while Hg uses local, os-specific convention
					s = s.toString().replace('\\', '/'); 
				}
				return s;
			}
		});
	}

	public void reset() {
		result = new HgStatusCollector.Record();
	}

	public void parse(CharSequence seq) {
		Matcher m = pattern.matcher(seq);
		Path lastEntry = null;
		while (m.find()) {
			Path fname = pathHelper.path(m.group(2));
			switch ((int) m.group(1).charAt(0)) {
			case (int) 'M' : {
				result.modified(fname);
				lastEntry = fname; // for files modified through merge there's also 'copy' source 
				break;
			}
			case (int) 'A' : {
				result.added(fname);
				lastEntry = fname;
				break;
			}
			case (int) 'R' : {
				result.removed(fname);
				break;
			}
			case (int) '?' : {
				result.unknown(fname);
				break;
			}
			case (int) 'I' : {
				result.ignored(fname);
				break;
			}
			case (int) 'C' : {
				result.clean(fname);
				break;
			}
			case (int) '!' : {
				result.missing(fname);
				break;
			}
			case (int) ' ' : {
				// last added is copy destination
				// to get or to remove it - depends on what StatusCollector does in this case
				result.copied(fname, lastEntry);
				lastEntry = null;
				break;
			}
			}
		}
	}

	// 
	public List<Path> getModified() {
		return result.getModified();
	}

	public List<Path> getAdded() {
		List<Path> rv = new LinkedList<Path>(result.getAdded());
		for (Path p : result.getCopied().keySet()) {
			rv.remove(p); // remove only one duplicate
		}
		return rv;
	}

	public List<Path> getRemoved() {
		return result.getRemoved();
	}

	public Map<Path,Path> getCopied() {
		return result.getCopied();
	}

	public List<Path> getClean() {
		return result.getClean();
	}

	public List<Path> getMissing() {
		return result.getMissing();
	}

	public List<Path> getUnknown() {
		return result.getUnknown();
	}

	public List<Path> getIgnored() {
		return result.getIgnored();
	}
}
