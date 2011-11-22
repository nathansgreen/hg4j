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

import junit.framework.Assert;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.util.PathRewrite;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestStorePath {
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	private PathRewrite storePathHelper;

	public static void main(String[] args) throws Throwable {
		final TestStorePath test = new TestStorePath();
		test.testWindowsFilenames();
		test.testHashLongPath();
		test.errorCollector.verify();
	}
	
	public TestStorePath() {
		final Internals i = new Internals();
		i.setStorageConfig(1, 0x7);
		storePathHelper = i.buildDataFilesHelper();
	}

	@Test
	public void testWindowsFilenames() {
		// see http://mercurial.selenic.com/wiki/fncacheRepoFormat#Encoding_of_Windows_reserved_names
		String n1 = "aux.bla/bla.aux/prn/PRN/lpt/com3/nul/coma/foo.NUL/normal.c";
		String r1 = "store/data/au~78.bla/bla.aux/pr~6e/_p_r_n/lpt/co~6d3/nu~6c/coma/foo._n_u_l/normal.c.i";
		Assert.assertEquals("Windows filenames are ", r1, storePathHelper.rewrite(n1));
	}

	@Test
	public void testHashLongPath() {
		String n1 = "AUX/SECOND/X.PRN/FOURTH/FI:FTH/SIXTH/SEVENTH/EIGHTH/NINETH/TENTH/ELEVENTH/LOREMIPSUM.TXT";
		String r1 = "store/dh/au~78/second/x.prn/fourth/fi~3afth/sixth/seventh/eighth/nineth/tenth/loremia20419e358ddff1bf8751e38288aff1d7c32ec05.i";
		String n2 = "enterprise/openesbaddons/contrib-imola/corba-bc/netbeansplugin/wsdlExtension/src/main/java/META-INF/services/org.netbeans.modules.xml.wsdl.bindingsupport.spi.ExtensibilityElementTemplateProvider";
		String r2 = "store/dh/enterpri/openesba/contrib-/corba-bc/netbeans/wsdlexte/src/main/java/org.net7018f27961fdf338a598a40c4683429e7ffb9743.i";
		String n3 = "AUX.THE-QUICK-BROWN-FOX-JU:MPS-OVER-THE-LAZY-DOG-THE-QUICK-BROWN-FOX-JUMPS-OVER-THE-LAZY-DOG.TXT";
		String r3 = "store/dh/au~78.the-quick-brown-fox-ju~3amps-over-the-lazy-dog-the-quick-brown-fox-jud4dcadd033000ab2b26eb66bae1906bcb15d4a70.i";
		// TODO segment[8] == [. ], segment[8] in the middle of windows reserved name or character (to see if ~xx is broken)
		errorCollector.checkThat(storePathHelper.rewrite(n1), CoreMatchers.<CharSequence>equalTo(r1));
		errorCollector.checkThat(storePathHelper.rewrite(n2), CoreMatchers.<CharSequence>equalTo(r2));
		errorCollector.checkThat(storePathHelper.rewrite(n3), CoreMatchers.<CharSequence>equalTo(r3));
	}

	@Test
	public void testIndexFileExtensionIsPartOfTheName() {
		// with "data/" and ".i" 121 chars
		String n1 = "src/jgit/main/org.eclipse.jgit.packaging/org.eclipse.jgit.junit.feature/.settings/org.eclipse.core.resources.prefs";
		String r1 = "store/dh/src/jgit/main/org.ecli/org.ecli/~2esetti/org.eclipse.core.resources.prefs.ie1f4f9eed1009d220cd5afa6e01e7d9a06c02201.i";
		// with "data/" and ".i" 122 chars
		String n2 = "src/jgit/main/org.eclipse.jgit.packaging/org.eclipse.jgit.source.feature/.settings/org.eclipse.core.resources.prefs";
		String r2 = "store/dh/src/jgit/main/org.ecli/org.ecli/~2esetti/org.eclipse.core.resources.prefs.i5193ab724f0225178fa949738444c4aac05e5e00.i";
		//
		// with "data/" and ".i" just 118 chars, use as sanity that it's not mangled
		String n3 = "src/jgit/main/org.eclipse.jgit.packaging/org.eclipse.jgit.updatesite/.settings/org.eclipse.core.resources.prefs";
		String r3 = "store/data/src/jgit/main/org.eclipse.jgit.packaging/org.eclipse.jgit.updatesite/~2esettings/org.eclipse.core.resources.prefs.i";
		errorCollector.checkThat(storePathHelper.rewrite(n1), CoreMatchers.<CharSequence>equalTo(r1));
		errorCollector.checkThat(storePathHelper.rewrite(n2), CoreMatchers.<CharSequence>equalTo(r2));
		errorCollector.checkThat(storePathHelper.rewrite(n3), CoreMatchers.<CharSequence>equalTo(r3));
	}
}
