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
package org.tmatesoft.hg.test;

import static org.junit.Assert.assertThat;

import java.util.concurrent.Callable;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.internal.runners.model.MultipleFailureException;
import org.junit.rules.ErrorCollector;

/**
 * Expose verify method for allow not-junit runs to check test outcome 
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
final class ErrorCollectorExt extends ErrorCollector {
	public void verify() throws Throwable {
		try {
			super.verify();
		} catch (MultipleFailureException ex) {
			for (Throwable t : ex.getFailures()) {
				t.printStackTrace();
			}
			throw ex;
		}
	}

	public <T> void checkThat(final String reason, final T value, final Matcher<T> matcher) {
		checkSucceeds(new Callable<Object>() {
			public Object call() throws Exception {
				assertThat(reason, value, matcher);
				return value;
			}
		});
	}
	
	public void assertTrue(final boolean value) {
		assertTrue(null, value);
	}

	public void assertTrue(final String reason, final boolean value) {
		checkSucceeds(new Callable<Object>() {
			public Object call() throws Exception {
				org.junit.Assert.assertTrue(reason, value);
				return null;
			}
		});
	}
	
	public <T> void assertEquals(T expected, T actual) {
		checkThat(null, actual, CoreMatchers.equalTo(expected));
	}
}