/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.IOException;

/**
 *
 * @author artem
 */
public class ByteArrayDataAccess extends DataAccess {

	private final byte[] data;
	private final int offset;
	private final int length;
	private int pos;

	public ByteArrayDataAccess(byte[] data) {
		this(data, 0, data.length);
	}

	public ByteArrayDataAccess(byte[] data, int offset, int length) {
		this.data = data;
		this.offset = offset;
		this.length = length;
		pos = 0;
	}
	
	@Override
	public byte readByte() throws IOException {
		if (pos >= length) {
			throw new IOException();
		}
		return data[offset + pos++];
	}
	@Override
	public void readBytes(byte[] buf, int off, int len) throws IOException {
		if (len > (this.length - pos)) {
			throw new IOException();
		}
		System.arraycopy(data, pos, buf, off, len);
		pos += len;
	}

	@Override
	public void reset() {
		pos = 0;
	}
	@Override
	public long length() {
		return length;
	}
	@Override
	public void seek(long offset) {
		pos = (int) offset;
	}
	@Override
	public void skip(int bytes) throws IOException {
		seek(pos + bytes);
	}
	@Override
	public boolean isEmpty() {
		return pos >= length;
	}
	
	//
	
	// when byte[] needed from DA, we may save few cycles and some memory giving this (otherwise unsafe) access to underlying data
	@Override
	public byte[] byteArray() {
		return data;
	}
}
