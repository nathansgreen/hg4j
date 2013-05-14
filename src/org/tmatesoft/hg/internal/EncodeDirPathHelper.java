/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tmatesoft.hg.util.PathRewrite;

/**
 * <blockquote cite="http://mercurial.selenic.com/wiki/FileFormats#data.2F">Directory names ending in .i or .d have .hg appended</blockquote>
 *  
 * @see http://mercurial.selenic.com/wiki/FileFormats#data.2F
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
final class EncodeDirPathHelper implements PathRewrite {
	private final Pattern suffix2replace;
	
	public EncodeDirPathHelper() {
		suffix2replace = Pattern.compile("\\.([id]|hg)/");
	}

	public CharSequence rewrite(CharSequence p) {
		Matcher suffixMatcher = suffix2replace.matcher(p);
		CharSequence path;
		// Matcher.replaceAll, but without extra toString
		boolean found = suffixMatcher.find();
		if (found) {
			StringBuffer sb = new StringBuffer(p.length()  + 20);
			do {
				suffixMatcher.appendReplacement(sb, ".$1.hg/");
			} while (found = suffixMatcher.find());
			suffixMatcher.appendTail(sb);
			path = sb;
		} else {
			path = p;
		}
		return path;
	}

}
