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

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;


/**
 * Base class for all Mercurial entities that are serialized in a so called revlog format (changelog, manifest, data files).
 * 
 * Implementation note:
 * Hides actual actual revlog stream implementation and its access methods (i.e. RevlogStream.Inspector), iow shall not expose anything internal
 * in public methods.
 *   
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
abstract class Revlog {

	private final HgRepository repo;
	protected final RevlogStream content;

	protected Revlog(HgRepository hgRepo, RevlogStream contentStream) {
		if (hgRepo == null) {
			throw new IllegalArgumentException();
		}
		if (contentStream == null) {
			throw new IllegalArgumentException();
		}
		repo = hgRepo;
		content = contentStream;
	}
	
	// invalid Revlog
	protected Revlog(HgRepository hgRepo) {
		repo = hgRepo;
		content = null;
	}

	public final HgRepository getRepo() {
		return repo;
	}

	public final int getRevisionCount() {
		return content.revisionCount();
	}
	
	public final int getLastRevision() {
		return content.revisionCount() - 1;
	}
	
	public final Nodeid getRevision(int revision) {
		// XXX cache nodeids?
		return Nodeid.fromBinary(content.nodeid(revision), 0);
	}

	public final int getLocalRevision(Nodeid nid) {
		int revision = content.findLocalRevisionNumber(nid);
		if (revision == BAD_REVISION) {
			throw new IllegalArgumentException(String.format("%s doesn't represent a revision of %s", nid.toString(), this /*XXX HgDataFile.getPath might be more suitable here*/));
		}
		return revision;
	}

	// Till now, i follow approach that NULL nodeid is never part of revlog
	public final boolean isKnown(Nodeid nodeid) {
		final int rn = content.findLocalRevisionNumber(nodeid);
		if (Integer.MIN_VALUE == rn) {
			return false;
		}
		if (rn < 0 || rn >= content.revisionCount()) {
			// Sanity check
			throw new IllegalStateException();
		}
		return true;
	}

	/**
	 * Access to revision data as is (decompressed, but otherwise unprocessed, i.e. not parsed for e.g. changeset or manifest entries) 
	 * @param nodeid
	 */
	protected void rawContent(Nodeid nodeid, ByteChannel sink) throws HgException, IOException, CancelledException {
		rawContent(getLocalRevision(nodeid), sink);
	}
	
	/**
	 * @param revision - repo-local index of this file change (not a changelog revision number!)
	 */
	protected void rawContent(int revision, ByteChannel sink) throws HgException, IOException, CancelledException {
		if (sink == null) {
			throw new IllegalArgumentException();
		}
		ContentPipe insp = new ContentPipe(sink, 0);
		insp.checkCancelled();
		content.iterate(revision, revision, true, insp);
		insp.checkFailed();
	}

	/**
	 * XXX perhaps, return value Nodeid[2] and boolean needNodeids is better (and higher level) API for this query?
	 * 
	 * @param revision - revision to query parents, or {@link HgRepository#TIP}
	 * @param parentRevisions - int[2] to get local revision numbers of parents (e.g. {6, -1})
	 * @param parent1 - byte[20] or null, if parent's nodeid is not needed
	 * @param parent2 - byte[20] or null, if second parent's nodeid is not needed
	 * @return
	 */
	public void parents(int revision, int[] parentRevisions, byte[] parent1, byte[] parent2) {
		if (revision != TIP && !(revision >= 0 && revision < content.revisionCount())) {
			throw new IllegalArgumentException(String.valueOf(revision));
		}
		if (parentRevisions == null || parentRevisions.length < 2) {
			throw new IllegalArgumentException(String.valueOf(parentRevisions));
		}
		if (parent1 != null && parent1.length < 20) {
			throw new IllegalArgumentException(parent1.toString());
		}
		if (parent2 != null && parent2.length < 20) {
			throw new IllegalArgumentException(parent2.toString());
		}
		class ParentCollector implements RevlogStream.Inspector {
			public int p1 = -1;
			public int p2 = -1;
			public byte[] nodeid;
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
				p1 = parent1Revision;
				p2 = parent2Revision;
				this.nodeid = new byte[20];
				// nodeid arg now comes in 32 byte from (as in file format description), however upper 12 bytes are zeros.
				System.arraycopy(nodeid, nodeid.length > 20 ? nodeid.length - 20 : 0, this.nodeid, 0, 20);
			}
		};
		ParentCollector pc = new ParentCollector();
		content.iterate(revision, revision, false, pc);
		parentRevisions[0] = pc.p1;
		parentRevisions[1] = pc.p2;
		if (parent1 != null) {
			if (parentRevisions[0] == -1) {
				Arrays.fill(parent1, 0, 20, (byte) 0);
			} else {
				content.iterate(parentRevisions[0], parentRevisions[0], false, pc);
				System.arraycopy(pc.nodeid, 0, parent1, 0, 20);
			}
		}
		if (parent2 != null) {
			if (parentRevisions[1] == -1) {
				Arrays.fill(parent2, 0, 20, (byte) 0);
			} else {
				content.iterate(parentRevisions[1], parentRevisions[1], false, pc);
				System.arraycopy(pc.nodeid, 0, parent2, 0, 20);
			}
		}
	}

	/*
	 * XXX think over if it's better to do either:
	 * pw = getChangelog().new ParentWalker(); pw.init() and pass pw instance around as needed
	 * or
	 * add Revlog#getParentWalker(), static class, make cons() and #init package-local, and keep SoftReference to allow walker reuse.
	 * 
	 *  and yes, walker is not a proper name
	 */
	public final class ParentWalker {
		private Map<Nodeid, Nodeid> firstParent;
		private Map<Nodeid, Nodeid> secondParent;
		private final LinkedHashSet<Nodeid> allNodes = new LinkedHashSet<Nodeid>(); // childrenOf rely on ordering

		public ParentWalker() {
			firstParent = secondParent = Collections.emptyMap();
		}
		
		public void init() {
			final RevlogStream stream = Revlog.this.content;
			final int revisionCount = stream.revisionCount();
			firstParent = new HashMap<Nodeid, Nodeid>(revisionCount);
			secondParent = new HashMap<Nodeid, Nodeid>(firstParent.size() >> 1); // assume branches/merges are less frequent
			
			RevlogStream.Inspector insp = new RevlogStream.Inspector() {
				final Nodeid[] sequentialRevisionNodeids = new Nodeid[revisionCount];
				int ix = 0;
				public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
					if (ix != revisionNumber) {
						// XXX temp code, just to make sure I understand what's going on here
						throw new IllegalStateException();
					}
					if (parent1Revision >= revisionNumber || parent2Revision >= revisionNumber) {
						throw new IllegalStateException(); // sanity, revisions are sequential
					}
					final Nodeid nid = new Nodeid(nodeid, true);
					sequentialRevisionNodeids[ix++] = nid;
					allNodes.add(nid);
					if (parent1Revision != -1) {
						firstParent.put(nid, sequentialRevisionNodeids[parent1Revision]);
					}
					if (parent2Revision != -1) { // revlog of DataAccess.java has p2 set when p1 is -1  
						secondParent.put(nid, sequentialRevisionNodeids[parent2Revision]);
					}
				}
			};
			stream.iterate(0, -1, false, insp);
		}
		
		public Set<Nodeid> allNodes() {
			return Collections.unmodifiableSet(allNodes);
		}
		
		// FIXME need to decide whether Nodeid(00 * 20) is always known or not
		public boolean knownNode(Nodeid nid) {
			return allNodes.contains(nid);
		}

		// null if none
		public Nodeid firstParent(Nodeid nid) {
			return firstParent.get(nid);
		}

		// never null, Nodeid.NULL if none known
		public Nodeid safeFirstParent(Nodeid nid) {
			Nodeid rv = firstParent(nid);
			return rv == null ? Nodeid.NULL : rv;
		}
		
		public Nodeid secondParent(Nodeid nid) {
			return secondParent.get(nid);
		}

		public Nodeid safeSecondParent(Nodeid nid) {
			Nodeid rv = secondParent(nid);
			return rv == null ? Nodeid.NULL : rv;
		}

		public boolean appendParentsOf(Nodeid nid, Collection<Nodeid> c) {
			Nodeid p1 = firstParent(nid);
			boolean modified = false;
			if (p1 != null) {
				modified = c.add(p1);
			}
			Nodeid p2 = secondParent(nid);
			if (p2 != null) {
				modified = c.add(p2) || modified;
			}
			return modified;
		}

		// XXX alternative (and perhaps more reliable) approach would be to make a copy of allNodes and remove 
		// nodes, their parents and so on.
		
		// @return ordered collection of all children rooted at supplied nodes. Nodes shall not be descendants of each other! 
		public List<Nodeid> childrenOf(List<Nodeid> nodes) {
			HashSet<Nodeid> parents = new HashSet<Nodeid>();
			LinkedHashSet<Nodeid> result = new LinkedHashSet<Nodeid>();
			LinkedList<Nodeid> orderedResult = new LinkedList<Nodeid>();
			for(Nodeid next : allNodes) {
				// i assume allNodes is sorted, hence do not check any parents unless we hit any common known node first 
				if (nodes.contains(next)) {
					parents.add(next);
				} else {
					if (parents.isEmpty()) {
						// didn't scroll up to any known yet
						continue;
					}
					// record each and every node reported after first common known node hit  
					orderedResult.addLast(next);
					if (parents.contains(firstParent(next)) || parents.contains(secondParent(next))) {
						result.add(next);
						parents.add(next); // to find next's children
					}
				}
			}
			// leave only those of interest in ordered sequence 
			orderedResult.retainAll(result);
			return orderedResult;
		}
	}

	protected static class ContentPipe implements RevlogStream.Inspector, CancelSupport {
		private final ByteChannel sink;
		private final CancelSupport cancelSupport;
		private Exception failure;
		private final int offset;

		/**
		 * @param _sink - cannot be <code>null</code>
		 * @param seekOffset - when positive, orders to pipe bytes to the sink starting from specified offset, not from the first byte available in DataAccess
		 */
		public ContentPipe(ByteChannel _sink, int seekOffset) {
			assert _sink != null;
			sink = _sink;
			cancelSupport = CancelSupport.Factory.get(_sink);
			offset = seekOffset;
		}
		
		protected void prepare(int revisionNumber, DataAccess da) throws HgException, IOException {
			if (offset > 0) { // save few useless reset/rewind operations
				da.seek(offset);
			}
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
			try {
				prepare(revisionNumber, da); // XXX perhaps, prepare shall return DA (sliced, if needed)
				final ProgressSupport progressSupport = ProgressSupport.Factory.get(sink);
				ByteBuffer buf = ByteBuffer.allocate(512);
				progressSupport.start(da.length());
				while (!da.isEmpty()) {
					cancelSupport.checkCancelled();
					da.readBytes(buf);
					buf.flip();
					// XXX I may not rely on returned number of bytes but track change in buf position instead.
					int consumed = sink.write(buf); 
					// FIXME in fact, bad sink implementation (that consumes no bytes) would result in endless loop. Need to account for this 
					buf.compact();
					progressSupport.worked(consumed);
				}
				progressSupport.done(); // XXX shall specify whether #done() is invoked always or only if completed successfully.
			} catch (IOException ex) {
				recordFailure(ex);
			} catch (CancelledException ex) {
				recordFailure(ex);
			} catch (HgException ex) {
				recordFailure(ex);
			}
		}
		
		public void checkCancelled() throws CancelledException {
			cancelSupport.checkCancelled();
		}

		protected void recordFailure(Exception ex) {
			assert failure == null;
			failure = ex;
		}

		public void checkFailed() throws HgException, IOException, CancelledException {
			if (failure == null) {
				return;
			}
			if (failure instanceof IOException) {
				throw (IOException) failure;
			}
			if (failure instanceof CancelledException) {
				throw (CancelledException) failure;
			}
			if (failure instanceof HgException) {
				throw (HgException) failure;
			}
			throw new HgBadStateException(failure);
		}
	}
}
