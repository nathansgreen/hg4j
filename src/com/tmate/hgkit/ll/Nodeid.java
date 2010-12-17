/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.math.BigInteger;
import java.util.Formatter;

/**
 * @see mercurial/node.py
 * @author artem
 *
 */
public class Nodeid {
	
	public static int NULLREV = -1;
	private final byte[] binaryData; 

	public Nodeid(byte[] binaryRepresentation) {
		// 5 int fields => 32 bytes
		// byte[20] => 48 bytes
		this.binaryData = binaryRepresentation;
	}

	@Override
	public String toString() {
		// FIXME temp impl.
		// BEWARE, if binaryData[0] > 0x80, BigInteger treats it as negative  
		return new BigInteger(binaryData).toString();
	}

	// binascii.unhexlify()
	public static Nodeid fromAscii(byte[] asciiRepresentation, int offset, int length) {
		assert length % 2 == 0; // Python's binascii.hexlify convert each byte into 2 digits
		byte[] data = new byte[length / 2]; // XXX use known size instead? nodeid is always 20 bytes
		for (int i = 0, j = offset; i < data.length; i++) {
			int hiNibble = Character.digit(asciiRepresentation[j++], 16);
			int lowNibble = Character.digit(asciiRepresentation[j++], 16);
			data[i] = (byte) (((hiNibble << 4) | lowNibble) & 0xFF);
		}
		return new Nodeid(data);
	}
	
	
}
