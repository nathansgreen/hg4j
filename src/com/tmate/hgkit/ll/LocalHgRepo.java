/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
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
import java.util.LinkedList;
import java.util.TreeSet;

import com.tmate.hgkit.fs.DataAccessProvider;

/**
 * @author artem
 */
public class LocalHgRepo extends HgRepository {

	private File repoDir; // .hg folder
	private final String repoLocation;
	private final DataAccessProvider dataAccess;

	public LocalHgRepo(String repositoryPath) {
		setInvalid(true);
		repoLocation = repositoryPath;
		dataAccess = null;
	}
	
	public LocalHgRepo(File repositoryRoot) throws IOException {
		assert ".hg".equals(repositoryRoot.getName()) && repositoryRoot.isDirectory();
		setInvalid(false);
		repoDir = repositoryRoot;
		repoLocation = repositoryRoot.getParentFile().getCanonicalPath();
		dataAccess = new DataAccessProvider();
		parseRequires();
	}

	@Override
	public String getLocation() {
		return repoLocation;
	}
	
	@Override
	public void status(int rev1, int rev2, final StatusInspector inspector) {
		final HashMap<String, Nodeid> idsMap = new HashMap<String, Nodeid>();
		final HashMap<String, String> flagsMap = new HashMap<String, String>();
		HgManifest.Inspector collect = new HgManifest.Inspector() {
			
			
			public boolean next(Nodeid nid, String fname, String flags) {
				idsMap.put(fname, nid);
				flagsMap.put(fname, flags);
				return true;
			}
			
			public boolean end(int revision) {
				return false;
			}
			
			public boolean begin(int revision, Nodeid nid) {
				return true;
			}
		};
		getManifest().walk(rev1, rev1, collect);
		
		HgManifest.Inspector compare = new HgManifest.Inspector() {

			public boolean begin(int revision, Nodeid nid) {
				return true;
			}

			public boolean next(Nodeid nid, String fname, String flags) {
				Nodeid nidR1 = idsMap.remove(fname);
				String flagsR1 = flagsMap.remove(fname);
				if (nidR1 == null) {
					inspector.added(fname);
				} else {
					if (nidR1.compareTo(nid) == 0 && ((flags == null && flagsR1 == null) || flags.equals(flagsR1))) {
						inspector.clean(fname);
					} else {
						inspector.modified(fname);
					}
				}
				return true;
			}

			public boolean end(int revision) {
				for (String fname : idsMap.keySet()) {
					inspector.removed(fname);
				}
				if (idsMap.size() != flagsMap.size()) {
					throw new IllegalStateException();
				}
				return false;
			}
		};
		getManifest().walk(rev2, rev2, compare);
	}
	
	public void statusLocal(int rev1, StatusInspector inspector) {
		LinkedList<File> folders = new LinkedList<File>();
		final File rootDir = repoDir.getParentFile();
		folders.add(rootDir);
		final HgDirstate dirstate = loadDirstate();
		final HgIgnore hgignore = loadIgnore();
		TreeSet<String> knownEntries = dirstate.all();
		do {
			File d = folders.removeFirst();
			for (File f : d.listFiles()) {
				if (f.isDirectory()) {
					if (!".hg".equals(f.getName())) {
						folders.addLast(f);
					}
				} else {
					// FIXME path relative to rootDir
					String fname = normalize(f.getPath().substring(rootDir.getPath().length() + 1));
					if (hgignore.isIgnored(fname)) {
						inspector.ignored(fname);
					} else {
						if (knownEntries.remove(fname)) {
							// modified, added, removed, clean
							HgDirstate.Record r;
							if ((r = dirstate.checkNormal(fname)) != null) {
								// either clean or modified
								if (f.lastModified() / 1000 == r.time && r.size == f.length()) {
									inspector.clean(fname);
								} else {
									// FIXME check actual content to avoid false modified files
									inspector.modified(fname);
								}
							} else if ((r = dirstate.checkAdded(fname)) != null) {
								if (r.name2 == null) {
									inspector.added(fname);
								} else {
									inspector.copied(fname, r.name2);
								}
							} else if ((r = dirstate.checkRemoved(fname)) != null) {
								inspector.removed(fname);
							} else if ((r = dirstate.checkMerged(fname)) != null) {
								inspector.modified(fname);
							}
						} else {
							inspector.unknown(fname);
						}
					}
				}
			}
		} while (!folders.isEmpty());
		for (String m : knownEntries) {
			inspector.missing(m);
		}
	}
	
	// XXX package-local, unless there are cases when required from outside (guess, working dir/revision walkers may hide dirstate access and no public visibility needed)
	public final HgDirstate loadDirstate() {
		// XXX may cache in SoftReference if creation is expensive
		return new HgDirstate(this, new File(repoDir, "dirstate"));
	}

	// package-local, see comment for loadDirstate
	public final HgIgnore loadIgnore() {
		return new HgIgnore(this);
	}

	/*package-local*/ DataAccessProvider getDataAccess() {
		return dataAccess;
	}
	
	/*package-local*/ File getRepositoryRoot() {
		return repoDir;
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
			RevlogStream s = new RevlogStream(dataAccess, f);
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

	// FIXME document what path argument is, whether it includes .i or .d, and whether it's 'normalized' (slashes) or not.
	// since .hg/store keeps both .i files and files without extension (e.g. fncache), guees, for data == false 
	// we shall assume path has extension
	// FIXME much more to be done, see store.py:_hybridencode
	// @see http://mercurial.selenic.com/wiki/CaseFoldingPlan
	@Override
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
		buf[0] = hexDigits.charAt((ch & 0x00F0) >>> 4);
		buf[1] = hexDigits.charAt(ch & 0x0F);
		return buf;
	}

	// TODO handle . and .. (although unlikely to face them from GUI client)
	private static String normalize(String path) {
		path = path.replace('\\', '/').replace("//", "/");
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		return path;
	}
}
