/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * TODO sha1_binary to give array for Nodeid.equalsTo 
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


	public String sha1(Nodeid nodeid1, Nodeid nodeid2, byte[] data) {
		return sha1(nodeid1.cloneData(), nodeid2.cloneData(), data);
	}

	//  sha1_digest(min(p1,p2) ++ max(p1,p2) ++ final_text)
	public String sha1(byte[] nodeidParent1, byte[] nodeidParent2, byte[] data) {
		MessageDigest alg = getSHA1();
		if ((nodeidParent1[0] & 0x00FF) < (nodeidParent2[0] & 0x00FF)) { 
			alg.update(nodeidParent1);
			alg.update(nodeidParent2);
		} else {
			alg.update(nodeidParent2);
			alg.update(nodeidParent1);
		}
		byte[] digest = alg.digest(data);
		assert digest.length == 20;
		return toHexString(digest, 0, 20);
	}

	// XXX perhaps, digest functions should throw an exception, as it's caller responsibility to deal with eof, etc
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

	public static String toHexString(byte[] data, final int offset, final int count) {
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
