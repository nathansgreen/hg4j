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
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.internal.Filter.Direction.FromRepo;
import static org.tmatesoft.hg.internal.Filter.Direction.ToRepo;
import static org.tmatesoft.hg.internal.KeywordFilter.copySlice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;

import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class NewlineFilter implements Filter {

	// if allowInconsistent is true, filter simply pass incorrect newline characters (single \r or \r\n on *nix and single \n on Windows) as is,
	// i.e. doesn't try to convert them into appropriate newline characters. XXX revisit if Keyword extension behaves differently
	private final boolean allowInconsistent;
	private final boolean winToNix;

	private NewlineFilter(boolean failIfInconsistent, int transform) {
		winToNix = transform == 0;
		allowInconsistent = !failIfInconsistent;
	}

	public ByteBuffer filter(ByteBuffer src) {
		if (winToNix) {
			return win2nix(src);
		} else {
			return nix2win(src);
		}
	}

	private ByteBuffer win2nix(ByteBuffer src) {
		int x = src.position(); // source index
		int lookupStart = x;
		ByteBuffer dst = null;
		while (x < src.limit()) {
			// x, lookupStart, ir and in are absolute positions within src buffer, which is never read with modifying operations
			int ir = indexOf('\r', src, lookupStart);
			int in = indexOf('\n', src, lookupStart);
			if (ir == -1) {
				if (in == -1 || allowInconsistent) {
					if (dst != null) {
						copySlice(src, x, src.limit(), dst);
						x = src.limit(); // consumed all
					}
					break;
				} else {
					fail(src, in);
				}
			}
			// in == -1 while ir != -1 may be valid case if ir is the last char of the buffer, we check below for that
			if (in != -1 && in != ir+1 && !allowInconsistent) {
				fail(src, in);
			}
			if (dst == null) {
				dst = ByteBuffer.allocate(src.remaining());
			}
			copySlice(src, x, ir, dst);
			if (ir+1 == src.limit()) {
				// last char of the buffer - 
				// consume src till that char and let next iteration work on it
				x = ir;
				break;
			}
			if (in != ir + 1) {
				x = ir+1; // generally in, but if allowInconsistent==true and \r is not followed by \n, then 
				// cases like "one \r two \r\n three" shall be processed correctly (second pair would be ignored if x==in)
				lookupStart = ir+1;
			} else {
				x = in;
				lookupStart = x+1; // skip \n for next lookup
			}
		}
		src.position(x); // mark we've consumed up to x
		return dst == null ? src : (ByteBuffer) dst.flip();
	}

	private ByteBuffer nix2win(ByteBuffer src) {
		int x = src.position();
		ByteBuffer dst = null;
		while (x < src.limit()) {
			int in = indexOf('\n', src, x);
			int ir = indexOf('\r', src, x, in == -1 ? src.limit() : in);
			if (in == -1) {
				if (ir == -1 || allowInconsistent) {
					break;
				} else {
					fail(src, ir);
				}
			} else if (ir != -1 && !allowInconsistent) {
				fail(src, ir);
			}
			
			// x <= in < src.limit
			// allowInconsistent && x <= ir < in   || ir == -1  
			if (dst == null) {
				// buffer full of \n grows as much as twice in size
				dst = ByteBuffer.allocate(src.remaining() * 2);
			}
			copySlice(src, x, in, dst);
			if (ir == -1 || ir+1 != in) {
				dst.put((byte) '\r');
			} // otherwise (ir!=-1 && ir+1==in) we found \r\n pair, don't convert to \r\r\n
			// we may copy \n at src[in] on the next iteration, but would need extra lookupIndex variable then.
			dst.put((byte) '\n');
			x = in+1;
		}
		src.position(x);
		return dst == null ? src : (ByteBuffer) dst.flip();
	}


	private void fail(ByteBuffer b, int pos) {
		throw new RuntimeException(String.format("Inconsistent newline characters in the stream (char 0x%x, local index:%d)", b.get(pos), pos));
	}

	private static int indexOf(char ch, ByteBuffer b, int from) {
		return indexOf(ch, b, from, b.limit());
	}

	// looks up in buf[from..to)
	private static int indexOf(char ch, ByteBuffer b, int from, int to) {
		for (int i = from; i < to; i++) {
			byte c = b.get(i);
			if (ch == c) {
				return i;
			}
		}
		return -1;
	}

	public static class Factory implements Filter.Factory {
		private boolean failIfInconsistent = true;
		private Path.Matcher lfMatcher;
		private Path.Matcher crlfMatcher;
		private Path.Matcher binMatcher;
		private Path.Matcher nativeMatcher;
		private String nativeRepoFormat;
		private String nativeOSFormat;

		public void initialize(HgRepository hgRepo, ConfigFile cfg) {
			failIfInconsistent = cfg.getBoolean("eol", "only-consistent", true);
			File cfgFile = new File(new HgInternals(hgRepo).getRepositoryDir().getParentFile(), ".hgeol");
			if (!cfgFile.canRead()) {
				return;
			}
			// XXX if .hgeol is not checked out, we may get it from repository
//			HgDataFile cfgFileNode = hgRepo.getFileNode(".hgeol");
//			if (!cfgFileNode.exists()) {
//				return;
//			}
			// XXX perhaps, add HgDataFile.hasWorkingCopy and workingCopyContent()?
			ConfigFile hgeol = new ConfigFile();
			hgeol.addLocation(cfgFile);
			nativeRepoFormat = hgeol.getSection("repository").get("native");
			if (nativeRepoFormat == null) {
				nativeRepoFormat = "LF";
			}
			final String os = System.getProperty("os.name"); // XXX need centralized set of properties
			nativeOSFormat = os.indexOf("Windows") != -1 ? "CRLF" : "LF";
			// I assume pattern ordering in .hgeol is not important
			ArrayList<String> lfPatterns = new ArrayList<String>();
			ArrayList<String> crlfPatterns = new ArrayList<String>();
			ArrayList<String> nativePatterns = new ArrayList<String>();
			ArrayList<String> binPatterns = new ArrayList<String>();
			for (Map.Entry<String,String> e : hgeol.getSection("patterns").entrySet()) {
				if ("CRLF".equals(e.getValue())) {
					crlfPatterns.add(e.getKey());
				} else if ("LF".equals(e.getValue())) {
					lfPatterns.add(e.getKey());
				} else if ("native".equals(e.getValue())) {
					nativePatterns.add(e.getKey());
				} else if ("BIN".equals(e.getValue())) {
					binPatterns.add(e.getKey());
				} else {
					System.out.printf("Can't recognize .hgeol entry: %s for %s", e.getValue(), e.getKey()); // FIXME log warning
				}
			}
			if (!crlfPatterns.isEmpty()) {
				crlfMatcher = new PathGlobMatcher(crlfPatterns.toArray(new String[crlfPatterns.size()]));
			}
			if (!lfPatterns.isEmpty()) {
				lfMatcher = new PathGlobMatcher(lfPatterns.toArray(new String[lfPatterns.size()]));
			}
			if (!binPatterns.isEmpty()) {
				binMatcher = new PathGlobMatcher(binPatterns.toArray(new String[binPatterns.size()]));
			}
			if (!nativePatterns.isEmpty()) {
				nativeMatcher = new PathGlobMatcher(nativePatterns.toArray(new String[nativePatterns.size()]));
			}
		}

		public Filter create(Path path, Options opts) {
			if (binMatcher == null && crlfMatcher == null && lfMatcher == null && nativeMatcher == null) {
				// not initialized - perhaps, no .hgeol found
				return null;
			}
			if (binMatcher != null && binMatcher.accept(path)) {
				return null;
			}
			if (crlfMatcher != null && crlfMatcher.accept(path)) {
				return new NewlineFilter(failIfInconsistent, 1);
			} else if (lfMatcher != null && lfMatcher.accept(path)) {
				return new NewlineFilter(failIfInconsistent, 0);
			} else if (nativeMatcher != null && nativeMatcher.accept(path)) {
				if (nativeOSFormat.equals(nativeRepoFormat)) {
					return null;
				}
				if (opts.getDirection() == FromRepo) {
					int transform = "CRLF".equals(nativeOSFormat) ? 1 : 0;
					return new NewlineFilter(failIfInconsistent, transform);
				} else if (opts.getDirection() == ToRepo) {
					int transform = "CRLF".equals(nativeOSFormat) ? 0 : 1;
					return new NewlineFilter(failIfInconsistent, transform);
				}
				return null;
			}
			return null;
		}
	}

	public static void main(String[] args) throws Exception {
		FileInputStream fis = new FileInputStream(new File("/temp/design.lf.txt"));
		FileOutputStream fos = new FileOutputStream(new File("/temp/design.newline.out"));
		ByteBuffer b = ByteBuffer.allocate(12);
		NewlineFilter nlFilter = new NewlineFilter(true, 1);
		while (fis.getChannel().read(b) != -1) {
			b.flip(); // get ready to be read
			ByteBuffer f = nlFilter.filter(b);
			fos.getChannel().write(f); // XXX in fact, f may not be fully consumed
			if (b.hasRemaining()) {
				b.compact();
			} else {
				b.clear();
			}
		}
		fis.close();
		fos.flush();
		fos.close();
	}

}
