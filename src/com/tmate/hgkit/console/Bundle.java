/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.io.File;

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
			while (!da.isEmpty()) {
				int len = da.readInt();
				while (len > 4) {
					byte[] nb = new byte[80];
					da.readBytes(nb, 0, 80);
					Nodeid node = Nodeid.fromBinary(nb, 0);
					Nodeid p1 = Nodeid.fromBinary(nb, 20);
					Nodeid p2 = Nodeid.fromBinary(nb, 40);
					Nodeid cs = Nodeid.fromBinary(nb, 60);
					da.skip(len - 84);
					System.out.printf("%6d %s %s %s %s\n", len, node, p1, p2, cs);
					len = da.isEmpty() ? 0 : da.readInt();
				}
				System.out.println("Group done");
			}
		} finally {
			da.done();
		}
	}
}
