/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.io.File;
import java.util.LinkedList;

import com.tmate.hgkit.fs.DataAccess;
import com.tmate.hgkit.fs.DataAccessProvider;
import com.tmate.hgkit.ll.Nodeid;

/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat
 *
 * @author artem
 */
public class Bundle {

	public static void main(String[] args) throws Exception {
		File bundleFile = new File("/temp/hg/hg-bundle-a78c980749e3.tmp");
		DataAccessProvider dap = new DataAccessProvider();
		DataAccess da = dap.create(bundleFile);
		try {
			LinkedList<String> names = new LinkedList<String>();
			if (!da.isEmpty()) {
				System.out.println("Changelog group");
				readGroup(da);
				System.out.println("Manifest group");
				readGroup(da);
				while (!da.isEmpty()) {
					int fnameLen = da.readInt();
					if (fnameLen <= 4) {
						break; // null chunk, the last one.
					}
					byte[] fname = new byte[fnameLen - 4];
					da.readBytes(fname, 0, fname.length);
					names.add(new String(fname));
					System.out.println(names.getLast());
					readGroup(da);
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

	private static void readGroup(DataAccess da) throws Exception {
		int len = da.readInt();
		while (len > 4 && !da.isEmpty()) {
			byte[] nb = new byte[80];
			da.readBytes(nb, 0, 80);
			Nodeid node = Nodeid.fromBinary(nb, 0);
			Nodeid p1 = Nodeid.fromBinary(nb, 20);
			Nodeid p2 = Nodeid.fromBinary(nb, 40);
			Nodeid cs = Nodeid.fromBinary(nb, 60);
			byte[] data = new byte[len-84];
			da.readBytes(data, 0, data.length);
			System.out.printf("%6d %s %s %s %s\n", len, node, p1, p2, cs);
			System.out.println(new String(data));
			len = da.isEmpty() ? 0 : da.readInt();
		}
	}
}
