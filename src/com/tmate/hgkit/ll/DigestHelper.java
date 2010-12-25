/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 *
 * @author artem
 */
public class DigestHelper {
	private MessageDigest sha1;

	public DigestHelper() {
	}
	
	private MessageDigest getSHA1() {
		if (sha1 == null) {
			try {
				sha1 = MessageDigest.getInstance("SHA-1");
			} catch (NoSuchAlgorithmException ex) {
				// could hardly happen, JDK from Sun always has sha1.
				ex.printStackTrace(); // FIXME log error
			}
		}
		return sha1;
	}

	// XXX perhaps, digest functions should throw an exception, as it's caller responsibility to deal with eof, etc
	public String sha1(byte[] data) {
		MessageDigest alg = getSHA1();
		byte[] digest = alg.digest(data);
		assert digest.length == 20;
		return toHexString(digest, 0, 20);
	}

	public byte[] sha1(InputStream is /*ByteBuffer*/) throws IOException {
		MessageDigest alg = getSHA1();
		byte[] buf = new byte[1024];
		int c;
		while ((c = is.read(buf)) != -1) {
			alg.update(buf, 0, c);
		}
		byte[] digest = alg.digest();
		return digest;
	}

	public String toHexString(byte[] data, final int offset, final int count) {
		char[] result = new char[count << 1];
		final String hexDigits = "0123456789abcdef";
		final int end = offset+count;
		for (int i = offset, j = 0; i < end; i++) {
			result[j++] = hexDigits.charAt((data[i] >>> 4) & 0x0F);
			result[j++] = hexDigits.charAt(data[i] & 0x0F);
		}
		return new String(result);
	}
}
