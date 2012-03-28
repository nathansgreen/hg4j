/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgTags;
import org.tmatesoft.hg.repo.HgTags.TagInfo;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Tags {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, Collections.<String>emptySet());
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		HgTags tags = hgRepo.getTags();
		final HgChangelog clog = hgRepo.getChangelog();
		final Map<TagInfo, Integer> ti2index = new HashMap<TagInfo, Integer>();
		final TreeSet<TagInfo> sorted = new TreeSet<HgTags.TagInfo>(new Comparator<TagInfo>() {

			public int compare(TagInfo o1, TagInfo o2) {
				// reverse, from newer to older (bigger indexes first);
				// never ==, tags from same revision in any order, just next to each other
				int x1 = ti2index.get(o1);
				int x2 = ti2index.get(o2);
				return x1 < x2 ? 1 : -1;
			}
		});
		for (TagInfo ti : tags.getAllTags().values()) {
			int x = clog.getRevisionIndex(ti.revision()); // XXX in fact, performance hog. Need batch revisionIndex or another improvement
			ti2index.put(ti, x);
			sorted.add(ti);
		}
		for (TagInfo ti : sorted) {
			int x = ti2index.get(ti);
			System.out.printf("%-30s%8d:%s\n", ti.name(), x, ti.revision().shortNotation());
		}
	}
}
