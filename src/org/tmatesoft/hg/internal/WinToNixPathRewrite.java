/*
 * Copyright (c) 2012 TMate Software Ltd
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
package org.tmatesoft.hg.internal;

import org.tmatesoft.hg.util.PathRewrite;

/**
 * Translate windows path separators to Unix/POSIX-style
 * 
 * @author Artem Tikhomirov
 * @author Tmate Software Ltd.
 */
public final class WinToNixPathRewrite implements PathRewrite {
	public CharSequence rewrite(CharSequence p) {
		// TODO handle . and .. (although unlikely to face them from GUI client)
		String path = p.toString();
		path = path.replace('\\', '/').replace("//", "/");
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		return path;
	}
}