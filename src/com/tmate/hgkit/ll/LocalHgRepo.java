/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeSet;

/**
 * @author artem
 */
public class LocalHgRepo extends HgRepository {

	private File repoDir; // .hg folder
	private final String repoLocation;

	public LocalHgRepo(String repositoryPath) {
		setInvalid(true);
		repoLocation = repositoryPath;
	}
	
	public LocalHgRepo(File repositoryRoot) throws IOException {
		assert ".hg".equals(repositoryRoot.getName()) && repositoryRoot.isDirectory();
		setInvalid(false);
		repoDir = repositoryRoot;
		repoLocation = repositoryRoot.getParentFile().getCanonicalPath();
		parseRequires();
	}

	@Override
	public String getLocation() {
		return repoLocation;
	}

	private final HashMap<String, SoftReference<RevlogStream>> streamsCache = new HashMap<String, SoftReference<RevlogStream>>();

	/**
	 * path - repository storage path (i.e. one usually with .i or .d)
	 */
	@Override
	protected RevlogStream resolve(String path) {
		final SoftReference<RevlogStream> ref = streamsCache.get(path);
		RevlogStream cached = ref == null ? null : ref.get();
		if (cached != null) {
			return cached;
		}
		File f = new File(repoDir, path);
		if (f.exists()) {
			RevlogStream s = new RevlogStream(f);
			streamsCache.put(path, new SoftReference<RevlogStream>(s));
			return s;
		}
		return null;
	}

	@Override
	public HgDataFile getFileNode(String path) {
		String nPath = normalize(path);
		String storagePath = toStoragePath(nPath, true);
		RevlogStream content = resolve(storagePath);
		// XXX no content when no file? or HgDataFile.exists() to detect that? How about files that were removed in previous releases?
		return new HgDataFile(this, nPath, content);
	}

	private boolean revlogv1;
	private boolean store;
	private boolean fncache;
	private boolean dotencode;
	
	
	private void parseRequires() {
		File requiresFile = new File(repoDir, "requires");
		if (!requiresFile.exists()) {
			return;
		}
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(requiresFile)));
			String line;
			while ((line = br.readLine()) != null) {
				revlogv1 |= "revlogv1".equals(line);
				store |= "store".equals(line);
				fncache |= "fncache".equals(line);
				dotencode |= "dotencode".equals(line);
			}
		} catch (IOException ex) {
			ex.printStackTrace(); // FIXME log
		}
	}

	// FIXME much more to be done, see store.py:_hybridencode
	// @see http://mercurial.selenic.com/wiki/CaseFoldingPlan
	protected String toStoragePath(String path, boolean data) {
		path = normalize(path);
		final String STR_STORE = "store/";
		final String STR_DATA = "data/";
		final String STR_DH = "dh/";
		if (!data) {
			return this.store ? STR_STORE + path : path;
		}
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
		if (this.store) {
			sb.insert(0, STR_STORE + STR_DATA);
		}
		sb.append(".i");
		return sb.toString();
	}

	private static char[] toHexByte(int ch, char[] buf) {
		assert buf.length > 1;
		final String hexDigits = "0123456789abcdef";
		buf[0] = hexDigits.charAt((ch & 0x00F0) >> 4);
		buf[1] = hexDigits.charAt(ch & 0x0F);
		return buf;
	}

	private static String normalize(String path) {
		path = path.replace('\\', '/').replace("//", "/");
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		return path;
	}
}
