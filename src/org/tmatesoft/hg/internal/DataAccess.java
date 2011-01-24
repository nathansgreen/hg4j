/*
 * Copyright (c) 2010 TMate Software Ltd
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
package org.tmatesoft.hg.internal;

import java.io.IOException;

/**
 * relevant parts of DataInput, non-stream nature (seek operation), explicit check for end of data.
 * convenient skip (+/- bytes)
 * Primary goal - effective file read, so that clients don't need to care whether to call few 
 * distinct getInt() or readBytes(totalForFewInts) and parse themselves instead in an attempt to optimize.  
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class DataAccess {
	public boolean isEmpty() {
		return true;
	}
	// absolute positioning
	public void seek(long offset) throws IOException {
		throw new UnsupportedOperationException();
	}
	// relative positioning
	public void skip(int bytes) throws IOException {
		throw new UnsupportedOperationException();
	}
	// shall be called once this object no longer needed
	public void done() {
		// no-op in this empty implementation
	}
	public int readInt() throws IOException {
		byte[] b = new byte[4];
		readBytes(b, 0, 4);
		return b[0] << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
	}
	public long readLong() throws IOException {
		byte[] b = new byte[8];
		readBytes(b, 0, 8);
		int i1 = b[0] << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
		int i2 = b[4] << 24 | (b[5] & 0xFF) << 16 | (b[6] & 0xFF) << 8 | (b[7] & 0xFF);
		return ((long) i1) << 32 | ((long) i2 & 0xFFFFFFFF);
	}
	public void readBytes(byte[] buf, int offset, int length) throws IOException {
		throw new UnsupportedOperationException();
	}
	public byte readByte() throws IOException {
		throw new UnsupportedOperationException();
	}
}