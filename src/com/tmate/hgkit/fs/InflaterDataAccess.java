/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.EOFException;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * DataAccess counterpart for InflaterInputStream.
 * XXX is it really needed to be subclass of FilterDataAccess? 
 * @author artem
 */
public class InflaterDataAccess extends FilterDataAccess {

	private final Inflater inflater;
	private final byte[] buffer;
	private final byte[] singleByte = new byte[1];
	private int decompressedPos = 0;
	private int decompressedLength = -1;

	public InflaterDataAccess(DataAccess dataAccess, long offset, int length) {
		this(dataAccess, offset, length, new Inflater(), 512);
	}

	public InflaterDataAccess(DataAccess dataAccess, long offset, int length, Inflater inflater, int bufSize) {
		super(dataAccess, offset, length);
		this.inflater = inflater;
		buffer = new byte[bufSize];
	}
	
	@Override
	public void reset() throws IOException {
		super.reset();
		inflater.reset();
		decompressedPos = 0;
	}
	
	@Override
	protected int available() {
		throw new IllegalStateException("Can't tell how much uncompressed data left");
	}
	
	@Override
	public boolean isEmpty() {
		return super.available() <= 0 && inflater.finished(); // and/or inflater.getRemaining() <= 0 ?
	}
	
	@Override
	public long length() {
		if (decompressedLength != -1) {
			return decompressedLength;
		}
		int c = 0;
		try {
			int oldPos = decompressedPos;
			while (!isEmpty()) {
				readByte();
				c++;
			}
			decompressedLength = c + oldPos;
			reset();
			seek(oldPos);
			return decompressedLength;
		} catch (IOException ex) {
			ex.printStackTrace(); // FIXME log error
			decompressedLength = -1; // better luck next time?
			return 0;
		}
	}
	
	@Override
	public void seek(long localOffset) throws IOException {
		System.out.println("Seek: " + localOffset);
		if (localOffset < 0 /* || localOffset >= length() */) {
			throw new IllegalArgumentException();
		}
		if (localOffset >= decompressedPos) {
			skip((int) (localOffset - decompressedPos));
		} else {
			reset();
			skip((int) localOffset);
		}
	}
	
	@Override
	public void skip(int bytes) throws IOException {
		if (bytes < 0) {
			bytes += decompressedPos;
			if (bytes < 0) {
				throw new IOException("Underflow. Rewind past start of the slice.");
			}
			reset();
			// fall-through
		}
		while (!isEmpty() && bytes > 0) {
			readByte();
			bytes--;
		}
		if (bytes != 0) {
			throw new IOException("Underflow. Rewind past end of the slice");
		}
	}

	@Override
	public byte readByte() throws IOException {
		readBytes(singleByte, 0, 1);
		return singleByte[0];
	}

	@Override
	public void readBytes(byte[] b, int off, int len) throws IOException {
		try {
		    int n;
		    while (len > 0) {
			    while ((n = inflater.inflate(b, off, len)) == 0) {
					if (inflater.finished() || inflater.needsDictionary()) {
	                    throw new EOFException();
					}
					if (inflater.needsInput()) {
						// fill:
						int toRead = super.available();
						if (toRead > buffer.length) {
							toRead = buffer.length;
						}
						super.readBytes(buffer, 0, toRead);
						inflater.setInput(buffer, 0, toRead);
					}
			    }
				off += n;
				len -= n;
				decompressedPos += n;
				if (len == 0) {
					return; // filled
				}
		    }
		} catch (DataFormatException e) {
		    String s = e.getMessage();
		    throw new ZipException(s != null ? s : "Invalid ZLIB data format");
		}
    }
}
