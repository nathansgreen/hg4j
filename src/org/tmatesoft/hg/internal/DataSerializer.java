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
package org.tmatesoft.hg.internal;

import java.io.ByteArrayOutputStream;

import org.tmatesoft.hg.core.HgIOException;

/**
 * Serialization friend of {@link DataAccess}
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public class DataSerializer {
	private byte[] buffer;
	
	public void writeByte(byte... values) throws HgIOException {
		write(values, 0, values.length);
	}

	public void writeInt(int... values) throws HgIOException {
		ensureBufferSize(4*values.length); // sizeof(int)
		int idx = 0;
		for (int v : values) {
			bigEndian(v, buffer, idx);
			idx += 4;
		}
		write(buffer, 0, idx);
	}

	public void write(byte[] data, int offset, int length) throws HgIOException {
		throw new HgIOException("Attempt to write to non-existent file", null);
	}

	public void done() throws HgIOException {
		// no-op
	}
	
	private void ensureBufferSize(int bytesNeeded) {
		if (buffer == null || buffer.length < bytesNeeded) {
			buffer = new byte[bytesNeeded];
		}
	}

	/**
	 * Writes 4 bytes of supplied value into the buffer at given offset, big-endian. 
	 */
	public static final void bigEndian(int value, byte[] buffer, int offset) {
		assert offset + 4 <= buffer.length; 
		buffer[offset++] = (byte) ((value >>> 24) & 0x0ff);
		buffer[offset++] = (byte) ((value >>> 16) & 0x0ff);
		buffer[offset++] = (byte) ((value >>> 8) & 0x0ff);
		buffer[offset++] = (byte) (value & 0x0ff);
	}
	
	/**
	 * Denotes an entity that wants to/could be serialized
	 */
	@Experimental(reason="Work in progress")
	interface DataSource {
		/**
		 * Invoked once for a single write operation, 
		 * although the source itself may get serialized several times
		 */
		public void serialize(DataSerializer out) throws HgIOException;

		/**
		 * Hint of data length it would like to writes
		 * @return -1 if can't answer
		 */
		public int serializeLength();
	}
	
	public static class ByteArrayDataSource implements DataSource {
		
		private final byte[] data;

		public ByteArrayDataSource(byte[] bytes) {
			data = bytes;
		}

		public void serialize(DataSerializer out) throws HgIOException {
			if (data != null) {
				out.write(data, 0, data.length);
			}
		}

		public int serializeLength() {
			return data == null ? 0 : data.length;
		}
	}
	
	public static class ByteArrayDataSerializer extends DataSerializer {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		@Override
		public void write(byte[] data, int offset, int length) {
			out.write(data, offset, length);
		}
		
		public byte[] toByteArray() {
			return out.toByteArray();
		}
	}
}
