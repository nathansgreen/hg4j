/*
 * Copyright (c) 2013 TMate Software Ltd
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

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.COWTransaction;
import org.tmatesoft.hg.internal.Transaction;

/**
 * Check transaction rollback/commit as it's tricky to test transactions as part of pull/push commands
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestTransaction {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	@Test
	public void testCopyOnWriteTransaction() throws Exception {
		final BasicSessionContext ctx = new BasicSessionContext(null);
		Transaction.Factory f = new COWTransaction.Factory();
		File dir = RepoUtils.createEmptyDir("test-transaction-cow");
		File f1 = new File(dir, "f1");
		File f2 = new File(dir, "f2");
		File f3 = new File(dir, "f3");
		RepoUtils.createFile(f1, "1");
		assertTrue(f1.exists());
		assertFalse(f2.exists());
		assertFalse(f3.exists());
		//
		// transaction commit
		Transaction tr1 = f.create(ctx);
		File tf1 = tr1.prepare(f1);
		RepoUtils.modifyFileAppend(tf1, "2");
		tr1.done(tf1);
		File tf2 = tr1.prepare(f2);
		errorCollector.assertTrue(tf2.exists());
		RepoUtils.modifyFileAppend(tf2, "A");
		tr1.done(tf2);
		tr1.commit();
		errorCollector.assertTrue(f1.isFile());
		errorCollector.assertTrue(f2.isFile());
		errorCollector.assertEquals("12", read(f1));
		errorCollector.assertEquals("A", read(f2));
		//
		// transaction rollback
		assertFalse(f3.exists());
		Transaction tr2 = f.create(ctx);
		tf1 = tr2.prepare(f1);
		RepoUtils.modifyFileAppend(tf1, "3");
		tr2.done(tf1);
		errorCollector.assertEquals("123", read(tf1));
		tf2 = tr2.prepare(f2);
		RepoUtils.modifyFileAppend(tf2, "B");
		tr2.done(tf2);
		errorCollector.assertEquals("AB", read(tf2));
		File tf3 = tr2.prepare(f3);
		errorCollector.assertTrue(tf3.exists());
		RepoUtils.modifyFileAppend(tf3, "!");
		tr2.done(tf3);
		errorCollector.assertEquals("!", read(tf3));
		tr2.rollback();
		errorCollector.assertTrue(f1.isFile());
		errorCollector.assertTrue(f2.isFile());
		errorCollector.assertFalse(f3.isFile());
		errorCollector.assertEquals("12", read(f1));
		errorCollector.assertEquals("A", read(f2));
	}

	String read(File f) throws IOException {
		StringBuilder sb = new StringBuilder();
		FileReader fr = new FileReader(f);
		int ch;
		while ((ch = fr.read()) != -1) {
			sb.append((char) ch);
		}
		fr.close();
		return sb.toString();
	}
}
