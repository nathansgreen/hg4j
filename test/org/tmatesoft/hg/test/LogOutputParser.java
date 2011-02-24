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

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tmatesoft.hg.repo.HgRepository;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class LogOutputParser implements OutputParser {
	private final List<Record> result = new LinkedList<Record>();
	private Pattern pattern1;
	private Pattern pattern2;
	private Pattern pattern3;
	private Pattern pattern4;
	
	public LogOutputParser(boolean outputWithDebug) {
		if (outputWithDebug) {
			pattern1 = Pattern.compile("^changeset:\\s+(\\d+):([a-f0-9]{40})\n(^tag:(.+)$)?", Pattern.MULTILINE);
			pattern2 = Pattern.compile("^parent:\\s+(-?\\d+):([a-f0-9]{40})\n", Pattern.MULTILINE);
			pattern3 = Pattern.compile("^manifest:\\s+(\\d+):([a-f0-9]{40})\nuser:\\s+(\\S.+)\ndate:\\s+(\\S.+)\n", Pattern.MULTILINE);
			pattern4 = Pattern.compile("^description:\n^(.+)\n\n", Pattern.MULTILINE);
			//p = "^manifest:\\s+(\\d+):([a-f0-9]{40})\nuser:(.+)$";
		} else {
			throw HgRepository.notImplemented();
		}
	}
	
	public void reset() {
		result.clear();
	}
	
	public List<Record> getResult() {
		return result;
	}

	public void parse(CharSequence seq) {
		Matcher m = pattern1.matcher(seq);
		while (m.find()) {
			Record r = new Record();
			r.changesetIndex = Integer.parseInt(m.group(1));
			r.changesetNodeid = m.group(2);
			//tags = m.group(4);
			m.usePattern(pattern2);
			if (m.find()) {
				r.parent1Index = Integer.parseInt(m.group(1));
				r.parent1Nodeid = m.group(2);
			}
			if (m.find()) {
				r.parent2Index = Integer.parseInt(m.group(1));
				r.parent2Nodeid = m.group(2);
			}
			m.usePattern(pattern3);
			if (m.find()) {
				r.user = m.group(3);
				r.date = m.group(4);
			}
			m.usePattern(pattern4);
			if (m.find()) {
				r.description = m.group(1);
			}
			result.add(r);
			m.usePattern(pattern1);
		}
	}

	public static class Record {
		public int changesetIndex;
		public String changesetNodeid;
		public int parent1Index;
		public int parent2Index;
		public String parent1Nodeid;
		public String parent2Nodeid;
		public String user;
		public String date;
		public String description;
	}
}
