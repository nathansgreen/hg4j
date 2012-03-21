/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;

/**
 * Keep all encoding-related issues in the single place
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class EncodingHelper {
	// XXX perhaps, shall not be full of statics, but rather an instance coming from e.g. HgRepository?
	/*
	 * To understand what Mercurial thinks of UTF-8 and Unix byte approach to names, see
	 * http://mercurial.808500.n3.nabble.com/Unicode-support-request-td3430704.html
	 */
	
	private final CharsetEncoder encoder;
	private final CharsetDecoder decoder;
	
	EncodingHelper(Charset fsEncoding) {
		decoder = fsEncoding.newDecoder();
		encoder = fsEncoding.newEncoder();
	}

	public String fromManifest(byte[] data, int start, int length) {
		try {
			return decoder.decode(ByteBuffer.wrap(data, start, length)).toString();
		} catch (CharacterCodingException ex) {
			// resort to system-default
			return new String(data, start, length);
		}
	}

	public String fromDirstate(byte[] data, int start, int length) throws CharacterCodingException {
		return decoder.decode(ByteBuffer.wrap(data, start, length)).toString();
	}

	public Charset charset() {
		return encoder.charset();
	}
}
