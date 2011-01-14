/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import static com.tmate.hgkit.ll.DigestHelper.toHexString;

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
	
	public static final Nodeid NULL = new Nodeid(new byte[20], false);
	private final byte[] binaryData; 

	/**
	 * @param binaryRepresentation - byte[20], kept by reference
	 * @param shallClone - true if array is subject to future modification and shall be copied, not referenced 
	 */
	public Nodeid(byte[] binaryRepresentation, boolean shallClone) {
		// 5 int fields => 32 bytes
		// byte[20] => 48 bytes
		if (binaryRepresentation == null || binaryRepresentation.length != 20) {
			throw new IllegalArgumentException();
		}
		this.binaryData = shallClone ? binaryRepresentation.clone() : binaryRepresentation;
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
		// XXX may want to output just single 0 for the NULL id?
		return toHexString(binaryData, 0, binaryData.length);
	}

	public String shortNotation() {
		return toHexString(binaryData, 0, 6);
	}
	
	public boolean isNull() {
		if (this == NULL) {
			return true;
		}
		for (int i = 0; i < 20; i++) {
			if (this.binaryData[i] != 0) {
				return false;
			}
		}
		return true;
	}

	// primary purpose is to give DigestHelper access to internal structure. Despite it's friends-only (package visibility), it's still makes sense to 
	// return a copy, to avoid any accidental modification (same reason field is not made visible, nor any callback, e.g. Output.write(byte[]) was introduced)
	/*package-local*/byte[] cloneData() {
		return binaryData.clone();
	}

	// primary difference with cons is handling of NULL id (this method returns constant)
	// always makes a copy of an array passed
	public static Nodeid fromBinary(byte[] binaryRepresentation, int offset) {
		if (binaryRepresentation == null || binaryRepresentation.length - offset < 20) {
			throw new IllegalArgumentException();
		}
		int i = 0;
		while (i < 20 && binaryRepresentation[offset+i] == 0) i++;
		if (i == 20) {
			return NULL;
		}
		if (offset == 0 && binaryRepresentation.length == 20) {
			return new Nodeid(binaryRepresentation, true);
		}
		byte[] b = new byte[20]; // create new instance if no other reasonable guesses possible
		System.arraycopy(binaryRepresentation, offset, b, 0, 20);
		return new Nodeid(b, false);
	}

	// binascii.unhexlify()
	public static Nodeid fromAscii(byte[] asciiRepresentation, int offset, int length) {
		if (length != 40) {
			throw new IllegalArgumentException();
		}
		byte[] data = new byte[20];
		boolean zeroBytes = true;
		for (int i = 0, j = offset; i < data.length; i++) {
			int hiNibble = Character.digit(asciiRepresentation[j++], 16);
			int lowNibble = Character.digit(asciiRepresentation[j++], 16);
			byte b = (byte) (((hiNibble << 4) | lowNibble) & 0xFF);
			data[i] = b;
			zeroBytes = zeroBytes && b == 0;
		}
		if (zeroBytes) {
			return NULL;
		}
		return new Nodeid(data, false);
	}
}
