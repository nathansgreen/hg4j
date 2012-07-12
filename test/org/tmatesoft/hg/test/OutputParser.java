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

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface OutputParser {

	public void parse(CharSequence seq);

	public class Stub implements OutputParser {
		private boolean shallDump;
		private CharSequence result;

		public Stub() {
			this(false);
		}
		public Stub(boolean dump) {
			shallDump = dump;
		}
		public void parse(CharSequence seq) {
			result = seq;
			if (shallDump) {
				System.out.println(seq);
			} 
			// else no-op
		}
		public CharSequence result() {
			return result;
		}

		public Iterable<String> lines() {
			return lines("(.+)$");
		}
		public Iterable<String> lines(String pattern) {
			final Matcher m = Pattern.compile(pattern, Pattern.MULTILINE).matcher(result);
			class S implements Iterable<String>, Iterator<String> {
				public Iterator<String> iterator() {
					return this;
				}
				private boolean next;
				{
					next = m.find();
				}

				public boolean hasNext() {
					return next;
				}

				public String next() {
					if (next) {
						String rv = m.group();
						next = m.find();
						return rv;
					}
					throw new NoSuchElementException();
				}

				public void remove() {
					throw new UnsupportedOperationException();
				}
			};
			return new S();
		}
	}
}
