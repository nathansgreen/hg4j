/*
 * Copyright (c) 2010-2012 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepository.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Preview;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.LogFacility;
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

	/**
	 * @return total number of revisions kept in this revlog
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public final int getRevisionCount() throws HgRuntimeException {
		return content.revisionCount();
	}
	
	/**
	 * @return index of last known revision, a.k.a. {@link HgRepository#TIP}
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public final int getLastRevision() throws HgRuntimeException {
		return content.revisionCount() - 1;
	}

	/**
	 * Map revision index to unique revision identifier (nodeid).
	 *  
	 * @param revisionIndex index of the entry in this revlog, may be {@link HgRepository#TIP}
	 * @return revision nodeid of the entry
	 * 
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public final Nodeid getRevision(int revisionIndex) throws HgRuntimeException {
		// XXX cache nodeids? Rather, if context.getCache(this).getRevisionMap(create == false) != null, use it
		return Nodeid.fromBinary(content.nodeid(revisionIndex), 0);
	}
	
	/**
	 * Effective alternative to map few revision indexes to corresponding nodeids at once.
	 * <p>Note, there are few aspects to be careful about when using this method<ul>
	 * <li>ordering of the revisions in the return list is unspecified, it's likely won't match that of the method argument
	 * <li>supplied array get modified (sorted)</ul>
	 * @return list of mapped revisions in no particular order
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public final List<Nodeid> getRevisions(int... revisions) throws HgRuntimeException {
		ArrayList<Nodeid> rv = new ArrayList<Nodeid>(revisions.length);
		Arrays.sort(revisions);
		getRevisionsInternal(rv, revisions);
		return rv;
	}
	
	/*package-local*/ void getRevisionsInternal(final List<Nodeid> retVal, int[] sortedRevs) throws HgInvalidRevisionException, HgInvalidControlFileException {
		// once I have getRevisionMap and may find out whether it is avalable from cache,
		// may use it, perhaps only for small number of revisions
		content.iterate(sortedRevs, false, new RevlogStream.Inspector() {
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
				retVal.add(Nodeid.fromBinary(nodeid, 0));
			}
		});
	}

	/**
	 * Get local index of the specified revision.
	 * If unsure, use {@link #isKnown(Nodeid)} to find out whether nodeid belongs to this revlog.
	 * 
	 * For occasional queries, this method works with decent performance, despite its O(n/2) approach.
	 * Alternatively, if you need to perform multiple queries (e.g. at least 15-20), {@link HgRevisionMap} may come handy.
	 * 
	 * @param nid revision to look up 
	 * @return revision local index in this revlog
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public final int getRevisionIndex(Nodeid nid) throws HgRuntimeException {
		int revision = content.findRevisionIndex(nid);
		if (revision == BAD_REVISION) {
			// using toString() to identify revlog. HgDataFile.toString includes path, HgManifest and HgChangelog instances 
			// are fine with default (class name)
			// Perhaps, more tailored description method would be suitable here
			throw new HgInvalidRevisionException(String.format("Can't find revision %s in %s", nid.shortNotation(), this), nid, null);
		}
		return revision;
	}
	
	/**
	 * Note, {@link Nodeid#NULL} nodeid is not reported as known in any revlog.
	 * 
	 * @param nodeid
	 * @return <code>true</code> if revision is part of this revlog
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public final boolean isKnown(Nodeid nodeid) throws HgRuntimeException {
		final int rn = content.findRevisionIndex(nodeid);
		if (BAD_REVISION == rn) {
			return false;
		}
		if (rn < 0 || rn >= content.revisionCount()) {
			// Sanity check
			throw new HgInvalidStateException(String.format("Revision index %d found for nodeid %s is not from the range [0..%d]", rn, nodeid.shortNotation(), content.revisionCount()-1));
		}
		return true;
	}

	/**
	 * Access to revision data as is, equivalent to <code>rawContent(getRevisionIndex(nodeid), sink)</code>
	 * 
	 * @param nodeid revision to retrieve
	 * @param sink data destination
	 * 
	 * @throws HgInvalidRevisionException if supplied argument doesn't represent revision index in this revlog
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 * @throws CancelledException if content retrieval operation was cancelled
	 * 
	 * @see #rawContent(int, ByteChannel)
	 */
	protected void rawContent(Nodeid nodeid, ByteChannel sink) throws HgInvalidControlFileException, CancelledException, HgInvalidRevisionException {
		rawContent(getRevisionIndex(nodeid), sink);
	}
	
	/**
	 * Access to revision data as is (decompressed, but otherwise unprocessed, i.e. not parsed for e.g. changeset or manifest entries).
	 *  
	 * @param revisionIndex index of this revlog change (not a changelog revision index), non-negative. From predefined constants, only {@link HgRepository#TIP} makes sense.
	 * @param sink data destination
	 * 
	 * @throws HgInvalidRevisionException if supplied argument doesn't represent revision index in this revlog
	 * @throws HgInvalidControlFileException if access to revlog index/data entry failed
	 * @throws CancelledException if content retrieval operation was cancelled
	 */
	protected void rawContent(int revisionIndex, ByteChannel sink) throws HgInvalidControlFileException, CancelledException, HgInvalidRevisionException {
		if (sink == null) {
			throw new IllegalArgumentException();
		}
		try {
			ContentPipe insp = new ContentPipe(sink, 0, repo.getContext().getLog());
			insp.checkCancelled();
			content.iterate(revisionIndex, revisionIndex, true, insp);
			insp.checkFailed();
		} catch (IOException ex) {
			HgInvalidControlFileException e = new HgInvalidControlFileException(String.format("Access to revision %d content failed", revisionIndex), ex, null);
			e.setRevisionIndex(revisionIndex);
			// TODO post 1.0 e.setFileName(content.getIndexFile() or this.getHumanFriendlyPath()) - shall decide whether 
			// protected abstract getHFPath() with impl in HgDataFile, HgManifest and HgChangelog or path is data of either Revlog or RevlogStream
			// Do the same (add file name) below
			throw e;
		} catch (HgInvalidControlFileException ex) {
			throw ex.isRevisionIndexSet() ? ex : ex.setRevisionIndex(revisionIndex);
		}
	}

	/**
	 * Fills supplied arguments with information about revision parents.
	 * 
	 * @param revision - revision to query parents, or {@link HgRepository#TIP}
	 * @param parentRevisions - int[2] to get local revision numbers of parents (e.g. {6, -1}), {@link HgRepository#NO_REVISION} indicates parent not set
	 * @param parent1 - byte[20] or null, if parent's nodeid is not needed
	 * @param parent2 - byte[20] or null, if second parent's nodeid is not needed
	 * @throws IllegalArgumentException if passed arrays can't fit requested data
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void parents(int revision, int[] parentRevisions, byte[] parent1, byte[] parent2) throws HgRuntimeException, IllegalArgumentException {
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
		// although next code looks odd (NO_REVISION *is* -1), it's safer to be explicit
		parentRevisions[0] = pc.p1 == -1 ? NO_REVISION : pc.p1;
		parentRevisions[1] = pc.p2 == -1 ? NO_REVISION : pc.p2;
		if (parent1 != null) {
			if (parentRevisions[0] == NO_REVISION) {
				Arrays.fill(parent1, 0, 20, (byte) 0);
			} else {
				content.iterate(parentRevisions[0], parentRevisions[0], false, pc);
				System.arraycopy(pc.nodeid, 0, parent1, 0, 20);
			}
		}
		if (parent2 != null) {
			if (parentRevisions[1] == NO_REVISION) {
				Arrays.fill(parent2, 0, 20, (byte) 0);
			} else {
				content.iterate(parentRevisions[1], parentRevisions[1], false, pc);
				System.arraycopy(pc.nodeid, 0, parent2, 0, 20);
			}
		}
	}

	/**
	 * EXPERIMENTAL CODE, DO NOT USE
	 * 
	 * Alternative revlog iteration
	 * 
	 * @param start
	 * @param end
	 * @param inspector
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	@Experimental
	public void indexWalk(int start, int end, final Revlog.Inspector inspector) throws HgRuntimeException {
		int lastRev = getLastRevision();
		if (start == TIP) {
			start = lastRev;
		}
		if (end == TIP) {
			end = lastRev;
		}
		final RevisionInspector revisionInsp = Adaptable.Factory.getAdapter(inspector, RevisionInspector.class, null);
		final ParentInspector parentInsp = Adaptable.Factory.getAdapter(inspector, ParentInspector.class, null);
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

	/**
	 * MARKER 
	 */
	@Experimental
	public interface Inspector {
	}

	@Experimental
	public interface RevisionInspector extends Inspector {
		void next(int revisionIndex, Nodeid revision, int linkedRevisionIndex);
	}

	@Experimental
	public interface ParentInspector extends Inspector {
		// XXX document whether parentX is -1 or a constant (BAD_REVISION? or dedicated?)
		void next(int revisionIndex, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2);
	}
	
	protected HgParentChildMap<? extends Revlog> getParentWalker() {
		HgParentChildMap<Revlog> pw = new HgParentChildMap<Revlog>(this);
		pw.init();
		return pw;
	}

	/*
	 * class with cancel and few other exceptions support. TODO consider general superclass to share with e.g. HgManifestCommand.Mediator
	 */
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

		public void checkFailed() throws HgRuntimeException, IOException, CancelledException {
			if (failure == null) {
				return;
			}
			if (failure instanceof IOException) {
				throw (IOException) failure;
			}
			if (failure instanceof CancelledException) {
				throw (CancelledException) failure;
			}
			if (failure instanceof HgRuntimeException) {
				throw (HgRuntimeException) failure;
			}
			throw new HgInvalidStateException(failure.toString());
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
		private final LogFacility logFacility;

		/**
		 * @param _sink - cannot be <code>null</code>
		 * @param seekOffset - when positive, orders to pipe bytes to the sink starting from specified offset, not from the first byte available in DataAccess
		 * @param log optional facility to put warnings/debug messages into, may be null.
		 */
		public ContentPipe(ByteChannel _sink, int seekOffset, LogFacility log) {
			assert _sink != null;
			sink = _sink;
			setCancelSupport(CancelSupport.Factory.get(_sink));
			offset = seekOffset;
			logFacility = log;
		}
		
		protected void prepare(int revisionNumber, DataAccess da) throws IOException {
			if (offset > 0) { // save few useless reset/rewind operations
				da.seek(offset);
			}
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
			try {
				prepare(revisionNumber, da); // XXX perhaps, prepare shall return DA (sliced, if needed)
				final ProgressSupport progressSupport = ProgressSupport.Factory.get(sink);
				ByteBuffer buf = ByteBuffer.allocate(actualLen > 8192 ? 8192 : actualLen);
				Preview p = Adaptable.Factory.getAdapter(sink, Preview.class, null);
				if (p != null) {
					progressSupport.start(2 * da.length());
					while (!da.isEmpty()) {
						checkCancelled();
						da.readBytes(buf);
						p.preview(buf);
						buf.clear();
					}
					da.reset();
					prepare(revisionNumber, da);
					progressSupport.worked(da.length());
					buf.clear();
				} else {
					progressSupport.start(da.length());
				}
				while (!da.isEmpty()) {
					checkCancelled();
					da.readBytes(buf);
					buf.flip(); // post: position == 0
					// XXX I may not rely on returned number of bytes but track change in buf position instead.
					
					int consumed = sink.write(buf);
					if ((consumed == 0 || consumed != buf.position()) && logFacility != null) {
						logFacility.dump(getClass(), Warn, "Bad data sink when reading revision %d. Reported %d bytes consumed, byt actually read %d", revisionNumber, consumed, buf.position());
					}
					if (buf.position() == 0) {
						throw new HgInvalidStateException("Bad sink implementation (consumes no bytes) results in endless loop");
					}
					buf.compact(); // ensure (a) there's space for new (b) data starts at 0
					progressSupport.worked(consumed);
				}
				progressSupport.done(); // XXX shall specify whether #done() is invoked always or only if completed successfully.
			} catch (IOException ex) {
				recordFailure(ex);
			} catch (CancelledException ex) {
				recordFailure(ex);
			}
		}
	}
}
