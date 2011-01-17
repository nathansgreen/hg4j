/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.BufferedInputStream;
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
	
//	public void statusLocal(int baseRevision, StatusCollector.Inspector inspector) {
//		LinkedList<File> folders = new LinkedList<File>();
//		final File rootDir = repoDir.getParentFile();
//		folders.add(rootDir);
//		final HgDirstate dirstate = loadDirstate();
//		final HgIgnore hgignore = loadIgnore();
//		TreeSet<String> knownEntries = dirstate.all();
//		final boolean isTipBase = baseRevision == TIP || baseRevision == getManifest().getRevisionCount();
//		final StatusCollector.ManifestRevisionInspector collect = isTipBase ? null : new StatusCollector.ManifestRevisionInspector();
//		if (!isTipBase) {
//			getManifest().walk(baseRevision, baseRevision, collect);
//		}
//		do {
//			File d = folders.removeFirst();
//			for (File f : d.listFiles()) {
//				if (f.isDirectory()) {
//					if (!".hg".equals(f.getName())) {
//						folders.addLast(f);
//					}
//				} else {
//					// FIXME path relative to rootDir - need more robust approach
//					String fname = normalize(f.getPath().substring(rootDir.getPath().length() + 1));
//					if (hgignore.isIgnored(fname)) {
//						inspector.ignored(fname);
//					} else {
//						if (knownEntries.remove(fname)) {
//							// modified, added, removed, clean
//							if (collect != null) { // need to check against base revision, not FS file
//								checkLocalStatusAgainstBaseRevision(collect, fname, f, dirstate, inspector);
//							} else {
//								checkLocalStatusAgainstFile(fname, f, dirstate, inspector);
//							}
//						} else {
//							inspector.unknown(fname);
//						}
//					}
//				}
//			}
//		} while (!folders.isEmpty());
//		if (collect != null) {
//			for (String r : collect.idsMap.keySet()) {
//				inspector.removed(r);
//			}
//		}
//		for (String m : knownEntries) {
//			// removed from the repository and missing from working dir shall not be reported as 'deleted' 
//			if (dirstate.checkRemoved(m) == null) {
//				inspector.missing(m);
//			}
//		}
//	}
//	
//	private static void checkLocalStatusAgainstFile(String fname, File f, HgDirstate dirstate, StatusCollector.Inspector inspector) {
//		HgDirstate.Record r;
//		if ((r = dirstate.checkNormal(fname)) != null) {
//			// either clean or modified
//			if (f.lastModified() / 1000 == r.time && r.size == f.length()) {
//				inspector.clean(fname);
//			} else {
//				// FIXME check actual content to avoid false modified files
//				inspector.modified(fname);
//			}
//		} else if ((r = dirstate.checkAdded(fname)) != null) {
//			if (r.name2 == null) {
//				inspector.added(fname);
//			} else {
//				inspector.copied(fname, r.name2);
//			}
//		} else if ((r = dirstate.checkRemoved(fname)) != null) {
//			inspector.removed(fname);
//		} else if ((r = dirstate.checkMerged(fname)) != null) {
//			inspector.modified(fname);
//		}
//	}
//	
//	// XXX refactor checkLocalStatus methods in more OO way
//	private void checkLocalStatusAgainstBaseRevision(StatusCollector.ManifestRevisionInspector collect, String fname, File f, HgDirstate dirstate, StatusCollector.Inspector inspector) {
//		// fname is in the dirstate, either Normal, Added, Removed or Merged
//		Nodeid nid1 = collect.idsMap.remove(fname);
//		String flags = collect.flagsMap.remove(fname);
//		HgDirstate.Record r;
//		if (nid1 == null) {
//			// normal: added?
//			// added: not known at the time of baseRevision, shall report
//			// merged: was not known, report as added?
//			if ((r = dirstate.checkAdded(fname)) != null) {
//				if (r.name2 != null && collect.idsMap.containsKey(r.name2)) {
//					collect.idsMap.remove(r.name2);
//					collect.idsMap.remove(r.name2);
//					inspector.copied(r.name2, fname);
//					return;
//				}
//				// fall-through, report as added
//			} else if (dirstate.checkRemoved(fname) != null) {
//				// removed: removed file was not known at the time of baseRevision, and we should not report it as removed
//				return;
//			}
//			inspector.added(fname);
//		} else {
//			// was known; check whether clean or modified
//			// when added - seems to be the case of a file added once again, hence need to check if content is different
//			if ((r = dirstate.checkNormal(fname)) != null || (r = dirstate.checkMerged(fname)) != null || (r = dirstate.checkAdded(fname)) != null) {
//				// either clean or modified
//				HgDataFile fileNode = getFileNode(fname);
//				final int lengthAtRevision = fileNode.length(nid1);
//				if (r.size /* XXX File.length() ?! */ != lengthAtRevision || flags != todoGenerateFlags(fname /*java.io.File*/)) {
//					inspector.modified(fname);
//				} else {
//					// check actual content to see actual changes
//					// XXX consider adding HgDataDile.compare(File/byte[]/whatever) operation to optimize comparison
//					if (areTheSame(f, fileNode.content(nid1))) {
//						inspector.clean(fname);
//					} else {
//						inspector.modified(fname);
//					}
//				}
//			}
//			// only those left in idsMap after processing are reported as removed 
//		}
//
//		// TODO think over if content comparison may be done more effectively by e.g. calculating nodeid for a local file and comparing it with nodeid from manifest
//		// we don't need to tell exact difference, hash should be enough to detect difference, and it doesn't involve reading historical file content, and it's relatively 
//		// cheap to calc hash on a file (no need to keep it completely in memory). OTOH, if I'm right that the next approach is used for nodeids: 
//		// changeset nodeid + hash(actual content) => entry (Nodeid) in the next Manifest
//		// then it's sufficient to check parents from dirstate, and if they do not match parents from file's baseRevision (non matching parents means different nodeids).
//		// The question is whether original Hg treats this case (same content, different parents and hence nodeids) as 'modified' or 'clean'
//	}

	private static String todoGenerateFlags(String fname) {
		// FIXME implement
		return null;
	}
	private static boolean areTheSame(File f, byte[] data) {
		try {
			BufferedInputStream is = new BufferedInputStream(new FileInputStream(f));
			int i = 0;
			while (i < data.length && data[i] == is.read()) {
				i++; // increment only for successful match, otherwise won't tell last byte in data was the same as read from the stream
			}
			return i == data.length && is.read() == -1; // although data length is expected to be the same (see caller), check that we reached EOF, no more data left.
		} catch (IOException ex) {
			ex.printStackTrace(); // log warn
		}
		return false;
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

	@Override
	protected HgTags createTags() {
		return new HgTags();
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
