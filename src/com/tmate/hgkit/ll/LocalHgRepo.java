/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.HashMap;

/**
 * @author artem
 */
public class LocalHgRepo extends HgRepository {

	private File repoDir;
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
		String storagePath = toStoragePath(nPath);
		RevlogStream content = resolve(storagePath);
		// XXX no content when no file? or HgDataFile.exists() to detect that? How about files that were removed in previous releases?
		return new HgDataFile(this, nPath, content);
	}
	
	// FIXME much more to be done, see store.py:_hybridencode
	private static String toStoragePath(String path) {
		// XXX works for lowercase names only
		return "store/data/" + path.replace('\\', '/') + ".i";
	}

	private static String normalize(String path) {
		return path.replace('\\', '/');
	}
}
