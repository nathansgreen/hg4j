/*
 * Copyright (c) 2010-2012 TMate Software Ltd
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

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.util.LogFacility;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class DataAccessProvider {
	/**
	 * Boundary to start using file memory mapping instead of regular file access, in bytes.  
	 * Set to 0 to indicate mapping files into memory shall not be used.
	 * If set to -1, file of any size would be mapped in memory.
	 */
	public static final String CFG_PROPERTY_MAPIO_LIMIT				= "hg4j.dap.mapio_limit";
	public static final String CFG_PROPERTY_MAPIO_BUFFER_SIZE		= "hg4j.dap.mapio_buffer";
	public static final String CFG_PROPERTY_FILE_BUFFER_SIZE		= "hg4j.dap.file_buffer";
	
	private static final int DEFAULT_MAPIO_LIMIT = 100 * 1024; // 100 kB
	private static final int DEFAULT_FILE_BUFFER =   8 * 1024; // 8 kB
	private static final int DEFAULT_MAPIO_BUFFER = DEFAULT_MAPIO_LIMIT; // same as default boundary

	private final int mapioMagicBoundary;
	private final int bufferSize, mapioBufSize;
	private final SessionContext context;

	public DataAccessProvider(SessionContext ctx) {
		context = ctx;
		PropertyMarshal pm = new PropertyMarshal(ctx);
		mapioMagicBoundary = pm.getInt(CFG_PROPERTY_MAPIO_LIMIT, DEFAULT_MAPIO_LIMIT);
		bufferSize = pm.getInt(CFG_PROPERTY_FILE_BUFFER_SIZE, DEFAULT_FILE_BUFFER);
		mapioBufSize = pm.getInt(CFG_PROPERTY_MAPIO_BUFFER_SIZE, DEFAULT_MAPIO_BUFFER);
	}
	
	public DataAccessProvider(SessionContext ctx, int mapioBoundary, int regularBufferSize, int mapioBufferSize) {
		context = ctx;
		mapioMagicBoundary = mapioBoundary == 0 ? Integer.MAX_VALUE : mapioBoundary;
		bufferSize = regularBufferSize;
		mapioBufSize = mapioBufferSize;
	}

	public DataAccess create(File f) {
		if (!f.exists()) {
			return new DataAccess();
		}
		try {
			FileChannel fc = new FileInputStream(f).getChannel();
			long flen = fc.size();
			if (flen > mapioMagicBoundary) {
				// TESTS: bufLen of 1024 was used to test MemMapFileAccess
				return new MemoryMapFileAccess(fc, flen, mapioBufSize, context.getLog());
			} else {
				// XXX once implementation is more or less stable,
				// may want to try ByteBuffer.allocateDirect() to see
				// if there's any performance gain. 
				boolean useDirectBuffer = false; // XXX might be another config option
				// TESTS: bufferSize of 100 was used to check buffer underflow states when readBytes reads chunks bigger than bufSize
				return new FileAccess(fc, flen, bufferSize, useDirectBuffer, context.getLog());
			}
		} catch (IOException ex) {
			// unlikely to happen, we've made sure file exists.
			context.getLog().dump(getClass(), Error, ex, null);
		}
		return new DataAccess(); // non-null, empty.
	}

	private static class MemoryMapFileAccess extends DataAccess {
		private FileChannel fileChannel;
		private long position = 0; // always points to buffer's absolute position in the file
		private MappedByteBuffer buffer;
		private final long size;
		private final int memBufferSize;
		private final LogFacility logFacility;

		public MemoryMapFileAccess(FileChannel fc, long channelSize, int bufferSize, LogFacility log) {
			fileChannel = fc;
			size = channelSize;
			logFacility = log;
			memBufferSize = bufferSize > channelSize ? (int) channelSize : bufferSize; // no reason to waste memory more than there's data 
		}

		@Override
		public boolean isEmpty() {
			return position + (buffer == null ? 0 : buffer.position()) >= size;
		}
		
		@Override
		public DataAccess reset() throws IOException {
			longSeek(0);
			return this;
		}

		@Override
		public int length() {
			return Internals.ltoi(longLength());
		}
		
		@Override
		public long longLength() {
			return size;
		}
		
		@Override
		public void longSeek(long offset) {
			assert offset >= 0;
			// offset may not necessarily be further than current position in the file (e.g. rewind) 
			if (buffer != null && /*offset is within buffer*/ offset >= position && (offset - position) < buffer.limit()) {
				// cast is ok according to check above
				buffer.position(Internals.ltoi(offset - position));
			} else {
				position = offset;
				buffer = null;
			}
		}

		@Override
		public void seek(int offset) {
			longSeek(offset);
		}

		@Override
		public void skip(int bytes) throws IOException {
			assert bytes >= 0;
			if (buffer == null) {
				position += bytes;
				return;
			}
			if (buffer.remaining() > bytes) {
				buffer.position(buffer.position() + bytes);
			} else {
				position += buffer.position() + bytes;
				buffer = null;
			}
		}

		private void fill() throws IOException {
			if (buffer != null) {
				position += buffer.position(); 
			}
			long left = size - position;
			for (int i = 0; i < 3; i++) {
				try {
					buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, left < memBufferSize ? left : memBufferSize);
					return;
				} catch (IOException ex) {
					if (i == 2) {
						throw ex;
					}
					if (i == 0) {
						// if first attempt failed, try to free some virtual memory, see Issue 30 for details
						logFacility.dump(getClass(), Warn, ex, "Memory-map failed, gonna try gc() to free virtual memory");
					}
					try {
						buffer = null;
						System.gc();
						Thread.sleep((1+i) * 1000);
					} catch (Throwable t) {
						logFacility.dump(getClass(), Error, t, "Bad luck");
					}
				}
			}
		}

		@Override
		public void readBytes(byte[] buf, int offset, int length) throws IOException {
			if (buffer == null || !buffer.hasRemaining()) {
				fill();
			}
			// XXX in fact, we may try to create a MappedByteBuffer of exactly length size here, and read right away
			while (length > 0) {
				int tail = buffer.remaining();
				if (tail == 0) {
					throw new IOException();
				}
				if (tail >= length) {
					buffer.get(buf, offset, length);
				} else {
					buffer.get(buf, offset, tail);
					fill();
				}
				offset += tail;
				length -= tail;
			}
		}

		@Override
		public byte readByte() throws IOException {
			if (buffer == null || !buffer.hasRemaining()) {
				fill();
			}
			if (buffer.hasRemaining()) {
				return buffer.get();
			}
			throw new IOException();
		}

		@Override
		public void done() {
			buffer = null;
			if (fileChannel != null) {
				try {
					fileChannel.close();
				} catch (IOException ex) {
					logFacility.dump(getClass(), Warn, ex, null);
				}
				fileChannel = null;
			}
		}
	}

	// (almost) regular file access - FileChannel and buffers.
	private static class FileAccess extends DataAccess {
		private FileChannel fileChannel;
		private ByteBuffer buffer;
		private long bufferStartInFile = 0; // offset of this.buffer in the file.
		private final long size;
		private final LogFacility logFacility;

		public FileAccess(FileChannel fc, long channelSize, int bufferSizeHint, boolean useDirect, LogFacility log) {
			fileChannel = fc;
			size = channelSize;
			logFacility = log;
			final int capacity = size < bufferSizeHint ? (int) size : bufferSizeHint;
			buffer = useDirect ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
			buffer.flip(); // or .limit(0) to indicate it's empty
		}
		
		@Override
		public boolean isEmpty() {
			return bufferStartInFile + buffer.position() >= size;
		}
		
		@Override
		public DataAccess reset() throws IOException {
			longSeek(0);
			return this;
		}

		@Override
		public int length() {
			return Internals.ltoi(longLength());
		}
		
		@Override
		public long longLength() {
			return size;
		}

		@Override
		public void longSeek(long offset) throws IOException {
			if (offset > size) {
				throw new IllegalArgumentException(String.format("Can't seek to %d for the file of size %d (buffer start:%d)", offset, size, bufferStartInFile));
			}
			if (offset < bufferStartInFile + buffer.limit() && offset >= bufferStartInFile) {
				// cast to int is safe, we've checked we fit into buffer
				buffer.position(Internals.ltoi(offset - bufferStartInFile));
			} else {
				// out of current buffer, invalidate it (force re-read) 
				// XXX or ever re-read it right away?
				bufferStartInFile = offset;
				buffer.clear();
				buffer.limit(0); // or .flip() to indicate we switch to reading
				fileChannel.position(offset);
			}
		}

		@Override
		public void seek(int offset) throws IOException {
			longSeek(offset);
		}

		@Override
		public void skip(int bytes) throws IOException {
			final int newPos = buffer.position() + bytes;
			if (newPos >= 0 && newPos < buffer.limit()) {
				// no need to move file pointer, just rewind/seek buffer 
				buffer.position(newPos);
			} else {
				//
				longSeek(bufferStartInFile + newPos);
			}
		}

		private boolean fill() throws IOException {
			if (!buffer.hasRemaining()) {
				bufferStartInFile += buffer.limit();
				buffer.clear();
				if (bufferStartInFile < size) { // just in case there'd be any exception on EOF, not -1 
					fileChannel.read(buffer);
					// may return -1 when EOF, but empty will reflect this, hence no explicit support here   
				}
				buffer.flip();
			}
			return buffer.hasRemaining();
		}

		@Override
		public void readBytes(byte[] buf, int offset, int length) throws IOException {
			if (!buffer.hasRemaining()) {
				fill();
			}
			while (length > 0) {
				int tail = buffer.remaining();
				if (tail == 0) {
					throw new IOException(); // shall not happen provided stream contains expected data and no attempts to read past isEmpty() == true are made.
				}
				if (tail >= length) {
					buffer.get(buf, offset, length);
				} else {
					buffer.get(buf, offset, tail);
					fill();
				}
				offset += tail;
				length -= tail;
			}
		}

		@Override
		public byte readByte() throws IOException {
			if (buffer.hasRemaining()) {
				return buffer.get();
			}
			if (fill()) {
				return buffer.get();
			}
			throw new IOException();
		}

		@Override
		public void done() {
			if (buffer != null) {
				buffer = null;
			}
			if (fileChannel != null) {
				try {
					fileChannel.close();
				} catch (IOException ex) {
					logFacility.dump(getClass(), Warn, ex, null);
				}
				fileChannel = null;
			}
		}
	}
}
