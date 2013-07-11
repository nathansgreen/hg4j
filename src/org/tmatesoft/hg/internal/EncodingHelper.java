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

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInvalidStateException;

/**
 * Keep all encoding-related issues in the single place
 * NOT thread-safe (encoder and decoder requires synchronized access)
 * 
 * @see http://mercurial.selenic.com/wiki/EncodingStrategy
 * @see http://mercurial.selenic.com/wiki/WindowsUTF8Plan
 * @see http://mercurial.selenic.com/wiki/CharacterEncodingOnWindows
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class EncodingHelper {
	/*
	 * To understand what Mercurial thinks of UTF-8 and Unix byte approach to names, see
	 * http://mercurial.808500.n3.nabble.com/Unicode-support-request-td3430704.html
	 */
	
	private final SessionContext sessionContext;
	private final CharsetEncoder encoder;
	private final CharsetDecoder decoder;
	private final CharsetEncoder utfEncoder;
	private final CharsetDecoder utfDecoder;
	
	EncodingHelper(Charset fsEncoding, SessionContext.Source ctx) {
		sessionContext = ctx.getSessionContext();
		decoder = fsEncoding.newDecoder();
		encoder = fsEncoding.newEncoder();
		Charset utf8 = getUTF8();
		if (fsEncoding.equals(utf8)) {
			utfDecoder = decoder;
			utfEncoder = encoder;
		} else {
			utfDecoder = utf8.newDecoder();
			utfEncoder = utf8.newEncoder();
		}
	}

	/**
	 * Translate file names from manifest to amazing Unicode string 
	 */
	public String fromManifest(byte[] data, int start, int length) {
		return decodeWithSystemDefaultFallback(data, start, length);
	}
	
	/**
	 * @return byte representation of the string directly comparable to bytes in manifest
	 */
	public byte[] toManifest(CharSequence s) {
		if (s == null) {
			// perhaps, can return byte[0] in this case?
			throw new IllegalArgumentException();
		}
		return toArray(encodeWithSystemDefaultFallback(s));
	}

	/**
	 * Translate file names from dirstate to amazing Unicode string 
	 */
	public String fromDirstate(byte[] data, int start, int length) {
		return decodeWithSystemDefaultFallback(data, start, length);
	}
	
	public byte[] toDirstate(CharSequence fname) {
		if (fname == null) {
			throw new IllegalArgumentException();
		}
		return toArray(encodeWithSystemDefaultFallback(fname));
	}
	
	/**
	 * prepare filename to be serialized into fncache file
	 */
	public ByteBuffer toFNCache(CharSequence fname) {
		return encodeWithSystemDefaultFallback(fname);
	}
	
	public byte[] toBundle(CharSequence fname) {
		// yes, mercurial transfers filenames in local encoding
		// so that if your local encoding doesn't match that on server, 
		// and you use native characters, you'd likely fail
		return toArray(encodeWithSystemDefaultFallback(fname));
	}
	public String fromBundle(byte[] data, int start, int length) {
		return decodeWithSystemDefaultFallback(data, start, length);
	}
	
	
	public String userFromChangeset(byte[] data, int start, int length) {
		return decodeUnicodeWithFallback(data, start, length);
	}
	
	public String commentFromChangeset(byte[] data, int start, int length) {
		return decodeUnicodeWithFallback(data, start, length);
	}
	
	public String fileFromChangeset(byte[] data, int start, int length) {
		return decodeWithSystemDefaultFallback(data, start, length);
	}

	public byte[] userToChangeset(CharSequence user) {
		return toArray(encodeUnicode(user));
	}
	
	public byte[] commentToChangeset(CharSequence comment) {
		return toArray(encodeUnicode(comment));
	}
	
	public byte[] fileToChangeset(CharSequence file) {
		return toArray(encodeWithSystemDefaultFallback(file));
	}
	
	private String decodeWithSystemDefaultFallback(byte[] data, int start, int length) {
		try {
			return decoder.decode(ByteBuffer.wrap(data, start, length)).toString();
		} catch (CharacterCodingException ex) {
			sessionContext.getLog().dump(getClass(), Error, ex, String.format("Use of charset %s failed, resort to system default", charset().name()));
			// resort to system-default
			return new String(data, start, length);
		}
	}
	
	private ByteBuffer encodeWithSystemDefaultFallback(CharSequence s) {
		try {
			// synchronized(encoder) {
			return encoder.encode(CharBuffer.wrap(s));
			// }
		} catch (CharacterCodingException ex) {
			sessionContext.getLog().dump(getClass(), Error, ex, String.format("Use of charset %s failed, resort to system default", charset().name()));
			// resort to system-default
			return ByteBuffer.wrap(s.toString().getBytes());
		}
	}

	private byte[] toArray(ByteBuffer bb) {
		byte[] rv;
		if (bb.hasArray() && bb.arrayOffset() == 0) {
			rv = bb.array();
			if (rv.length == bb.remaining()) {
				return rv;
			}
			// fall through
		}
		rv = new byte[bb.remaining()];
		bb.get(rv, 0, rv.length);
		return rv;
	}

	private String decodeUnicodeWithFallback(byte[] data, int start, int length) {
		try {
			return utfDecoder.decode(ByteBuffer.wrap(data, start, length)).toString();
		} catch (CharacterCodingException ex) {
			// TODO post-1.2 respect ui.fallbackencoding actual setting
			return new String(data, start, length, Charset.forName("ISO-8859-1"));
		}
	}
	
	private ByteBuffer encodeUnicode(CharSequence s) {
		// 
		try {
			return utfEncoder.encode(CharBuffer.wrap(s));
		} catch (CharacterCodingException ex) {
			byte[] rv;
			try {
				rv = s.toString().getBytes(getUTF8().name()); // XXX Java 1.5
			} catch (UnsupportedEncodingException e) {
				throw new HgInvalidStateException("Unexpected error trying to get UTF-8 encoding"); 
			}
			return ByteBuffer.wrap(rv);
		}
	}

	private Charset charset() {
		return encoder.charset();
	}

	public static Charset getUTF8() {
		return Charset.forName("UTF-8");
	}
}
