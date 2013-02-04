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

import java.io.IOException;

/**
 * Serialization friend of {@link DataAccess}
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public class DataSerializer {
	
	public void writeByte(byte... values) throws IOException {
		write(values, 0, values.length);
	}

	public void writeInt(int... values) throws IOException {
		byte[] buf = new byte[4];
		for (int v : values) {
			bigEndian(v, buf, 0);
			write(buf, 0, buf.length);
		}
	}

	public void write(byte[] data, int offset, int length) throws IOException {
		throw new IOException("Attempt to write to non-existent file");
	}

	public void done() {
		// FIXME perhaps, shall allow IOException, too
		// no-op
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
	
	@Experimental(reason="Work in progress")
	interface DataSource {
		public void serialize(DataSerializer out) throws IOException;

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

		public void serialize(DataSerializer out) throws IOException {
			if (data != null) {
				out.write(data, 0, data.length);
			}
		}

		public int serializeLength() {
			return data == null ? 0 : data.length;
		}
		
	}
}
