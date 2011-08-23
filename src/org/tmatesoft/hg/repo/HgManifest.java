/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.Path;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgManifest extends Revlog {
	private RevisionMapper revisionMap;

	/*package-local*/ HgManifest(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content);
	}

	/**
	 * 
	 * @param start changelog (not manifest!) revision to begin with
	 * @param end changelog (not manifest!) revision to stop, inclusive.
	 * @param inspector can't be <code>null</code>
	 */
	public void walk(int start, int end, final Inspector inspector) {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		int start0 = fromChangelog(start);
		int end0 = fromChangelog(end);
		content.iterate(start0, end0, true, new ManifestParser(inspector));
	}
	
	/**
	 * "Sparse" iteration of the manifest
	 * 
	 * @param inspector
	 * @param localRevisions local changeset revisions to visit
	 */
	public void walk(final Inspector inspector, int... localRevisions) {
		if (inspector == null || localRevisions == null) {
			throw new IllegalArgumentException();
		}
		int[] manifestLocalRevs = new int[localRevisions.length];
		boolean needsSort = false;
		for (int i = 0; i < localRevisions.length; i++) {
			final int manifestLocalRev = fromChangelog(localRevisions[i]);
			manifestLocalRevs[i] = manifestLocalRev;
			if (i > 0 && manifestLocalRevs[i-1] > manifestLocalRev) {
				needsSort = true;
			}
		}
		if (needsSort) {
			Arrays.sort(manifestLocalRevs);
		}
		content.iterate(manifestLocalRevs, true, new ManifestParser(inspector));
	}
	
	// manifest revision number that corresponds to the given changeset
	/*package-local*/ int fromChangelog(int revisionNumber) {
		if (HgInternals.wrongLocalRevision(revisionNumber)) {
			throw new IllegalArgumentException(String.valueOf(revisionNumber));
		}
		if (revisionNumber == HgRepository.WORKING_COPY || revisionNumber == HgRepository.BAD_REVISION) {
			throw new IllegalArgumentException("Can't use constants like WORKING_COPY or BAD_REVISION");
		}
		// revisionNumber == TIP is processed by RevisionMapper 
		if (revisionMap == null) {
			revisionMap = new RevisionMapper(getRepo());
			content.iterate(0, TIP, false, revisionMap);
		}
		return revisionMap.at(revisionNumber);
	}
	
	/**
	 * Extracts file revision as it was known at the time of given changeset.
	 * 
	 * @param revisionNumber local changeset index 
	 * @param file path to file in question
	 * @return file revision or <code>null</code> if manifest at specified revision doesn't list such file
	 */
	@Experimental(reason="Perhaps, HgDataFile shall own this method")
	public Nodeid getFileRevision(int revisionNumber, final Path file) {
		int rev = fromChangelog(revisionNumber);
		final Nodeid[] rv = new Nodeid[] { null };
		content.iterate(rev, rev, true, new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				try {
					byte b;
					while (!data.isEmpty() && (b = data.readByte()) != '\n') {
						if (b != 0) {
							bos.write(b);
						} else {
							String fname = new String(bos.toByteArray());
							bos.reset();
							if (file.toString().equals(fname)) {
								byte[] nid = new byte[40];  
								data.readBytes(nid, 0, 40);
								rv[0] = Nodeid.fromAscii(nid, 0, 40);
								break;
							}
							// else skip to the end of line
							while (!data.isEmpty() && (b = data.readByte()) != '\n')
								;
						}
					}
				} catch (IOException ex) {
					throw new HgBadStateException(ex);
				}
			}
		});
		return rv[0];
	}
			
	public interface Inspector {
		boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision);
		boolean next(Nodeid nid, String fname, String flags);
		boolean end(int manifestRevision);
	}
	
	public interface ElementProxy<T> {
		T get();
	}
	
	private static class PoolStringProxy {
		private byte[] data;
		private int start, length;
		private int hash;
		private String result;

		public void init(byte[] data, int start, int length) {
			this.data = data;
			this.start = start;
			this.length = length;

			// copy from String.hashCode()
			int h = 0;
			byte[] d = data;
			for (int i = 0, off = start, len = length; i < len; i++) {
				h = 31 * h + d[off++];
			}
			hash = h;
		}

		@Override
		public boolean equals(Object obj) {
			if (false == obj instanceof PoolStringProxy) {
				return false;
			}
			PoolStringProxy o = (PoolStringProxy) obj;
			if (o.result != null && result != null) {
				return result.equals(o.result);
			}
			if (o.result == null && result != null || o.result != null && result == null) {
				String s; PoolStringProxy noString; 
				if (o.result == null) {
					s = result;
					noString = o;
				} else {
					s = o.result;
					noString = this;
				}
				
			}
			// both are null
			if (o.length != length) {
				return false;
			}
			for (int i = 0, x = o.start, y = start; i < length; i++) {
				if (o.data[x++] != data[y++]) {
					return false;
				}
			}
			return true;
		}
		@Override
		public int hashCode() {
			return hash;
		}
		
		public String freeze() {
			if (result == null) {
				result = new String(data, start, length);
				data = null;
				start = length = -1;
			}
			return result;
		}
	}

	private static class ManifestParser implements RevlogStream.Inspector/*, Lifecycle */{
		private boolean gtg = true; // good to go
		private final Inspector inspector;
		private Pool<Nodeid> nodeidPool, thisRevPool;
		private final Pool<String> fnamePool;
		private final Pool<String> flagsPool;
		private byte[] nodeidLookupBuffer = new byte[20]; // get reassigned each time new Nodeid is added to pool
		
		public ManifestParser(Inspector delegate) {
			assert delegate != null;
			inspector = delegate;
			nodeidPool = new Pool<Nodeid>();
			fnamePool = new Pool<String>();
			flagsPool = new Pool<String>();
			thisRevPool = new Pool<Nodeid>();
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
			if (!gtg) {
				return;
			}
			try {
				gtg = gtg && inspector.begin(revisionNumber, new Nodeid(nodeid, true), linkRevision);
				String fname = null;
				String flags = null;
				Nodeid nid = null;
				int i;
				byte[] data = da.byteArray();
				for (i = 0; gtg && i < actualLen; i++) {
					int x = i;
					for( ; data[i] != '\n' && i < actualLen; i++) {
						if (fname == null && data[i] == 0) {
							fname = fnamePool.unify(new String(data, x, i - x));
							x = i+1;
						}
					}
					if (i < actualLen) {
						assert data[i] == '\n'; 
						int nodeidLen = i - x < 40 ? i-x : 40; // if > 40, there are flags
						DigestHelper.ascii2bin(data, x, nodeidLen, nodeidLookupBuffer); // ignore return value as it's unlikely to have NULL in manifest
						nid = new Nodeid(nodeidLookupBuffer, false); // this Nodeid is for pool lookup only, mock object
						Nodeid cached = nodeidPool.unify(nid);
						if (cached == nid) {
							// buffer now belongs to the cached nodeid
							nodeidLookupBuffer = new byte[20];
						} else {
							nid = cached; // use existing version, discard the lookup object
						} // for cpython 0..10k, cache hits are 15 973 301, vs 18871 misses.
						thisRevPool.record(nid); // memorize revision for the next iteration. 
						if (nodeidLen + x < i) {
							// 'x' and 'l' for executable bits and symlinks?
							// hg --debug manifest shows 644 for each regular file in my repo
							flags = flagsPool.unify(new String(data, x + nodeidLen, i-x-nodeidLen));
						}
						gtg = gtg && inspector.next(nid, fname, flags);
					}
					nid = null;
					fname = flags = null;
				}
				gtg = gtg && inspector.end(revisionNumber);
				//
				// keep only actual file revisions, found at this version 
				// (next manifest is likely to refer to most of them, although in specific cases 
				// like commit in another branch a lot may be useless)
				nodeidPool.clear();
				Pool<Nodeid> t = nodeidPool;
				nodeidPool = thisRevPool;
				thisRevPool = t;
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}
//
//		public void start(int count, Callback callback, Object token) {
//		}
//
//		public void finish(Object token) {
//			System.out.println(fnamePool);
//			System.out.println(nodeidPool);
//			System.out.printf("Free mem once parse done: %,d\n", Runtime.getRuntime().freeMemory());
//		}
	}
	
	private static class RevisionMapper implements RevlogStream.Inspector, Lifecycle {
		
		private final int changelogRevisions;
		private int[] changelog2manifest;
		private final HgRepository repo;

		public RevisionMapper(HgRepository hgRepo) {
			repo = hgRepo;
			changelogRevisions = repo.getChangelog().getRevisionCount();
		}

		// respects TIP
		public int at(int revisionNumber) {
			if (revisionNumber == TIP) {
				revisionNumber = changelogRevisions - 1;
			}
			if (changelog2manifest != null) {
				return changelog2manifest[revisionNumber];
			}
			return revisionNumber;
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
			if (changelog2manifest != null) {
				// next assertion is not an error, rather assumption check, which is too development-related to be explicit exception - 
				// I just wonder if there are manifests that have two entries pointing to single changeset. It seems unrealistic, though -
				// changeset records one and only one manifest nodeid
				assert changelog2manifest[linkRevision] == -1 : String.format("revision:%d, link:%d, already linked to revision:%d", revisionNumber, linkRevision, changelog2manifest[linkRevision]);
				changelog2manifest[linkRevision] = revisionNumber;
			} else {
				if (revisionNumber != linkRevision) {
					changelog2manifest = new int[changelogRevisions];
					Arrays.fill(changelog2manifest, -1);
					for (int i = 0; i < revisionNumber; changelog2manifest[i] = i, i++)
						;
					changelog2manifest[linkRevision] = revisionNumber;
				}
			}
		}
		
		public void start(int count, Callback callback, Object token) {
			if (count != changelogRevisions) {
				assert count < changelogRevisions; // no idea what to do if manifest has more revisions than changelog
				// the way how manifest may contain more revisions than changelog, as I can imagine, is a result of  
				// some kind of an import tool (e.g. from SVN or CVS), that creates manifest and changelog independently.
				// Note, it's pure guess, I didn't see such repository yet (although the way manifest revisions
				// in cpython repo are numbered makes me think aforementioned way) 
				changelog2manifest = new int[changelogRevisions];
				Arrays.fill(changelog2manifest, -1);
			}
		}

		public void finish(Object token) {
			if (changelog2manifest == null) {
				return;
			}
			// I assume there'd be not too many revisions we don't know manifest of
			ArrayList<Integer> undefinedChangelogRevision = new ArrayList<Integer>();
			for (int i = 0; i < changelog2manifest.length; i++) {
				if (changelog2manifest[i] == -1) {
					undefinedChangelogRevision.add(i);
				}
			}
			for (int u : undefinedChangelogRevision) {
				Nodeid manifest = repo.getChangelog().range(u, u).get(0).manifest();
				// FIXME calculate those missing effectively (e.g. cache and sort nodeids to speed lookup
				// right away in the #next (may refactor ParentWalker's sequential and sorted into dedicated helper and reuse here)
				changelog2manifest[u] = repo.getManifest().getLocalRevision(manifest);
			}
		}
	}
}
