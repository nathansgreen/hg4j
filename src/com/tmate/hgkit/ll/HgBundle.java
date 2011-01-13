/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import com.tmate.hgkit.fs.DataAccess;
import com.tmate.hgkit.fs.DataAccessProvider;

/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat
 *
 * @author artem
 */
public class HgBundle {

	private final File bundleFile;
	private final DataAccessProvider accessProvider;

	public HgBundle(DataAccessProvider dap, File bundle) {
		accessProvider = dap;
		bundleFile = bundle;
	}

	public void read() throws IOException {
		DataAccess da = accessProvider.create(bundleFile);
		try {
			LinkedList<String> names = new LinkedList<String>();
			if (!da.isEmpty()) {
				System.out.println("Changelog group");
				List<GroupElement> changelogGroup = readGroup(da);
				for (GroupElement ge : changelogGroup) {
					System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cs(), ge.patches.size());
				}
				System.out.println("Manifest group");
				List<GroupElement> manifestGroup = readGroup(da);
				for (GroupElement ge : manifestGroup) {
					System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cs(), ge.patches.size());
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
						System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cs(), ge.patches.size());
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
		public Nodeid cs() {
			return Nodeid.fromBinary(header, 60);
		}

	}
}
