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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.test;

import java.util.Arrays;

import org.junit.Assert;
import org.tmatesoft.hg.core.RepositoryFacade;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.repo.HgDataFile;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestByteChannel {

	public static void main(String[] args) throws Exception {
		RepositoryFacade rf = new RepositoryFacade();
		rf.init();
		HgDataFile file = rf.getRepository().getFileNode("TODO");
		for (int i = file.getRevisionCount() - 1; i >= 0; i--) {
			System.out.print("Content for revision:" + i);
			compareContent(file, i);
			System.out.println(" OK");
		}
		//CatCommand cmd = rf.createCatCommand();
	}

	private static void compareContent(HgDataFile file, int rev) throws Exception {
		byte[] oldAccess = file.content(rev);
		ByteArrayChannel ch = new ByteArrayChannel();
		file.content(rev, ch);
		byte[] newAccess = ch.toArray();
		Assert.assertArrayEquals(oldAccess, newAccess);
		// don't trust anyone (even JUnit) 
		if (!Arrays.equals(oldAccess, newAccess)) {
			throw new RuntimeException("Failed:" + rev);
		}
	}
}
