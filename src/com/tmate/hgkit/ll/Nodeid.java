/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.util.Arrays;



/**
 * Whether to store fixed size array (20 bytes) - ease of manipulation (e.g. hashcode/equals), or 
 * memory effective - reuse supplied array, keep significant bits only?
 * Fixed size array looks most appealing to me now - I doubt one can save any significant amount of memory. 
 * There'd always 20 non-zero bytes, the difference is only for any extra bytes one may pass to constructor  
 * @author artem
 *
 */
public final class Nodeid {
	
	public static int NULLREV = -1;
	private final byte[] binaryData; 

	/**
	 * @param binaryRepresentation - byte[20], kept by reference. Use {@link #clone()} if original array may get changed. 
	 */
	public Nodeid(byte[] binaryRepresentation) {
		// 5 int fields => 32 bytes
		// byte[20] => 48 bytes
		if (binaryRepresentation == null || binaryRepresentation.length != 20) {
			throw new IllegalArgumentException();
		}
		this.binaryData = binaryRepresentation;
	}

	@Override
	public int hashCode() {
		// TODO consider own impl, especially if byte[] get replaced with 5 ints
		return Arrays.hashCode(binaryData);
	}
	
	@Override
	public boolean equals(Object o) {
		if (o instanceof Nodeid) {
			return Arrays.equals(this.binaryData, ((Nodeid) o).binaryData);
		}
		return false;
	}

	public boolean equalsTo(byte[] buf) {
		return Arrays.equals(this.binaryData, buf);
	}
	
	@Override
	public String toString() {
		return new DigestHelper().toHexString(binaryData, 0, binaryData.length);
	}
	
	// binascii.unhexlify()
	public static Nodeid fromAscii(byte[] asciiRepresentation, int offset, int length) {
		if (length != 40) {
			throw new IllegalArgumentException();
		}
		byte[] data = new byte[20];
		for (int i = 0, j = offset; i < data.length; i++) {
			int hiNibble = Character.digit(asciiRepresentation[j++], 16);
			int lowNibble = Character.digit(asciiRepresentation[j++], 16);
			data[i] = (byte) (((hiNibble << 4) | lowNibble) & 0xFF);
		}
		return new Nodeid(data);
	}
}
