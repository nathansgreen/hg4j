/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;



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
		return equals(this.binaryData, o.binaryData) ? 0 : -1;
	}

	public boolean equalsTo(byte[] buf) {
		return equals(this.binaryData, buf);
	}
	
	private static boolean equals(byte[] a1, byte[] a2) {
		if (a1 == null || a1.length < 20 || a2 == null || a2.length < 20) {
			throw new IllegalArgumentException();
		}
		// assume significant bits are at the end of the array
		final int s1 = a1.length - 20, s2 = a2.length - 20;
		for (int i = 0; i < 20; i++) {
			if (a1[s1+i] != a2[s2+i]) {
				return false;
			}
		}
		return true;
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
