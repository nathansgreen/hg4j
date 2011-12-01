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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgInvalidControlFileException;
import org.tmatesoft.hg.core.HgInvalidRevisionException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.Adaptable;
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

	/**
	 * Map revision index to unique revision identifier (nodeid)
	 *  
	 * @param revision index of the entry in this revlog
	 * @return revision nodeid of the entry
	 * 
	 * @throws HgInvalidRevisionException if supplied argument doesn't represent revision index in this revlog
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 */
	public final Nodeid getRevision(int revision) throws HgInvalidControlFileException, HgInvalidRevisionException {
		// XXX cache nodeids? Rather, if context.getCache(this).getRevisionMap(create == false) != null, use it
		return Nodeid.fromBinary(content.nodeid(revision), 0);
	}
	
	/**
	 * FIXME need to be careful about (1) ordering of the revisions in the return list; (2) modifications (sorting) of the argument array 
	 */
	public final List<Nodeid> getRevisions(int... revisions) throws HgInvalidRevisionException {
		ArrayList<Nodeid> rv = new ArrayList<Nodeid>(revisions.length);
		Arrays.sort(revisions);
		getRevisionsInternal(rv, revisions);
		return rv;
	}
	
	/*package-local*/ void getRevisionsInternal(final List<Nodeid> retVal, int[] sortedRevs) throws HgInvalidRevisionException {
		// once I have getRevisionMap and may find out whether it is avalable from cache,
		// may use it, perhaps only for small number of revisions
		content.iterate(sortedRevs, false, new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
				retVal.add(Nodeid.fromBinary(nodeid, 0));
			}
		});
	}

	/**
	 * Get local revision number (index) of the specified revision.
	 * If unsure, use {@link #isKnown(Nodeid)} to find out whether nodeid belongs to this revlog.
	 * 
	 * For occasional queries, this method works with decent performance, despite its O(n/2) approach.
	 * Alternatively, if you need to perform multiple queries (e.g. at least 15-20), {@link RevisionMap} may come handy.
	 * 
	 * @param nid revision to look up 
	 * @return revision local index in this revlog
	 * @throws HgInvalidRevisionException if supplied nodeid doesn't identify any revision from this revlog  
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 */
	public final int getLocalRevision(Nodeid nid) throws HgInvalidControlFileException, HgInvalidRevisionException {
		int revision = content.findLocalRevisionNumber(nid);
		if (revision == BAD_REVISION) {
			throw new HgInvalidRevisionException(String.format("Bad revision of %s", this /*XXX HgDataFile.getPath might be more suitable here*/), nid, null);
		}
		return revision;
	}

	/**
	 * Note, {@link Nodeid#NULL} nodeid is not reported as known in any revlog.
	 * 
	 * @param nodeid
	 * @return
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 */
	public final boolean isKnown(Nodeid nodeid) throws HgInvalidControlFileException {
		final int rn = content.findLocalRevisionNumber(nodeid);
		if (BAD_REVISION == rn) {
			return false;
		}
		if (rn < 0 || rn >= content.revisionCount()) {
			// Sanity check
			throw new HgBadStateException(String.format("Revision index %d found for nodeid %s is not from the range [0..%d]", rn, nodeid.shortNotation(), content.revisionCount()-1));
		}
		return true;
	}

	/**
	 * Access to revision data as is (decompressed, but otherwise unprocessed, i.e. not parsed for e.g. changeset or manifest entries) 
	 * @param nodeid
	 */
	protected void rawContent(Nodeid nodeid, ByteChannel sink) throws HgException, IOException, CancelledException, HgInvalidRevisionException {
		rawContent(getLocalRevision(nodeid), sink);
	}
	
	/**
	 * @param revision - repo-local index of this file change (not a changelog revision number!)
	 * FIXME is it necessary to have IOException along with HgException here?
	 */
	protected void rawContent(int revision, ByteChannel sink) throws HgException, IOException, CancelledException, HgInvalidRevisionException {
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
	 * @throws HgInvalidRevisionException
	 * @throws IllegalArgumentException
	 */
	public void parents(int revision, int[] parentRevisions, byte[] parent1, byte[] parent2) throws HgInvalidRevisionException {
		if (revision != TIP && !(revision >= 0 && revision < content.revisionCount())) {
			throw new HgInvalidRevisionException(revision);
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
	
	@Experimental
	public void walk(int start, int end, final Revlog.Inspector inspector) throws HgInvalidRevisionException {
		int lastRev = getLastRevision();
		if (start == TIP) {
			start = lastRev;
		}
		if (end == TIP) {
			end = lastRev;
		}
		final RevisionInspector revisionInsp = getAdapter(inspector, RevisionInspector.class);
		final ParentInspector parentInsp = getAdapter(inspector, ParentInspector.class);
		final Nodeid[] allRevisions = parentInsp == null ? null : new Nodeid[end - start + 1]; 

		content.iterate(start, end, false, new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
				Nodeid nid = Nodeid.fromBinary(nodeid, 0);
				if (revisionInsp != null) {
					revisionInsp.next(revisionNumber, nid, linkRevision);
				}
				if (parentInsp != null) {
					Nodeid p1 = parent1Revision == -1 ? Nodeid.NULL : allRevisions[parent1Revision];
					Nodeid p2 = parent2Revision == -1 ? Nodeid.NULL : allRevisions[parent2Revision];
					allRevisions[revisionNumber] = nid;
					parentInsp.next(revisionNumber, nid, parent1Revision, parent2Revision, p1, p2);
				}
			}
		});
	}
	private static <T> T getAdapter(Object o, Class<T> adapterClass) {
		if (adapterClass.isInstance(o)) {
			return adapterClass.cast(o);
		}
		if (o instanceof Adaptable) {
			return ((Adaptable) o).getAdapter(adapterClass);
		}
		return null;
	}

	/**
	 * MARKER 
	 */
	@Experimental
	public interface Inspector {
	}

	@Experimental
	public interface RevisionInspector extends Inspector {
		void next(int localRevision, Nodeid revision, int linkedRevision);
	}

	@Experimental
	public interface ParentInspector extends Inspector {
		// XXX document whether parentX is -1 or a constant (BAD_REVISION? or dedicated?)
		void next(int localRevision, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2);
	}
	
	/*
	 * XXX think over if it's better to do either:
	 * pw = getChangelog().new ParentWalker(); pw.init() and pass pw instance around as needed
	 * or
	 * add Revlog#getParentWalker(), static class, make cons() and #init package-local, and keep SoftReference to allow walker reuse.
	 * 
	 *  and yes, walker is not a proper name
	 */
	public final class ParentWalker implements ParentInspector {

		
		private Nodeid[] sequential; // natural repository order, childrenOf rely on ordering
		private Nodeid[] sorted; // for binary search
		private int[] sorted2natural;
		private Nodeid[] firstParent;
		private Nodeid[] secondParent;

		// Nodeid instances shall be shared between all arrays

		public ParentWalker() {
		}
		
		public HgRepository getRepo() {
			return Revlog.this.getRepo();
		}
		
		public void next(int revisionNumber, Nodeid revision, int parent1Revision, int parent2Revision, Nodeid nidParent1, Nodeid nidParent2) {
			if (parent1Revision >= revisionNumber || parent2Revision >= revisionNumber) {
				throw new IllegalStateException(); // sanity, revisions are sequential
			}
			int ix = revisionNumber;
			sequential[ix] = sorted[ix] = revision;
			if (parent1Revision != -1) {
				firstParent[ix] = sequential[parent1Revision];
			}
			if (parent2Revision != -1) { // revlog of DataAccess.java has p2 set when p1 is -1
				secondParent[ix] = sequential[parent2Revision];
			}
		}
		
		public void init() {
			final int revisionCount = Revlog.this.getRevisionCount();
			firstParent = new Nodeid[revisionCount];
			// although branches/merges are less frequent, and most of secondParent would be -1/null, some sort of 
			// SparseOrderedList might be handy, provided its inner structures do not overweight simplicity of an array
			// FIXME IntMap is right candidate?
			secondParent = new Nodeid[revisionCount];
			//
			sequential = new Nodeid[revisionCount];
			sorted = new Nodeid[revisionCount];
			Revlog.this.walk(0, TIP, this);
			Arrays.sort(sorted);
			sorted2natural = new int[revisionCount];
			for (int i = 0; i < revisionCount; i++) {
				Nodeid n = sequential[i];
				int x = Arrays.binarySearch(sorted, n);
				assertSortedIndex(x);
				sorted2natural[x] = i;
			}
		}
		
		private void assertSortedIndex(int x) {
			if (x < 0) {
				throw new HgBadStateException();
			}
		}
		
		// FIXME need to decide whether Nodeid(00 * 20) is always known or not
		// right now Nodeid.NULL is not recognized as known if passed to this method, 
		// caller is supposed to make explicit check 
		public boolean knownNode(Nodeid nid) {
			return Arrays.binarySearch(sorted, nid) >= 0;
		}

		/**
		 * null if none. only known nodes (as per #knownNode) are accepted as arguments
		 */
		public Nodeid firstParent(Nodeid nid) {
			int x = Arrays.binarySearch(sorted, nid);
			assertSortedIndex(x);
			int i = sorted2natural[x];
			return firstParent[i];
		}

		// never null, Nodeid.NULL if none known
		public Nodeid safeFirstParent(Nodeid nid) {
			Nodeid rv = firstParent(nid);
			return rv == null ? Nodeid.NULL : rv;
		}
		
		public Nodeid secondParent(Nodeid nid) {
			int x = Arrays.binarySearch(sorted, nid);
			assertSortedIndex(x);
			int i = sorted2natural[x];
			return secondParent[i];
		}

		public Nodeid safeSecondParent(Nodeid nid) {
			Nodeid rv = secondParent(nid);
			return rv == null ? Nodeid.NULL : rv;
		}

		public boolean appendParentsOf(Nodeid nid, Collection<Nodeid> c) {
			int x = Arrays.binarySearch(sorted, nid);
			assertSortedIndex(x);
			int i = sorted2natural[x];
			Nodeid p1 = firstParent[i];
			boolean modified = false;
			if (p1 != null) {
				modified = c.add(p1);
			}
			Nodeid p2 = secondParent[i];
			if (p2 != null) {
				modified = c.add(p2) || modified;
			}
			return modified;
		}

		// XXX alternative (and perhaps more reliable) approach would be to make a copy of allNodes and remove 
		// nodes, their parents and so on.
		
		// @return ordered collection of all children rooted at supplied nodes. Nodes shall not be descendants of each other!
		// Nodeids shall belong to this revlog
		public List<Nodeid> childrenOf(List<Nodeid> roots) {
			HashSet<Nodeid> parents = new HashSet<Nodeid>();
			LinkedList<Nodeid> result = new LinkedList<Nodeid>();
			int earliestRevision = Integer.MAX_VALUE;
			assert sequential.length == firstParent.length && firstParent.length == secondParent.length;
			// first, find earliest index of roots in question, as there's  no sense 
			// to check children among nodes prior to branch's root node
			for (Nodeid r : roots) {
				int x = Arrays.binarySearch(sorted, r);
				assertSortedIndex(x);
				int i = sorted2natural[x];
				if (i < earliestRevision) {
					earliestRevision = i;
				}
				parents.add(sequential[i]); // add canonical instance in hope equals() is bit faster when can do a ==
			}
			for (int i = earliestRevision + 1; i < sequential.length; i++) {
				if (parents.contains(firstParent[i]) || parents.contains(secondParent[i])) {
					parents.add(sequential[i]); // to find next child
					result.add(sequential[i]);
				}
			}
			return result;
		}
		
		/**
		 * @return revisions that have supplied revision as their immediate parent
		 */
		public List<Nodeid> directChildren(Nodeid nid) {
			LinkedList<Nodeid> result = new LinkedList<Nodeid>();
			int x = Arrays.binarySearch(sorted, nid);
			assertSortedIndex(x);
			nid = sorted[x]; // canonical instance
			int start = sorted2natural[x];
			for (int i = start + 1; i < sequential.length; i++) {
				if (nid == firstParent[i] || nid == secondParent[i]) {
					result.add(sequential[i]);
				}
			}
			return result;
		}
		
		/**
		 * @param nid possibly parent node, shall be {@link #knownNode(Nodeid) known} in this revlog.
		 * @return <code>true</code> if there's any node in this revlog that has specified node as one of its parents. 
		 */
		public boolean hasChildren(Nodeid nid) {
			int x = Arrays.binarySearch(sorted, nid);
			assertSortedIndex(x);
			int i = sorted2natural[x];
			assert firstParent.length == secondParent.length; // just in case later I implement sparse array for secondParent
			assert firstParent.length == sequential.length;
			// to use == instead of equals, take the same Nodeid instance we used to fill all the arrays.
			final Nodeid canonicalNode = sequential[i];
			i++; // no need to check node itself. child nodes may appear in sequential only after revision in question
			for (; i < sequential.length; i++) {
				// FIXME likely, not very effective. 
				// May want to optimize it with another (Tree|Hash)Set, created on demand on first use, 
				// however, need to be careful with memory usage
				if (firstParent[i] == canonicalNode || secondParent[i] == canonicalNode) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Effective int to Nodeid and vice versa translation. It's advised to use this class instead of 
	 * multiple {@link Revlog#getLocalRevision(Nodeid)} calls.
	 * 
	 * getLocalRevision(Nodeid) with straightforward lookup approach performs O(n/2)
	 * #localRevision() is log(n), plus initialization is O(n) 
	 */
	public final class RevisionMap implements RevisionInspector {
		/*
		 * in fact, initialization is much slower as it instantiates Nodeids, while #getLocalRevision
		 * compares directly against byte buffer. Measuring cpython with 70k+ gives 3 times difference (47 vs 171)
		 * for complete changelog iteration. 
		 */
		
		/*
		 * XXX 3 * (x * 4) bytes. Can I do better?
		 * It seems, yes. Don't need to keep sorted, always can emulate it with indirect access to sequential through sorted2natural.
		 * i.e. instead sorted[mid].compareTo(toFind), do sequential[sorted2natural[mid]].compareTo(toFind) 
		 */
		private Nodeid[] sequential; // natural repository order, childrenOf rely on ordering
		private Nodeid[] sorted; // for binary search
		private int[] sorted2natural;

		public RevisionMap() {
		}
		
		public HgRepository getRepo() {
			return Revlog.this.getRepo();
		}
		
		public void next(int localRevision, Nodeid revision, int linkedRevision) {
			sequential[localRevision] = sorted[localRevision] = revision;
		}

		/**
		 * @return <code>this</code> for convenience.
		 */
		public RevisionMap init(/*XXX Pool<Nodeid> to reuse nodeids, if possible. */) {
			// XXX HgRepository.register((RepoChangeListener) this); // listen to changes in repo, re-init if needed?
			final int revisionCount = Revlog.this.getRevisionCount();
			sequential = new Nodeid[revisionCount];
			sorted = new Nodeid[revisionCount];
			Revlog.this.walk(0, TIP, this);
			// next is alternative to Arrays.sort(sorted), and build sorted2natural looking up each element of sequential in sorted.
			// the way sorted2natural was build is O(n*log n).  
			final ArrayHelper ah = new ArrayHelper();
			ah.sort(sorted);
			// note, values in ArrayHelper#getReversed are 1-based indexes, not 0-based 
			sorted2natural = ah.getReverse();
			return this;
		}
		
		public Nodeid revision(int localRevision) {
			return sequential[localRevision];
		}
		public int localRevision(Nodeid revision) {
			if (revision == null || revision.isNull()) {
				return BAD_REVISION;
			}
			int x = Arrays.binarySearch(sorted, revision);
			if (x < 0) {
				return BAD_REVISION;
			}
			return sorted2natural[x]-1;
		}
	}

	protected abstract static class ErrorHandlingInspector implements RevlogStream.Inspector, CancelSupport {
		private Exception failure;
		private CancelSupport cancelSupport;
		
		protected void setCancelSupport(CancelSupport cs) {
			assert cancelSupport == null; // no reason to set it twice
			cancelSupport = cs;
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

		public void checkCancelled() throws CancelledException {
			if (cancelSupport != null) {
				cancelSupport.checkCancelled();
			}
		}
	}

	protected static class ContentPipe extends ErrorHandlingInspector implements RevlogStream.Inspector, CancelSupport {
		private final ByteChannel sink;
		private final int offset;

		/**
		 * @param _sink - cannot be <code>null</code>
		 * @param seekOffset - when positive, orders to pipe bytes to the sink starting from specified offset, not from the first byte available in DataAccess
		 */
		public ContentPipe(ByteChannel _sink, int seekOffset) {
			assert _sink != null;
			sink = _sink;
			setCancelSupport(CancelSupport.Factory.get(_sink));
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
					checkCancelled();
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
	}
}
