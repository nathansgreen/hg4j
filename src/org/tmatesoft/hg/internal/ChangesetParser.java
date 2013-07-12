/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgInvalidDataFormatException;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * @see mercurial/changelog.py:read()
 * 
 *      <pre>
 *         format used:
 *         nodeid\n        : manifest node in ascii
 *         user\n          : user, no \n or \r allowed
 *         time tz extra\n : date (time is int or float, timezone is int)
 *                         : extra is metadatas, encoded and separated by '\0'
 *                         : older versions ignore it
 *         files\n\n       : files modified by the cset, no \n or \r allowed
 *         (.*)            : comment (free text, ideally utf-8)
 * 
 *         changelog v0 doesn't use extra
 * </pre>
 * 
 * Extracted from internals of HgChangelog (the code initially from inside RawChangeset)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class ChangesetParser {
	private final EncodingHelper encHelper;
	// it's likely user names get repeated again and again throughout repository. 
	private final Pool<String> usersPool;
	private final Pool<String> filesPool;
	private final CsetFactory factory;
	
	public ChangesetParser(SessionContext.Source sessionContex, CsetFactory csetFactory) {
		assert csetFactory != null;
		encHelper = Internals.buildFileNameEncodingHelper(sessionContex);
		usersPool = new Pool<String>();
		filesPool = new Pool<String>();
		factory = csetFactory;
	}
	
	public void dispose() {
		usersPool.clear();
		filesPool.clear();
	}

	public RawChangeset parse(DataAccess da) throws IOException, HgInvalidDataFormatException {
		byte[] data = da.byteArray();
		return parse(data);
	}
	
	public RawChangeset parse(byte[] data) throws HgInvalidDataFormatException {
		return init(data, 0, data.length);
	}

	private RawChangeset init(byte[] data, int offset, int length) throws HgInvalidDataFormatException {
		final int bufferEndIndex = offset + length;
		final byte lineBreak = (byte) '\n';
		int breakIndex1 = indexOf(data, lineBreak, offset, bufferEndIndex);
		if (breakIndex1 == -1) {
			throw new HgInvalidDataFormatException("Bad Changeset data");
		}
		Nodeid _nodeid = Nodeid.fromAscii(data, 0, breakIndex1);
		int breakIndex2 = indexOf(data, lineBreak, breakIndex1 + 1, bufferEndIndex);
		if (breakIndex2 == -1) {
			throw new HgInvalidDataFormatException("Bad Changeset data");
		}
		String _user;
		_user = encHelper.userFromChangeset(data, breakIndex1 + 1, breakIndex2 - breakIndex1 - 1);
		_user = usersPool.unify(_user);

		int breakIndex3 = indexOf(data, lineBreak, breakIndex2 + 1, bufferEndIndex);
		if (breakIndex3 == -1) {
			throw new HgInvalidDataFormatException("Bad Changeset data");
		}
		String _timeString = new String(data, breakIndex2 + 1, breakIndex3 - breakIndex2 - 1);
		int space1 = _timeString.indexOf(' ');
		if (space1 == -1) {
			throw new HgInvalidDataFormatException(String.format("Bad Changeset data: %s in [%d..%d]", "time string", breakIndex2+1, breakIndex3));
		}
		int space2 = _timeString.indexOf(' ', space1 + 1);
		if (space2 == -1) {
			space2 = _timeString.length();
		}
		long unixTime = Long.parseLong(_timeString.substring(0, space1));
		int _timezone = Integer.parseInt(_timeString.substring(space1 + 1, space2));
		// unixTime is local time, and timezone records difference of the local time to UTC.
		Date _time = new Date(unixTime * 1000);
		String _extras = space2 < _timeString.length() ? _timeString.substring(space2 + 1) : null;
		Map<String, String> _extrasMap = parseExtras(_extras);
		//
		int lastStart = breakIndex3 + 1;
		int breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
		ArrayList<String> _files = null;
		if (breakIndex4 > lastStart) {
			// if breakIndex4 == lastStart, we already found \n\n and hence there are no files (e.g. merge revision)
			_files = new ArrayList<String>(5);
			while (breakIndex4 != -1 && breakIndex4 + 1 < bufferEndIndex) {
				String fname = encHelper.fileFromChangeset(data, lastStart, breakIndex4 - lastStart);
				_files.add(filesPool.unify(fname));
				lastStart = breakIndex4 + 1;
				if (data[breakIndex4 + 1] == lineBreak) {
					// found \n\n
					break;
				} else {
					breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
				}
			}
			if (breakIndex4 == -1 || breakIndex4 >= bufferEndIndex) {
				throw new HgInvalidDataFormatException("Bad Changeset data");
			}
		} else {
			breakIndex4--;
		}
		String _comment = encHelper.commentFromChangeset(data, breakIndex4 + 2, bufferEndIndex - breakIndex4 - 2);
		RawChangeset target = factory.create(_nodeid, _user, _time, _timezone, _files, _comment, _extrasMap);
		return target; 
	}

	private Map<String, String> parseExtras(String _extras) {
		final String extras_branch_key = "branch";
		_extras = _extras == null ? null : _extras.trim();
		if (_extras == null || _extras.length() == 0) {
			return Collections.singletonMap(extras_branch_key, HgRepository.DEFAULT_BRANCH_NAME);
		}
		Map<String, String> _extrasMap = new HashMap<String, String>();
		int lastIndex = 0;
		do {
			String pair;
			int sp = _extras.indexOf('\0', lastIndex);
			if (sp == -1) {
				sp = _extras.length();
			}
			if (sp > lastIndex) {
				pair = _extras.substring(lastIndex, sp);
				pair = decode(pair);
				int eq = pair.indexOf(':');
				_extrasMap.put(pair.substring(0, eq), pair.substring(eq + 1));
				lastIndex = sp + 1;
			}
		} while (lastIndex < _extras.length());
		if (!_extrasMap.containsKey(extras_branch_key)) {
			_extrasMap.put(extras_branch_key, HgRepository.DEFAULT_BRANCH_NAME);
		}
		return Collections.unmodifiableMap(_extrasMap);
	}

	private static int indexOf(byte[] src, byte what, int startOffset, int endIndex) {
		for (int i = startOffset; i < endIndex; i++) {
			if (src[i] == what) {
				return i;
			}
		}
		return -1;
	}
	
	private static String decode(String s) {
		if (s != null && s.indexOf('\\') != -1) {
			// TestAuxUtilities#testChangelogExtrasDecode
			return s.replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\0", "\00");
		}
		return s;
	}

	public interface CsetFactory {
		public RawChangeset create(Nodeid nodeid, String user, Date time, int timezone, List<String> files, String comment, Map<String, String> extrasMap);
	}
}