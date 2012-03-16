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
	}
}
