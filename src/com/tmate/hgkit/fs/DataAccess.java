/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.IOException;

/**
 * relevant parts of DataInput, non-stream nature (seek operation), explicit check for end of data.
 * convenient skip (+/- bytes)
 * Primary goal - effective file read, so that clients don't need to care whether to call few 
 * distinct getInt() or readBytes(totalForFewInts) and parse themselves instead in an attempt to optimize.
 * Name: ByteSource? DataSource, DataInput, ByteInput 
 */
public class DataAccess {
	public boolean isEmpty() {
		return true;
	}
	public long length() {
		return 0;
	}
	// get this instance into initial state
	public void reset() throws IOException {
		// nop, empty instance is always in the initial state
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

	// XXX decide whether may or may not change position in the DataAccess
	// FIXME exception handling is not right, just for the sake of quick test
	public byte[] byteArray() {
		byte[] rv = new byte[(int) length()];
		try {
			reset();
			readBytes(rv, 0, rv.length);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		return rv;
	}
}