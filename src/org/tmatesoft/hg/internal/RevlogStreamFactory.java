/*
 * Copyright (c) 2013 TMate Software Ltd
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

import java.io.File;
import java.lang.ref.SoftReference;
import java.util.HashMap;

import org.tmatesoft.hg.util.Path;

/**
 * Factory to create {@link RevlogStream RevlogStreams}, cache-capable.
 *   
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class RevlogStreamFactory {
	
	private final Internals repo;
	private final HashMap<Path, SoftReference<RevlogStream>> streamsCache;


	public RevlogStreamFactory(Internals hgRepo, boolean shallCacheRevlogs) {
		repo = hgRepo;
		if (shallCacheRevlogs) {
			streamsCache = new HashMap<Path, SoftReference<RevlogStream>>();
		} else {
			streamsCache = null;
		}
	}
	
	/**
	 * Creates a stream for specified file, doesn't cache stream
	 */
	/*package-local*/ RevlogStream create(File f) {
		return new RevlogStream(repo, f);
	}

	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 * @param path - normalized file name
	 * @return <code>null</code> if path doesn't resolve to a existing file
	 */
	/*package-local*/ RevlogStream getStoreFile(Path path, boolean onlyIfExists) {
		final SoftReference<RevlogStream> ref = shallCacheRevlogs() ? streamsCache.get(path) : null;
		RevlogStream cached = ref == null ? null : ref.get();
		if (cached != null) {
			return cached;
		}
		File f = repo.getFileFromDataDir(path);
		if (!onlyIfExists || f.exists()) {
			RevlogStream s = create(f);
			if (shallCacheRevlogs()) {
				streamsCache.put(path, new SoftReference<RevlogStream>(s));
			}
			return s;
		}
		return null;
	}
	
	private boolean shallCacheRevlogs() {
		return streamsCache != null;
	}
}
