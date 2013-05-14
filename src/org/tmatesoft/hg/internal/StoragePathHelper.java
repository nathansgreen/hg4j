/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Arrays;
import java.util.TreeSet;

import org.tmatesoft.hg.util.PathRewrite;

/**
 * @see http://mercurial.selenic.com/wiki/CaseFoldingPlan
 * @see http://mercurial.selenic.com/wiki/fncacheRepoFormat
 * @see http://mercurial.selenic.com/wiki/EncodingStrategy
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class StoragePathHelper implements PathRewrite {

	static final String STR_STORE = "store/";
	static final String STR_DATA = "data/";
	static final String STR_DH = "dh/";

	private final boolean store;
	private final boolean fncache;
	private final boolean dotencode;
	private final EncodeDirPathHelper dirPathRewrite;
	private final CharsetEncoder csEncoder;
	private final char[] hexEncodedByte = new char[] {'~', '0', '0'};
	private final ByteBuffer byteEncodingBuf;
	private final CharBuffer charEncodingBuf;
	
	public StoragePathHelper(boolean isStore, boolean isFncache, boolean isDotencode) {
		this(isStore, isFncache, isDotencode, Charset.defaultCharset());
	}

	public StoragePathHelper(boolean isStore, boolean isFncache, boolean isDotencode, Charset fsEncoding) {
		assert fsEncoding != null;
		store = isStore;
		fncache = isFncache;
		dotencode = isDotencode;
		dirPathRewrite = new EncodeDirPathHelper();
		csEncoder = fsEncoding.newEncoder();
		byteEncodingBuf = ByteBuffer.allocate(Math.round(csEncoder.maxBytesPerChar()) + 1/*in fact, need ceil, hence +1*/);
		charEncodingBuf = CharBuffer.allocate(1);
	}

	/**
	 * path argument is repository-relative name of the user's file.
	 * It has to be normalized (slashes) and shall not include extension .i or .d.
	 */
	public CharSequence rewrite(CharSequence p) {
		final String reservedChars = "\\:*?\"<>|";
		
		CharSequence path = dirPathRewrite.rewrite(p);
		
		StringBuilder sb = new StringBuilder(path.length() << 1);
		if (store || fncache) {
			for (int i = 0; i < path.length(); i++) {
				final char ch = path.charAt(i);
				if (ch >= 'a' && ch <= 'z') {
					sb.append(ch); // POIRAE
				} else if (ch >= 'A' && ch <= 'Z') {
					sb.append('_');
					sb.append(Character.toLowerCase(ch)); // Perhaps, (char) (((int) ch) + 32)? Even better, |= 0x20? 
				} else if (reservedChars.indexOf(ch) != -1) {
					sb.append(toHexByte(ch));
				} else if ((ch >= '~' /*126*/ && ch <= 255) || ch < ' ' /*32*/) {
					sb.append(toHexByte(ch));
				} else if (ch == '_') {
					sb.append('_');
					sb.append('_');
				} else {
					// either ASCII char that doesn't require special handling, or an Unicode character to get encoded
					// according to filesystem/native encoding, see http://mercurial.selenic.com/wiki/EncodingStrategy
					// despite of what the page says, use of native encoding seems worst solution to me (repositories
					// can't be easily shared between OS'es with different encodings then, e.g. Win1251 and Linux UTF8).
					// If the ease of sharing was not the point, what's the reason to mangle with names at all then (
					// lowercase and exclude reserved device names).
					if (ch < '~' /*126*/ || !csEncoder.canEncode(ch)) {
						sb.append(ch);
					} else {
						appendEncoded(sb, ch);
					}
				}
			}
			// auxencode
			if (fncache) {
				encodeWindowsDeviceNames(sb);
			}
		}
		final int MAX_PATH_LEN = 120;
		if (fncache && (sb.length() + STR_DATA.length() + ".i".length() > MAX_PATH_LEN)) {
			// TODO [post-1.0] Mercurial uses system encoding for paths, hence we need to pass bytes to DigestHelper
			// to ensure our sha1 value (default encoding of unicode string if one looks into DH impl) match that 
			// produced by Mercurial (based on native string). 
			String digest = new DigestHelper().sha1(STR_DATA, path, ".i").asHexString();
			final int DIR_PREFIX_LEN = 8;
			 // not sure why (-4) is here. 120 - 40 = up to 80 for path with ext. dh/ + ext(.i) = 3+2
			final int MAX_DIR_PREFIX = 8 * (DIR_PREFIX_LEN + 1) - 4;
			sb = new StringBuilder(MAX_PATH_LEN);
			for (int i = 0; i < path.length(); i++) {
				final char ch = path.charAt(i);
				if (ch >= 'a' && ch <= 'z') {
					sb.append(ch);
				} else if (ch >= 'A' && ch <= 'Z') {
					sb.append((char) (ch | 0x20)); // lowercase 
				} else if (reservedChars.indexOf(ch) != -1) {
					sb.append(toHexByte(ch));
				} else if ((ch >= '~' /*126*/ && ch <= 255) || ch < ' ' /*32*/) {
					sb.append(toHexByte(ch));
				} else {
					if (ch < '~' /*126*/ || !csEncoder.canEncode(ch)) {
						sb.append(ch);
					} else {
						appendEncoded(sb, ch);
					}
				}
			}
			encodeWindowsDeviceNames(sb);
			int fnameStart = sb.lastIndexOf("/"); // since we rewrite file names, it never ends with slash (for dirs, I'd pass length-2);
			StringBuilder completeHashName = new StringBuilder(MAX_PATH_LEN);
			completeHashName.append(STR_STORE);
			completeHashName.append(STR_DH);
			if (fnameStart == -1) {
				// no dirs, just long filename
				sb.setLength(MAX_PATH_LEN - 40 /*digest.length()*/ - STR_DH.length() - ".i".length());
				completeHashName.append(sb);
			} else {
				StringBuilder sb2 = new StringBuilder(MAX_PATH_LEN);
				int x = 0;
				do {
					int i = sb.indexOf("/", x);
					final int sb2Len = sb2.length(); 
					if (i-x <= DIR_PREFIX_LEN) { // a b c d e f g h /
						sb2.append(sb, x, i + 1); // with slash
					} else {
						sb2.append(sb, x, x + DIR_PREFIX_LEN);
						// may unexpectedly end with bad character
						final int last = sb2.length()-1;
						char lastChar = sb2.charAt(last); 
						assert lastChar == sb.charAt(x + DIR_PREFIX_LEN - 1);
						if (lastChar == '.' || lastChar == ' ') {
							sb2.setCharAt(last, '_');
						}
						sb2.append('/');
					}
					if (sb2.length()-1 > MAX_DIR_PREFIX) {
						sb2.setLength(sb2Len); // strip off last segment, it's too much
						break;
					}
					x = i+1; 
				} while (x < fnameStart);
				assert sb2.charAt(sb2.length() - 1) == '/';
				int left = MAX_PATH_LEN - sb2.length() - 40 /*digest.length()*/ - STR_DH.length() - ".i".length();
				assert left >= 0;
				fnameStart++; // move from / to actual name
				if (fnameStart + left > sb.length()) {
					// there left less chars in the mangled name that we can fit
					sb2.append(sb, fnameStart, sb.length());
					int stillAvailable = (fnameStart+left) - sb.length();
					// stillAvailable > 0;
					sb2.append(".i", 0, stillAvailable > 2 ? 2 : stillAvailable);
				} else {
					// add as much as we can
					sb2.append(sb, fnameStart, fnameStart+left);
				}
				completeHashName.append(sb2);
			}
			completeHashName.append(digest);
			sb = completeHashName;
		} else if (store) {
			sb.insert(0, STR_STORE + STR_DATA);
		}
		sb.append(".i");
		return sb.toString();
	}
	
	private void encodeWindowsDeviceNames(StringBuilder sb) {
		int x = 0; // last segment start
		final TreeSet<String> windowsReservedFilenames = new TreeSet<String>();
		windowsReservedFilenames.addAll(Arrays.asList("con prn aux nul com1 com2 com3 com4 com5 com6 com7 com8 com9 lpt1 lpt2 lpt3 lpt4 lpt5 lpt6 lpt7 lpt8 lpt9".split(" "))); 
		do {
			int i = sb.indexOf("/", x);
			if (i == -1) {
				i = sb.length();
			}
			// windows reserved filenames are at least of length 3 
			if (i - x >= 3) {
				boolean found = false;
				if (i-x == 3 || i-x == 4) {
					found = windowsReservedFilenames.contains(sb.subSequence(x, i));
				} else if (sb.charAt(x+3) == '.') { // implicit i-x > 3
					found = windowsReservedFilenames.contains(sb.subSequence(x, x+3));
				} else if (i-x > 4 && sb.charAt(x+4) == '.') {
					found = windowsReservedFilenames.contains(sb.subSequence(x, x+4));
				}
				if (found) {
					// x+2 as we change the third letter in device name
					replace(sb, x+2, toHexByte(sb.charAt(x+2)));
					i += 2;
				}
			}
			if (dotencode && (sb.charAt(x) == '.' || sb.charAt(x) == ' ')) {
				char dotOrSpace = sb.charAt(x); // beware, replace() below changes charAt(x), rather get a copy 
				// not to get ~7e for '.' instead of ~2e, if later refactoring changes the logic 
				replace(sb, x, toHexByte(dotOrSpace));
				i += 2;
			}
			x = i+1;
		} while (x < sb.length());
	}
	
	// shall be synchronized in case of multithreaded use
	private void appendEncoded(StringBuilder sb, char ch) {
		charEncodingBuf.clear();
		byteEncodingBuf.clear();
		charEncodingBuf.put(ch).flip();
		csEncoder.encode(charEncodingBuf, byteEncodingBuf, false);
		byteEncodingBuf.flip();
		while (byteEncodingBuf.hasRemaining()) {
			sb.append(toHexByte(byteEncodingBuf.get()));
		}
	}

	/**
	 * replace char at sb[index] with a sequence
	 */
	private static void replace(StringBuilder sb, int index, char[] with) {
		// there's StringBuilder.replace(int, int+1, String), but with char[] - I don't want to make a string out of hexEncodedByte
		sb.setCharAt(index, with[0]);
		sb.insert(index+1, with, 1, with.length - 1);
	}

	/**
	 * put hex representation of byte ch into buf from specified offset 
	 */
	private char[] toHexByte(int ch) {
		final String hexDigits = "0123456789abcdef";
		hexEncodedByte[1] = hexDigits.charAt((ch & 0x00F0) >>> 4);
		hexEncodedByte[2] = hexDigits.charAt(ch & 0x0F);
		return hexEncodedByte;
	}
}
