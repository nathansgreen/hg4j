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
package org.tmatesoft.hg.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RequiresFile {
	public static final int STORE 		= 1 << 0;
	public static final int FNCACHE		= 1 << 1;
	public static final int DOTENCODE	= 1 << 2;
	public static final int REVLOGV0	= 1 << 31;
	public static final int REVLOGV1	= 1 << 30;
	
	public RequiresFile() {
	}

	/**
	 * Settings from requires file as bits
	 */
	public int parse(File requiresFile) throws IOException {
		if (!requiresFile.exists()) {
			// TODO check what's going on in Mercurial if no requires exist
			return 0;
		}
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(new FileInputStream(requiresFile)));
			String line;
			int flags = 0;
			while ((line = br.readLine()) != null) {
				if ("revlogv1".equals(line)) {
					flags |= REVLOGV1;
				} else if ("store".equals(line)) {
					flags |= STORE;
				} else if ("fncache".equals(line)) {
					flags |= FNCACHE;
				} else if ("dotencode".equals(line)) {
					flags |= DOTENCODE;
				}
			}
			if ((flags & REVLOGV1) == 0) {
				flags |= REVLOGV0; // TODO check if there's no special flag for V0 indeed
			}
			return flags;
		} finally {
			if (br != null) {
				br.close();
			}
		}
	}
}
