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
package org.tmatesoft.hg.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.ByteArrayDataAccess;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.InflaterDataAccess;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestInflaterDataAccess {
	@Rule
	public final ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	private final byte[] testContent1 = "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.".getBytes();
	
	private DataAccess zip(byte[] source) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DeflaterOutputStream dos = new DeflaterOutputStream(bos);
		dos.write(source);
		dos.flush();
		dos.close();
		return new ByteArrayDataAccess(bos.toByteArray());
	}
	
	@Test
	public void testSeek() throws Exception {
		DataAccess zip = zip(testContent1);
		InflaterDataAccess ida = new InflaterDataAccess(zip, 0, zip.length(), -1, new Inflater(), new byte[25]);
		ida.seek(20);
		final int bufferCapacity = 10;
		ByteBuffer chunk1 = ByteBuffer.allocate(bufferCapacity);
		ida.readBytes(chunk1);
		errorCollector.assertTrue(new ByteArraySlice(testContent1, 20, bufferCapacity).equalsTo(chunk1.array()));
		ida.skip(-bufferCapacity);
		ByteBuffer chunk2 = ByteBuffer.allocate(bufferCapacity);
		ida.readBytes(chunk2);
		errorCollector.assertEquals(chunk1, chunk2);
	}
	
	@Test
	public void testLength() throws Exception {
		DataAccess zip = zip(testContent1);
		InflaterDataAccess ida = new InflaterDataAccess(zip, 0, zip.length(), -1, new Inflater(), new byte[25]);
		errorCollector.assertEquals("Plain #length()", testContent1.length, ida.length());
		//
		ida = new InflaterDataAccess(zip, 0, zip.length(), -1, new Inflater(), new byte[25]);
		byte[] dummy = new byte[30];
		ida.readBytes(dummy, 0, dummy.length);
		errorCollector.assertEquals("#length() after readBytes()", testContent1.length, ida.length());
		//
		ida = new InflaterDataAccess(zip, 0, zip.length(), -1, new Inflater(), new byte[25]);
		// consume most of the stream, so that all original compressed data is already read
		dummy = new byte[testContent1.length - 1];
		ida.readBytes(dummy, 0, dummy.length);
		errorCollector.assertEquals("#length() after origin was completely read", testContent1.length, ida.length());
		//
		errorCollector.assertFalse(ida.isEmpty()); // check InflaterDataAccess#available() positive
	}

	@Test
	public void testReadBytes() throws Exception {
		DataAccess zip = zip(testContent1);
		InflaterDataAccess ida = new InflaterDataAccess(zip, 0, zip.length(), -1, new Inflater(), new byte[25]);
		ida.skip(10);
		byte[] chunk1 = new byte[22];
		ida.readBytes(chunk1, 0, 20);
		chunk1[20] = ida.readByte();
		chunk1[21] = ida.readByte();
		ida.skip(5);
		byte[] chunk2 = new byte[12];
		chunk2[0] = ida.readByte();
		chunk2[1] = ida.readByte();
		ida.readBytes(chunk2, 2, 10);
		errorCollector.assertTrue(new ByteArraySlice(testContent1, 10, 22).equalsTo(chunk1));
		errorCollector.assertTrue(new ByteArraySlice(testContent1, 10+22+5, 12).equalsTo(chunk2));
		int consumed = 10+22+5+12;
		// 
		// check that even when original content is completely unpacked, leftovers in the outBuffer are recognized   
		ida.readBytes(ByteBuffer.allocate(testContent1.length - consumed - 2)); // unpack up to an end (almost)
		errorCollector.assertFalse(ida.isEmpty()); // check InflaterDataAccess#available() positive
		//
		ByteBuffer chunk3 = ByteBuffer.allocate(10);
		ida.readBytes(chunk3);
		errorCollector.assertEquals(2, chunk3.flip().remaining());
	}

	private static class ByteArraySlice {
		public final byte[] array;
		public final int offset, length;

		public ByteArraySlice(byte[] array, int offset, int length) {
			this.array = array;
			this.offset = offset;
			this.length = length;
		}
		
		public boolean equalsTo(byte[] another) {
			if (another == null || another.length != length) {
				return false;
			}
			for (int i = 0; i < length; i++) {
				if (array[offset + i] != another[i]) {
					return false;
				}
			}
			return true;
		}
	}
}
