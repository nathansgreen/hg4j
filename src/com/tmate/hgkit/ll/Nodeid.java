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
public final class Nodeid implements Comparable<Nodeid> {
	
	public static int NULLREV = -1;
	private final byte[] binaryData; 

	public Nodeid(byte[] binaryRepresentation) {
		// 5 int fields => 32 bytes
		// byte[20] => 48 bytes
		this.binaryData = binaryRepresentation;
	}

	// instead of hashCode/equals
	public int compareTo(Nodeid o) {
		byte[] a1, a2;
		if (this.binaryData.length != 20) {
			a1 = new byte[20];
			System.arraycopy(binaryData, 0, a1, 20 - binaryData.length, binaryData.length);
		} else {
			a1 = this.binaryData;
		}
		
		if (o.binaryData.length != 20) {
			a2 = new byte[20];
			System.arraycopy(o.binaryData, 0, a2, 20 - o.binaryData.length, o.binaryData.length);
		} else {
			a2 = o.binaryData;
		}
		return Arrays.equals(a1, a2) ? 0 : -1;
	}

	@Override
	public String toString() {
		return new DigestHelper().toHexString(binaryData, 0, binaryData.length);
	}
	
	// binascii.unhexlify()
	public static Nodeid fromAscii(byte[] asciiRepresentation, int offset, int length) {
		assert length % 2 == 0; // Python's binascii.hexlify convert each byte into 2 digits
		byte[] data = new byte[length >>> 1]; // XXX use known size instead? nodeid is always 20 bytes
		for (int i = 0, j = offset; i < data.length; i++) {
			int hiNibble = Character.digit(asciiRepresentation[j++], 16);
			int lowNibble = Character.digit(asciiRepresentation[j++], 16);
			data[i] = (byte) (((hiNibble << 4) | lowNibble) & 0xFF);
		}
		return new Nodeid(data);
	}
}
