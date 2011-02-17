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
package org.tmatesoft.hg.util;

import java.util.LinkedList;
import java.util.List;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface PathRewrite {

	// XXX think over CharSequence use instead of String
	public String rewrite(String path);
	
	public static class Empty implements PathRewrite {
		public String rewrite(String path) {
			return path;
		}
	}

	public class Composite implements PathRewrite {
		private List<PathRewrite> chain;

		public Composite(PathRewrite... e) {
			LinkedList<PathRewrite> r = new LinkedList<PathRewrite>();
			for (int i = (e == null ? -1 : e.length); i >=0; i--) {
				r.addFirst(e[i]);
			}
			chain = r;
		}
		public Composite chain(PathRewrite e) {
			chain.add(e);
			return this;
		}

		public String rewrite(String path) {
			for (PathRewrite pr : chain) {
				path = pr.rewrite(path);
			}
			return path;
		}
	}
}
