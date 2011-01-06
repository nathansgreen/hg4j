/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 *
 * @author artem
 */
public class DataAccessProvider {

	private final int mapioMagicBoundary;
	private final int bufferSize;

	public DataAccessProvider() {
		mapioMagicBoundary = 100 * 1024;
		bufferSize = 8 * 1024;
	}

	public DataAccess create(File f) {
		if (!f.exists()) {
			return new DataAccess();
		}
		try {
			FileChannel fc = new FileInputStream(f).getChannel();
			if (fc.size() > mapioMagicBoundary) {
				return new MemoryMapFileAccess(fc, fc.size());
			} else {
				// XXX once implementation is more or less stable,
				// may want to try ByteBuffer.allocateDirect() to see
				// if there's any performance gain. 
				boolean useDirectBuffer = false;
				return new FileAccess(fc, fc.size(), bufferSize, useDirectBuffer);
			}
		} catch (IOException ex) {
			// unlikely to happen, we've made sure file exists.
			ex.printStackTrace(); // FIXME log error
		}
		return new DataAccess(); // non-null, empty.
	}

	// DOESN'T WORK YET 
	private static class MemoryMapFileAccess extends DataAccess {
		private FileChannel fileChannel;
		private final long size;
		private long position = 0;

		public MemoryMapFileAccess(FileChannel fc, long channelSize) {
			fileChannel = fc;
			size = channelSize;
		}

		@Override
		public void seek(long offset) {
			position = offset;
		}

		@Override
		public void skip(int bytes) throws IOException {
			position += bytes;
		}

		private boolean fill() throws IOException {
			final int BUFFER_SIZE = 8 * 1024;
			long left = size - position;
			MappedByteBuffer rv = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, left < BUFFER_SIZE ? left : BUFFER_SIZE);
			position += rv.capacity();
			return rv.hasRemaining();
		}

		@Override
		public void done() {
			if (fileChannel != null) {
				try {
					fileChannel.close();
				} catch (IOException ex) {
					ex.printStackTrace(); // log debug
				}
				fileChannel = null;
			}
		}
	}

	// (almost) regular file access - FileChannel and buffers.
	private static class FileAccess extends DataAccess {
		private FileChannel fileChannel;
		private final long size;
		private ByteBuffer buffer;
		private long bufferStartInFile = 0; // offset of this.buffer in the file.

		public FileAccess(FileChannel fc, long channelSize, int bufferSizeHint, boolean useDirect) {
			fileChannel = fc;
			size = channelSize;
			final int capacity = size < bufferSizeHint ? (int) size : bufferSizeHint;
			buffer = useDirect ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
			buffer.flip(); // or .limit(0) to indicate it's empty
		}
		
		@Override
		public boolean isEmpty() {
			return bufferStartInFile + buffer.position() >= size;
		}
		
		@Override
		public void seek(long offset) throws IOException {
			if (offset > size) {
				throw new IllegalArgumentException();
			}
			if (offset < bufferStartInFile + buffer.limit() && offset >= bufferStartInFile) {
				buffer.position((int) (offset - bufferStartInFile));
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
		public void skip(int bytes) throws IOException {
			final int newPos = buffer.position() + bytes;
			if (newPos >= 0 && newPos < buffer.limit()) {
				// no need to move file pointer, just rewind/seek buffer 
				buffer.position(newPos);
			} else {
				//
				seek(bufferStartInFile + newPos);
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
			final int tail = buffer.remaining();
			if (tail >= length) {
				buffer.get(buf, offset, length);
			} else {
				buffer.get(buf, offset, tail);
				if (fill()) {
					buffer.get(buf, offset + tail, length - tail);
				} else {
					throw new IOException(); // shall not happen provided stream contains expected data and no attempts to read past nonEmpty() == false are made. 
				}
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
					ex.printStackTrace(); // log debug
				}
				fileChannel = null;
			}
		}
	}
}
