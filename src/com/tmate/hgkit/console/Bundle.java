/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.io.File;

import com.tmate.hgkit.fs.DataAccessProvider;
import com.tmate.hgkit.ll.HgBundle;

/**
 *
 * @author artem
 */
public class Bundle {

	public static void main(String[] args) throws Exception {
		File bundleFile = new File("/temp/hg/hg-bundle-a78c980749e3.tmp");
		DataAccessProvider dap = new DataAccessProvider();
		HgBundle hgBundle = new HgBundle(dap, bundleFile);
		hgBundle.read();
	}
}
