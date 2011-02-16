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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.util.Path;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ManifestOutputParser implements OutputParser {

	private final Pattern pattern;
	private final LinkedHashMap<Path, Nodeid> result = new LinkedHashMap<Path, Nodeid>();

	public ManifestOutputParser() {
		pattern = Pattern.compile("^([a-f0-9]{40}) (\\d{3})   (.+)$", Pattern.MULTILINE);
	}
	
	public void reset() {
		result.clear();
	}
	
	public Map<Path, Nodeid> getResult() {
		return result;
	}
	
	public void parse(CharSequence seq) {
		Matcher m = pattern.matcher(seq);
		while (m.find()) {
			result.put(Path.create(m.group(3)), Nodeid.fromAscii(m.group(1).getBytes(), 0, 40));
		}
	}
}
