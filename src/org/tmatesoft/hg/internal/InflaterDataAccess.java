/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * DataAccess counterpart for InflaterInputStream.
 * XXX is it really needed to be subclass of FilterDataAccess? 
 *   
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class InflaterDataAccess extends FilterDataAccess {

	private final Inflater inflater;
	private final byte[] inBuffer;
	private final ByteBuffer outBuffer;
	private int inflaterPos = 0;
	private int decompressedLength;

	public InflaterDataAccess(DataAccess dataAccess, long offset, int compressedLength) {
		this(dataAccess, offset, compressedLength, -1, new Inflater(), new byte[512], null);
	}

	public InflaterDataAccess(DataAccess dataAccess, long offset, int compressedLength, int actualLength) {
		this(dataAccess, offset, compressedLength, actualLength, new Inflater(), new byte[512], null);
	}

	public InflaterDataAccess(DataAccess dataAccess, long offset, int compressedLength, int actualLength, Inflater inflater, byte[] inBuf, ByteBuffer outBuf) {
		super(dataAccess, offset, compressedLength);
		if (inflater == null || inBuf == null) {
			throw new IllegalArgumentException();
		}
		this.inflater = inflater;
		this.decompressedLength = actualLength;
		inBuffer = inBuf;
		outBuffer = outBuf == null ? ByteBuffer.allocate(inBuffer.length * 2) : outBuf;
		outBuffer.limit(0); // there's nothing to read in the buffer 
	}
	
	@Override
	public InflaterDataAccess reset() throws IOException {
		super.reset();
		inflater.reset();
		inflaterPos = 0;
		outBuffer.clear().limit(0); // or flip(), to indicate nothing to read
		return this;
	}
	
	@Override
	protected int available() throws IOException {
		return length() - decompressedPosition();
	}
	
	@Override
	public boolean isEmpty() throws IOException {
		// can't use super.available() <= 0 because even when 0 < super.count < 6(?)
		// decompressedPos might be already == length() 
		return available() <= 0;
	}
	
	@Override
	public int length() throws IOException {
		if (decompressedLength != -1) {
			return decompressedLength;
		}
		decompressedLength = 0; // guard to avoid endless loop in case length() would get invoked from below.
		final int oldPos = decompressedPosition();
		final int inflatedUpTo = inflaterPos;
		int inflatedMore = 0, c;
		do {
			outBuffer.limit(outBuffer.position()); // pretend the buffer is consumed
			c = fillOutBuffer();
			inflatedMore += c;
		} while (c == outBuffer.capacity()); // once we unpacked less than capacity, input is over
		decompressedLength = inflatedUpTo + inflatedMore;
		reset();
		seek(oldPos);
		return decompressedLength;
	}
	
	@Override
	public void seek(int localOffset) throws IOException {
		if (localOffset < 0 /* || localOffset >= length() */) {
			throw new IllegalArgumentException();
		}
		int currentPos = decompressedPosition();
		if (localOffset >= currentPos) {
			skip(localOffset - currentPos);
		} else {
			reset();
			skip(localOffset);
		}
	}
	
	@Override
	public void skip(final int bytesToSkip) throws IOException {
		int bytes = bytesToSkip;
		if (bytes < 0) {
			bytes += decompressedPosition();
			if (bytes < 0) {
				throw new IOException(String.format("Underflow. Rewind past start of the slice. To skip:%d, decPos:%d, decLen:%d. Left:%d", bytesToSkip, inflaterPos, decompressedLength, bytes));
			}
			reset();
			// fall-through
		}
		while (!isEmpty() && bytes > 0) {
			int fromBuffer = outBuffer.remaining();
			if (fromBuffer > 0) {
				if (fromBuffer >= bytes) {
					outBuffer.position(outBuffer.position() + bytes);
					bytes = 0;
					break;
				} else {
					bytes -= fromBuffer;
					outBuffer.limit(outBuffer.position()); // mark consumed
					// fall through to fill the buffer
				}
			}
			fillOutBuffer();
		}
		if (bytes != 0) {
			throw new IOException(String.format("Underflow. Rewind past end of the slice. To skip:%d, decPos:%d, decLen:%d. Left:%d", bytesToSkip, inflaterPos, decompressedLength, bytes));
		}
	}

	@Override
	public byte readByte() throws IOException {
		if (!outBuffer.hasRemaining()) {
			fillOutBuffer();
		}
		return outBuffer.get();
	}

	@Override
	public void readBytes(byte[] b, int off, int len) throws IOException {
		int fromBuffer;
		do {
			fromBuffer = outBuffer.remaining();
			if (fromBuffer > 0) {
				if (fromBuffer >= len) {
					outBuffer.get(b, off, len);
					return;
				} else {
					outBuffer.get(b, off, fromBuffer);
					off += fromBuffer;
					len -= fromBuffer;
					// fall-through
				}
			}
			fromBuffer = fillOutBuffer();
		} while (len > 0 && fromBuffer > 0);
		if (len > 0) {
			// prevent hang up in this cycle if no more data is available, see Issue 25
			throw new EOFException(String.format("No more compressed data is available to satisfy request for %d bytes. [finished:%b, needDict:%b, needInp:%b, available:%d", len, inflater.finished(), inflater.needsDictionary(), inflater.needsInput(), super.available()));
		}
	}
	
	@Override
	public void readBytes(ByteBuffer buf) throws IOException {
		int len = Math.min(available(), buf.remaining());
		while (len > 0) {
			if (outBuffer.remaining() >= len) {
				ByteBuffer slice = outBuffer.slice();
				slice.limit(len);
				buf.put(slice);
				outBuffer.position(outBuffer.position() + len);
				return;
			} else { 
				len -= outBuffer.remaining();
				buf.put(outBuffer);
			}
			fillOutBuffer();
		}
	}
	
	private int decompressedPosition() {
		assert outBuffer.remaining() <= inflaterPos; 
		return inflaterPos - outBuffer.remaining();
	}
	
	// after #fillOutBuffer(), outBuffer is ready for read
	private int fillOutBuffer() throws IOException {
		assert !outBuffer.hasRemaining();
		try {
			int inflatedBytes = 0;
		    outBuffer.clear();
		    int len = outBuffer.capacity();
		    int off = 0;
		    do {
			    int n;
			    while ((n = inflater.inflate(outBuffer.array(), off, len)) == 0) {
			    	// XXX few last bytes (checksum?) may be ignored by inflater, thus inflate may return 0 in
			    	// perfectly legal conditions (when all data already expanded, but there are still some bytes
			    	// in the input stream)
					int toRead = -1;
					if (inflater.needsInput() && (toRead = super.available()) > 0) {
						// fill
						if (toRead > inBuffer.length) {
							toRead = inBuffer.length;
						}
						super.readBytes(inBuffer, 0, toRead);
						inflater.setInput(inBuffer, 0, toRead);
					} else {
						// inflated nothing and doesn't want any more data (or no date available) - assume we're done 
						assert inflater.finished();
						assert toRead <= 0;
						break;
					}
			    }
				off += n;
				len -= n;
				inflatedBytes += n;
		    } while (len > 0 && !inflater.finished()); // either the buffer is filled or nothing more to unpack
		    inflaterPos += inflatedBytes;
		    outBuffer.limit(inflatedBytes);
		    assert outBuffer.position() == 0; // didn't change since #clear() above
		    return inflatedBytes;
		} catch (DataFormatException e) {
		    String s = e.getMessage();
		    throw new ZipException(s != null ? s : "Invalid ZLIB data format");
		}
    }
}
