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
package org.tmatesoft.hg.internal.diff;

import org.tmatesoft.hg.core.HgAnnotateCommand.LineInfo;

/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
final class LineImpl implements LineInfo {
	private int ln;
	private int origLine;
	private int rev;
	private byte[] content;

	void init(int line, int firstAppearance, int csetRev, byte[] cnt) {
		ln = line;
		origLine = firstAppearance;
		rev = csetRev;
		content = cnt;
	}

	public int getLineNumber() {
		return ln;
	}


	public int getOriginLineNumber() {
		return origLine;
	}

	public int getChangesetIndex() {
		return rev;
	}

	public byte[] getContent() {
		return content;
	}
}