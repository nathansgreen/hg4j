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
package org.tmatesoft.hg.repo;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.RevlogStream;


/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgBundle {

	private final File bundleFile;
	private final DataAccessProvider accessProvider;

	public HgBundle(DataAccessProvider dap, File bundle) {
		accessProvider = dap;
		bundleFile = bundle;
	}

	public void changes(HgRepository hgRepo) throws IOException {
		DataAccess da = accessProvider.create(bundleFile);
		DigestHelper dh = new DigestHelper();
		try {
			List<GroupElement> changelogGroup = readGroup(da);
			if (changelogGroup.isEmpty()) {
				throw new IllegalStateException("No changelog group in the bundle"); // XXX perhaps, just be silent and/or log?
			}
			// XXX in fact, bundle not necessarily starts with the first revision missing in hgRepo
			// need to 'scroll' till the last one common.
			final Nodeid base = changelogGroup.get(0).firstParent();
			if (!hgRepo.getChangelog().isKnown(base)) {
				throw new IllegalArgumentException("unknown parent");
			}
			// BundleFormat wiki says:
			// Each Changelog entry patches the result of all previous patches 
			// (the previous, or parent patch of a given patch p is the patch that has a node equal to p's p1 field)
			byte[] baseRevContent = hgRepo.getChangelog().content(base);
			for (GroupElement ge : changelogGroup) {
				byte[] csetContent = RevlogStream.apply(baseRevContent, -1, ge.patches);
				dh = dh.sha1(ge.firstParent(), ge.secondParent(), csetContent); // XXX ge may give me access to byte[] content of nodeid directly, perhaps, I don't need DH to be friend of Nodeid?
				if (!ge.node().equalsTo(dh.asBinary())) {
					throw new IllegalStateException("Integrity check failed on " + bundleFile + ", node:" + ge.node());
				}
				Changeset cs = Changeset.parse(csetContent, 0, csetContent.length);
				System.out.println(cs.toString());
				baseRevContent = csetContent;
			}
		} finally {
			da.done();
		}
	}

	public void dump() throws IOException {
		DataAccess da = accessProvider.create(bundleFile);
		try {
			LinkedList<String> names = new LinkedList<String>();
			if (!da.isEmpty()) {
				System.out.println("Changelog group");
				List<GroupElement> changelogGroup = readGroup(da);
				for (GroupElement ge : changelogGroup) {
					System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cset(), ge.patches.size());
				}
				System.out.println("Manifest group");
				List<GroupElement> manifestGroup = readGroup(da);
				for (GroupElement ge : manifestGroup) {
					System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cset(), ge.patches.size());
				}
				while (!da.isEmpty()) {
					int fnameLen = da.readInt();
					if (fnameLen <= 4) {
						break; // null chunk, the last one.
					}
					byte[] fname = new byte[fnameLen - 4];
					da.readBytes(fname, 0, fname.length);
					names.add(new String(fname));
					List<GroupElement> fileGroup = readGroup(da);
					System.out.println(names.getLast());
					for (GroupElement ge : fileGroup) {
						System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cset(), ge.patches.size());
					}
				}
			}
			System.out.println(names.size());
			for (String s : names) {
				System.out.println(s);
			}
		} finally {
			da.done();
		}
	}

	private static List<GroupElement> readGroup(DataAccess da) throws IOException {
		int len = da.readInt();
		LinkedList<GroupElement> rv = new LinkedList<HgBundle.GroupElement>();
		while (len > 4 && !da.isEmpty()) {
			byte[] nb = new byte[80];
			da.readBytes(nb, 0, 80);
			int dataLength = len-84;
			LinkedList<RevlogStream.PatchRecord> patches = new LinkedList<RevlogStream.PatchRecord>();
			while (dataLength > 0) {
				RevlogStream.PatchRecord pr = RevlogStream.PatchRecord.read(da);
				patches.add(pr);
				dataLength -= pr.len + 12;
			}
			rv.add(new GroupElement(nb, patches));
			len = da.isEmpty() ? 0 : da.readInt();
		}
		return rv;
	}

	static class GroupElement {
		private byte[] header; // byte[80] takes 120 bytes, 4 Nodeids - 192
		private List<RevlogStream.PatchRecord> patches;
		
		GroupElement(byte[] fourNodeids, List<RevlogStream.PatchRecord> patchList) {
			assert fourNodeids != null && fourNodeids.length == 80;
			// patchList.size() > 0
			header = fourNodeids;
			patches = patchList;
		}
		public Nodeid node() {
			return Nodeid.fromBinary(header, 0);
		}
		public Nodeid firstParent() {
			return Nodeid.fromBinary(header, 20);
		}
		public Nodeid secondParent() {
			return Nodeid.fromBinary(header, 40);
		}
		public Nodeid cset() { // cs seems to be changeset
			return Nodeid.fromBinary(header, 60);
		}
	}
}
