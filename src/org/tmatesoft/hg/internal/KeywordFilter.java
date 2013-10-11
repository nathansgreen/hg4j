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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class KeywordFilter implements Filter {
	private final HgRepository repo;
	private final boolean isExpanding;
	private final Map<String,String> keywords;
	private final Path path;
	private RawChangeset latestFileCset;
	private final ByteVector unprocessedBuffer;

	/**
	 * 
	 * @param hgRepo 
	 * @param path 
	 * @param expand <code>true</code> to expand keywords, <code>false</code> to shrink
	 */
	private KeywordFilter(HgRepository hgRepo, Path p, Map<String, String> kw, boolean expand) {
		repo = hgRepo;
		path = p;
		isExpanding = expand;
		keywords = kw;
		unprocessedBuffer = expand ? new ByteVector(0, 0) :  new ByteVector(120, 50);
	}

	/**
	 * @param src buffer ready to be read
	 * @return buffer ready to be read and original buffer's position modified to reflect consumed bytes. IOW, if source buffer
	 * on return has remaining bytes, they are assumed not-read (not processed) and next chunk passed to filter is supposed to 
	 * start with them  
	 */
	public ByteBuffer filter(ByteBuffer src) {
		// when unprocessedBuffer is empty, we are looking for first $ in the input,
		// when we've already got anything unprocessed, newline is of interest, too
		int kwBreak = indexOf(src, '$', src.position(), !unprocessedBuffer.isEmpty());
		ByteBuffer outBuffer = null;
		while (kwBreak != -1) {
			if (unprocessedBuffer.isEmpty()) {
				// both expand and collapse cases
				assert src.get(kwBreak) == '$';
				
				int end = indexOf(src, '$', kwBreak+1, true);
				if (end == -1) {
					for (int i = kwBreak; i < src.limit(); i++) {
						unprocessedBuffer.add(src.get(i));
					}
					src.limit(kwBreak);
					kwBreak = -1;
					// src up to kwBreak is left and returned either with outBuffer or alone 
				} else if (src.get(end) == '$') {
					StringBuilder sb = new StringBuilder(end - kwBreak);
					for (int i = kwBreak+1; i < end; i++) {
						if (src.get(i) == ':' || src.get(i) == ' ') {
							break;
						}
						sb.append((char) src.get(i));
					}
					final String keyword = sb.toString();
					if (knownKeyword(keyword)) {
						// copy src up to kw, including starting $keyword
						outBuffer = append(outBuffer, src, kwBreak - src.position() + 1+keyword.length());
						// replace kwStart..end with new content
						outBuffer = ensureCapacityFor(outBuffer, (isExpanding ? 200 : 1));
						if (isExpanding) {
							outBuffer.put((byte) ':');
							outBuffer.put((byte) ' ');
							outBuffer = expandKeywordValue(keyword, outBuffer);
							outBuffer.put((byte) ' ');
						}
						outBuffer.put((byte) '$');
						// src is consumed up to end
						src.position(end+1);
						kwBreak = indexOf(src, '$', end+1, false);
					} else {
						// no (or unknown) keyword, try with '$' at src[end]
						kwBreak = end;
					}
				} else {
					// newline, ignore keyword start
					kwBreak = indexOf(src, '$', end+1, false);
				}
			} else {
				// we've got smth unprocessed, and we've matched either $ or NL
				// the only chance to get here is when src is in the very start
				if (src.get(kwBreak) == '$') {
					// closed tag
					for (int i = src.position(); i <= kwBreak; i++) {
						// consume src: going to handle its [position*()..kwBreak] as part of unprocessedBuffer
						unprocessedBuffer.add(src.get());   
					}
					StringBuilder sb = new StringBuilder(unprocessedBuffer.size());
					assert unprocessedBuffer.get(0) == '$';
					for (int i = 1; i < unprocessedBuffer.size(); i++) {
						char ch = (char) unprocessedBuffer.get(i);
						if (ch == ':' || ch == ' ') {
							break;
						}
						sb.append(ch);
					}
					final String keyword = sb.toString();
					if (knownKeyword(keyword)) {
						outBuffer = ensureCapacityFor(outBuffer, keyword.length() + (isExpanding ? 200 : 2));
						outBuffer.put((byte) '$');
						outBuffer.put(keyword.getBytes());
						if (isExpanding) {
							outBuffer.put((byte) ':');
							outBuffer.put((byte) ' ');
							outBuffer = expandKeywordValue(keyword, outBuffer);
							outBuffer.put((byte) ' ');
						}
						outBuffer.put((byte) '$');
					} else {
						outBuffer = append(outBuffer, unprocessedBuffer.toByteArray());
					}
					// src part is consumed already, do nothing here, look for next possible kw
					kwBreak = indexOf(src, '$', kwBreak+1, false);
				} else {
					// newline => tag without close
					outBuffer = append(outBuffer, unprocessedBuffer.toByteArray());
					kwBreak = indexOf(src, '$', kwBreak+1, false);
				}
				unprocessedBuffer.clear();
			}
		} while (kwBreak != -1);
		if (outBuffer == null) {
			return src;
		}
		outBuffer = ensureCapacityFor(outBuffer, src.remaining());
		outBuffer.put(src);
		outBuffer.flip();
		return outBuffer;
	}
	private boolean knownKeyword(String kw) {
		return keywords.containsKey(kw);
	}

	private static ByteBuffer append(ByteBuffer out, byte[] data) {
		out = ensureCapacityFor(out, data.length);
		out.put(data);
		return out;
	}
	private static ByteBuffer append(ByteBuffer out, ByteBuffer in, int count) {
		out = ensureCapacityFor(out, count);
		while (count-- > 0) {
			out.put(in.get());
		}
		return out;
	}
	private static ByteBuffer ensureCapacityFor(ByteBuffer out, int exansion) {
		if (out == null || out.remaining() < exansion) {
			ByteBuffer newOut = ByteBuffer.allocate(out == null ? exansion*2 : out.capacity() + exansion);
			if (out != null) {
				out.flip();
				newOut.put(out);
			}
			return newOut;
		}
		return out;
	}
	
	private ByteBuffer expandKeywordValue(String keyword, ByteBuffer rv) {
		byte[] toInject;
		if ("Id".equals(keyword)) {
			toInject = identityString().getBytes();
		} else if ("Revision".equals(keyword)) {
			toInject = revision().getBytes();
		} else if ("Author".equals(keyword)) {
			toInject = username().getBytes();
		} else if ("Date".equals(keyword)) {
			toInject = date().getBytes();
		} else {
			throw new IllegalStateException(String.format("Keyword %s is not yet supported", keyword));
		}
		rv = ensureCapacityFor(rv, toInject.length);
		rv.put(toInject);
		return rv;
	}
	
	// copies part of the src buffer, [from..to). doesn't modify src position
	static void copySlice(ByteBuffer src, int from, int to, ByteBuffer dst) {
		if (to > src.limit()) {
			throw new IllegalArgumentException("Bad right boundary");
		}
		if (dst.remaining() < to - from) {
			throw new IllegalArgumentException("Not enough room in the destination buffer");
		}
		for (int i = from; i < to; i++) {
			dst.put(src.get(i));
		}
	}

	private static int indexOf(ByteBuffer b, char ch, int from, boolean newlineBreaks) {
		for (int i = from; i < b.limit(); i++) {
			byte c = b.get(i);
			if (ch == c) {
				return i;
			}
			if (newlineBreaks && (c == '\n' || c == '\r')) {
				return i;
			}
		}
		return -1;
	}

	private String identityString() {
		return String.format("%s,v %s %s %s", path, revision(), date(), username());
	}

	private String revision() {
		try {
			// TODO post-1.0 Either add cset's nodeid into Changeset class or use own inspector 
			// when accessing changelog, see below, #getChangeset
			int csetRev = repo.getFileNode(path).getChangesetRevisionIndex(HgRepository.TIP);
			return repo.getChangelog().getRevision(csetRev).shortNotation();
		} catch (HgRuntimeException ex) {
			repo.getSessionContext().getLog().dump(getClass(), Error, ex, null);
			return Nodeid.NULL.shortNotation(); // XXX perhaps, might return anything better? Not sure how hg approaches this. 
		}
	}
	
	private String username() {
		try {
			return getChangeset().user();
		} catch (HgRuntimeException ex) {
			repo.getSessionContext().getLog().dump(getClass(), Error, ex, null);
			return "";
		}
	}
	
	private String date() {
		Date d;
		try {
			d = getChangeset().date();
		} catch (HgRuntimeException ex) {
			repo.getSessionContext().getLog().dump(getClass(), Error, ex, null);
			d = new Date(0l);
		}
		return String.format("%tY/%<tm/%<td %<tH:%<tM:%<tS", d);
	}
	
	private RawChangeset getChangeset() throws HgRuntimeException {
		if (latestFileCset == null) {
			// TODO post-1.0 Use of TIP is likely incorrect in cases when working copy is not based
			// on latest revision. Perhaps, a constant like HgRepository.DIRSTATE_PARENT may come handy
			// Besides, it's reasonable to pass own inspector instead of implicit use of RawCsetCollector
			// to get changeset nodeid/index right away. Also check ChangelogHelper if may be of any use
			int csetRev = repo.getFileNode(path).getChangesetRevisionIndex(HgRepository.TIP);
			latestFileCset = repo.getChangelog().range(csetRev, csetRev).get(0);
		}
		return latestFileCset;
	}

	public static class Factory implements Filter.Factory {
		private final Map<String,String> keywords;
		private HgRepository repo;
		private Path.Matcher matcher;
		
		public Factory() {
			keywords = new TreeMap<String,String>();
			keywords.put("Id", "Id");
			keywords.put("Revision", "Revision");
			keywords.put("Author", "Author");
			keywords.put("Date", "Date");
			keywords.put("LastChangedRevision", "LastChangedRevision");
			keywords.put("LastChangedBy", "LastChangedBy");
			keywords.put("LastChangedDate", "LastChangedDate");
			keywords.put("Source", "Source");
			keywords.put("Header", "Header");
		}

		public void initialize(HgRepository hgRepo) {
			repo = hgRepo;
			ArrayList<String> patterns = new ArrayList<String>();
			for (Pair<String,String> e : hgRepo.getConfiguration().getSection("keyword")) {
				if (!"ignore".equalsIgnoreCase(e.second())) {
					patterns.add(e.first());
				}
			}
			matcher = new PathGlobMatcher(patterns.toArray(new String[patterns.size()]));
			// TODO post-1.0 read and respect keyword patterns from [keywordmaps]
		}

		public Filter create(Path path, Options opts) {
			if (matcher.accept(path)) {
				return new KeywordFilter(repo, path, keywords, opts.getDirection() == Filter.Direction.FromRepo);
			}
			return null;
		}
	}

//
//	public static void main(String[] args) throws Exception {
//		FileInputStream fis = new FileInputStream(new File("/temp/kwoutput.txt"));
//		FileOutputStream fos = new FileOutputStream(new File("/temp/kwoutput2.txt"));
//		ByteBuffer b = ByteBuffer.allocate(256);
//		KeywordFilter kwFilter = new KeywordFilter(false);
//		while (fis.getChannel().read(b) != -1) {
//			b.flip(); // get ready to be read
//			ByteBuffer f = kwFilter.filter(b);
//			fos.getChannel().write(f); // XXX in fact, f may not be fully consumed
//			if (b.hasRemaining()) {
//				b.compact();
//			} else {
//				b.clear();
//			}
//		}
//		fis.close();
//		fos.flush();
//		fos.close();
//	}
}
