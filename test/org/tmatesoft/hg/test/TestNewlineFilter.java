/*
 * Copyright (c) 2011 TMate Software Ltd
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.internal.NewlineFilter;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestNewlineFilter {

	public static void main(String[] args) throws Exception {
		FileInputStream fis = new FileInputStream(new File("/temp/design.lf.txt"));
		FileOutputStream fos = new FileOutputStream(new File("/temp/design.newline.out"));
		ByteBuffer b = ByteBuffer.allocate(12);
		NewlineFilter nlFilter = NewlineFilter.createNix2Win(true);
		while (fis.getChannel().read(b) != -1) {
			b.flip(); // get ready to be read
			ByteBuffer f = nlFilter.filter(b);
			fos.getChannel().write(f); // XXX in fact, f may not be fully consumed
			if (b.hasRemaining()) {
				b.compact();
			} else {
				b.clear();
			}
		}
		fis.close();
		fos.flush();
		fos.close();
	}

	// pure
	private final String crlf_1 = "\r\nA\r\nBC\r\n\r\nDEF\r\n";
	private final String lf_1 = "\nA\nBC\n\nDEF\n";
	// mixed
	private final String crlf_2 = "\r\nA\r\nBC\n\nDEF\r\n";
	private final String lf_2 = "\nA\nBC\r\n\r\nDEF\n";
	
	@Test
	public void testPure_CRLF_2_LF() {
		final byte[] input = crlf_1.getBytes();
		byte[] result = apply(NewlineFilter.createWin2Nix(false), input);
		Assert.assertArrayEquals(lf_1.getBytes(), result);
	}

	@Test
	public void testPure_LF_2_CRLF() {
		final byte[] input = lf_1.getBytes();
		byte[] result = apply(NewlineFilter.createNix2Win(false), input);
		Assert.assertArrayEquals(crlf_1.getBytes(), result);
	}

	@Test
	public void testRelaxedMixed_CRLF_2_LF() {
		// mixed \n and \r\n to uniform \n
		byte[] result = apply(NewlineFilter.createWin2Nix(true), crlf_2.getBytes());
		Assert.assertArrayEquals(lf_1.getBytes(), result);
	}

	@Test
	public void testRelaxedMixed_LF_2_CRLF() {
		// mixed \n and \r\n to uniform \r\n
		byte[] result = apply(NewlineFilter.createNix2Win(true), lf_2.getBytes());
		Assert.assertArrayEquals(crlf_1.getBytes(), result);
	}

	@Test
	public void testStrictMixed_CRLF_2_LF() {
		try {
			byte[] result = apply(NewlineFilter.createWin2Nix(false), crlf_2.getBytes());
			Assert.fail("Shall fail when eol.only-consistent is true:" + new String(result));
		} catch (RuntimeException ex) {
			// fine
		}
	}

	@Test
	public void testStrictMixed_LF_2_CRLF() {
		try {
			byte[] result = apply(NewlineFilter.createNix2Win(false), lf_2.getBytes());
			Assert.fail("Shall fail when eol.only-consistent is true:" + new String(result));
		} catch (RuntimeException ex) {
			// fine
		}
	}
	
	@Test
	public void testBufferEndInTheMiddle_CRLF_2_LF() {
		// filter works with ByteBuffer that may end with \r, and the next one starting with \n
		// need to make sure this is handled correctly.
		byte[] i1 = "\r\nA\r\nBC\r".getBytes();
		byte[] i2 = "\n\r\nDEF\r\n".getBytes();
		NewlineFilter nlFilter = NewlineFilter.createWin2Nix(false);
		ByteBuffer input = ByteBuffer.allocate(i1.length + i2.length);
		ByteBuffer res = ByteBuffer.allocate(i1.length + i2.length); // at most of the original size
		input.put(i1).flip();
		res.put(nlFilter.filter(input));
		Assert.assertTrue("Unpocessed chars shall be left in input buffer", input.remaining() > 0);
		input.compact();
		input.put(i2);
		input.flip();
		res.put(nlFilter.filter(input));
		Assert.assertTrue("Input shall be consumed completely", input.remaining() == 0);
		//
		res.flip();
		byte[] result = new byte[res.remaining()];
		res.get(result);
		Assert.assertArrayEquals(lf_1.getBytes(), result);
		//
		//
		// check the same, with extra \r at the end of first portion
		nlFilter = NewlineFilter.createWin2Nix(false);
		res.clear();
		input.clear();
		input.put(i1).put("\r\r\r".getBytes()).flip();
		res.put(nlFilter.filter(input));
		Assert.assertTrue("Unpocessed chars shall be left in input buffer", input.remaining() > 0);
		input.compact();
		input.put(i2);
		input.flip();
		res.put(nlFilter.filter(input));
		Assert.assertTrue("Input shall be consumed completely", input.remaining() == 0);
		res.flip();
		result = new byte[res.remaining()];
		res.get(result);
		Assert.assertArrayEquals(lf_1.getBytes(), result);
	}
	
	@Test
	public void testNoConversionLoneCR() {
		// CRLF -> LF
		final byte[] input = "\r\nA\rBC\r\rDE\r\nFGH".getBytes();
		final byte[] output = "\nA\rBC\r\rDE\nFGH".getBytes();
		byte[] result = apply(NewlineFilter.createWin2Nix(false), input);
		Assert.assertArrayEquals(output, result);
		//
		// LF -> CRLF
		result = apply(NewlineFilter.createNix2Win(false), output);
		Assert.assertArrayEquals(input, result);
	}


	@Test
	public void testNoConversionNeeded_LF_2_LF() {
		final byte[] input = lf_1.getBytes();
		Assert.assertTrue("sanity", indexOf(input, '\r') == -1);
		byte[] result = apply(NewlineFilter.createWin2Nix(false), input);
		Assert.assertArrayEquals(input, result);
	}

	@Test
	public void testNoConversionNeeded_CRLF_2_CRLF() {
		final byte[] input = crlf_1.getBytes();
		byte[] result = apply(NewlineFilter.createNix2Win(false), input);
		Assert.assertArrayEquals(input, result);
	}

	private static byte[] apply(NewlineFilter nlFilter, byte[] input) {
		ByteBuffer result = nlFilter.filter(ByteBuffer.wrap(input));
		byte[] res = new byte[result.remaining()];
		result.get(res);
		return res;
	}

	private static int indexOf(byte[] arr, int val) {
		for (int i = 0; i < arr.length; i++) {
			if (arr[i] == val) {
				return i;
			}
		}
		return -1;
	}
}
