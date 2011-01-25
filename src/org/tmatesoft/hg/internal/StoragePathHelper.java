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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.internal;

import java.util.Arrays;
import java.util.TreeSet;

import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * @see http://mercurial.selenic.com/wiki/CaseFoldingPlan
 * @see http://mercurial.selenic.com/wiki/fncacheRepoFormat
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class StoragePathHelper implements PathRewrite {
	
	private final boolean store;
	private final boolean fncache;
	private final boolean dotencode;

	public StoragePathHelper(boolean isStore, boolean isFncache, boolean isDotencode) {
		store = isStore;
		fncache = isFncache;
		dotencode = isDotencode;
	}

	// FIXME document what path argument is, whether it includes .i or .d, and whether it's 'normalized' (slashes) or not.
	// since .hg/store keeps both .i files and files without extension (e.g. fncache), guees, for data == false 
	// we shall assume path has extension
	// FIXME much more to be done, see store.py:_hybridencode
	public String rewrite(String path) {
		final String STR_STORE = "store/";
		final String STR_DATA = "data/";
		final String STR_DH = "dh/";
		
		path = path.replace(".hg/", ".hg.hg/").replace(".i/", ".i.hg/").replace(".d/", ".d.hg/");
		StringBuilder sb = new StringBuilder(path.length() << 1);
		if (store || fncache) {
			// encodefilename
			final String reservedChars = "\\:*?\"<>|";
			// in fact, \\ is unlikely to match, ever - we've replaced all of them already, above. Just regards to store.py 
			int x;
			char[] hexByte = new char[2];
			for (int i = 0; i < path.length(); i++) {
				final char ch = path.charAt(i);
				if (ch >= 'a' && ch <= 'z') {
					sb.append(ch); // POIRAE
				} else if (ch >= 'A' && ch <= 'Z') {
					sb.append('_');
					sb.append(Character.toLowerCase(ch)); // Perhaps, (char) (((int) ch) + 32)? Even better, |= 0x20? 
				} else if ( (x = reservedChars.indexOf(ch)) != -1) {
					sb.append('~');
					sb.append(toHexByte(reservedChars.charAt(x), hexByte));
				} else if ((ch >= '~' /*126*/ && ch <= 255) || ch < ' ' /*32*/) {
					sb.append('~');
					sb.append(toHexByte(ch, hexByte));
				} else if (ch == '_') {
					// note, encoding from store.py:_buildencodefun and :_build_lower_encodefun
					// differ in the way they process '_' (latter doesn't escape it)
					sb.append('_');
					sb.append('_');
				} else {
					sb.append(ch);
				}
			}
			// auxencode
			if (fncache) {
				x = 0; // last segment start
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
						if (i-x == 3) {
							found = windowsReservedFilenames.contains(sb.subSequence(x, i));
						} else if (sb.charAt(x+3) == '.') { // implicit i-x > 3
							found = windowsReservedFilenames.contains(sb.subSequence(x, x+3));
						} else if (i-x > 4 && sb.charAt(x+4) == '.') {
							found = windowsReservedFilenames.contains(sb.subSequence(x, x+4));
						}
						if (found) {
							sb.setCharAt(x, '~');
							sb.insert(x+1, toHexByte(sb.charAt(x+2), hexByte));
							i += 2;
						}
					}
					if (dotencode && (sb.charAt(x) == '.' || sb.charAt(x) == ' ')) {
						sb.insert(x+1, toHexByte(sb.charAt(x), hexByte));
						sb.setCharAt(x, '~'); // setChar *after* charAt/insert to get ~2e, not ~7e for '.'
						i += 2;
					}
					x = i+1;
				} while (x < sb.length());
			}
		}
		final int MAX_PATH_LEN_IN_HGSTORE = 120;
		if (fncache && (sb.length() + STR_DATA.length() > MAX_PATH_LEN_IN_HGSTORE)) {
			throw HgRepository.notImplemented(); // FIXME digest and fncache use
		}
		if (store) {
			sb.insert(0, STR_STORE + STR_DATA);
		}
		sb.append(".i");
		return sb.toString();
	}

	private static char[] toHexByte(int ch, char[] buf) {
		assert buf.length > 1;
		final String hexDigits = "0123456789abcdef";
		buf[0] = hexDigits.charAt((ch & 0x00F0) >>> 4);
		buf[1] = hexDigits.charAt(ch & 0x0F);
		return buf;
	}
}
