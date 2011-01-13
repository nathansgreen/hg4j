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
		while (!da.isEmpty()) {
			int len = da.readInt();
			while (len > 4) {
				byte[] nb = new byte[20];
				da.readBytes(nb, 0, 20);
				Nodeid node = new Nodeid(nb, true);
				da.readBytes(nb, 0, 20);
				Nodeid p1 = new Nodeid(nb, true);
				da.readBytes(nb, 0, 20);
				Nodeid p2 = new Nodeid(nb, true);
				da.readBytes(nb, 0, 20);
				Nodeid cs = new Nodeid(nb, true);
				da.skip(len - 84);
				System.out.printf("%6d %s %s %s %s\n", len, node, p1, p2, cs);
				len = da.isEmpty() ? 0 : da.readInt();
			}
			System.out.println("Group done");
		}
	}
}
