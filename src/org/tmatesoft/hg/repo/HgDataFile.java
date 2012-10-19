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

import static org.tmatesoft.hg.repo.HgInternals.wrongRevisionIndex;
import static org.tmatesoft.hg.repo.HgRepository.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.tmatesoft.hg.core.HgChangesetFileSneaker;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.FilterByteChannel;
import org.tmatesoft.hg.internal.FilterDataAccess;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;


/**
 * Regular user data file stored in the repository.
 * 
 * <p> Note, most methods accept index in the file's revision history, not that of changelog. Easy way to obtain 
 * changeset revision index from file's is to use {@link #getChangesetRevisionIndex(int)}. To obtain file's revision 
 * index for a given changeset, {@link HgManifest#getFileRevision(int, Path)} or {@link HgChangesetFileSneaker} may 
 * come handy. 
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgDataFile extends Revlog {

	// absolute from repo root?
	// slashes, unix-style?
	// repo location agnostic, just to give info to user, not to access real storage
	private final Path path;
	private Metadata metadata; // get initialized on first access to file content.
	
	/*package-local*/HgDataFile(HgRepository hgRepo, Path filePath, RevlogStream content) {
		super(hgRepo, content);
		path = filePath;
	}

	/*package-local*/HgDataFile(HgRepository hgRepo, Path filePath) {
		super(hgRepo);
		path = filePath;
	}

	// exists is not the best name possible. now it means no file with such name was ever known to the repo.
	// it might be confused with files existed before but lately removed. TODO HgFileNode.exists makes more sense.
	// or HgDataFile.known()
	public boolean exists() {
		return content != null; // XXX need better impl
	}

	/**
	 * Human-readable file name, i.e. "COPYING", not "store/data/_c_o_p_y_i_n_g.i"
	 */
	public Path getPath() {
		return path; // hgRepo.backresolve(this) -> name? In this case, what about hashed long names?
	}

	/**
	 * Handy shorthand for {@link #getLength(int) length(getRevisionIndex(nodeid))}
	 *
	 * @param nodeid revision of the file
	 * 
	 * @return size of the file content at the given revision
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public int getLength(Nodeid nodeid) throws HgRuntimeException {
		try {
			return getLength(getRevisionIndex(nodeid));
		} catch (HgInvalidControlFileException ex) {
			throw ex.isRevisionSet() ? ex : ex.setRevision(nodeid);
		} catch (HgInvalidRevisionException ex) {
			throw ex.isRevisionSet() ? ex : ex.setRevision(nodeid);
		}
	}
	
	/**
 	 * @param fileRevisionIndex - revision local index, non-negative. From predefined constants, only {@link HgRepository#TIP} makes sense. 
	 * @return size of the file content at the revision identified by local revision number.
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public int getLength(int fileRevisionIndex) throws HgRuntimeException {
		if (wrongRevisionIndex(fileRevisionIndex) || fileRevisionIndex == BAD_REVISION) {
			throw new HgInvalidRevisionException(fileRevisionIndex);
		}
		if (fileRevisionIndex == TIP) {
			fileRevisionIndex = getLastRevision();
		} else if (fileRevisionIndex == WORKING_COPY) {
			File f = getRepo().getFile(this);
			if (f.exists()) {
				// single revision can't be greater than 2^32, shall be safe to cast to int
				return Internals.ltoi(f.length());
			}
			Nodeid fileRev = getWorkingCopyRevision();
			if (fileRev == null) {
				throw new HgInvalidRevisionException(String.format("File %s is not part of working copy", getPath()), null, fileRevisionIndex);
			}
			fileRevisionIndex = getRevisionIndex(fileRev);
		}
		if (metadata == null || !metadata.checked(fileRevisionIndex)) {
			checkAndRecordMetadata(fileRevisionIndex);
		}
		final int dataLen = content.dataLength(fileRevisionIndex);
		if (metadata.known(fileRevisionIndex)) {
			return dataLen - metadata.dataOffset(fileRevisionIndex);
		}
		return dataLen;
	}
	
	/**
	 * Reads content of the file from working directory. If file present in the working directory, its actual content without
	 * any filters is supplied through the sink. If file does not exist in the working dir, this method provides content of a file 
	 * as if it would be refreshed in the working copy, i.e. its corresponding revision (according to dirstate) is read from the 
	 * repository, and filters repo -> working copy get applied.
	 * 
	 * NOTE, if file is missing from the working directory and is not part of the dirstate (but otherwise legal repository file,
	 * e.g. from another branch), no content would be supplied.
	 *     
	 * @param sink content consumer
	 * @throws CancelledException if execution of the operation was cancelled
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void workingCopy(ByteChannel sink) throws CancelledException, HgRuntimeException {
		File f = getRepo().getFile(this);
		if (f.exists()) {
			final CancelSupport cs = CancelSupport.Factory.get(sink);
			final ProgressSupport progress = ProgressSupport.Factory.get(sink);
			final long flength = f.length();
			final int bsize = (int) Math.min(flength, 32*1024);
			progress.start((int) (flength > Integer.MAX_VALUE ? flength >>> 15 /*32 kb buf size*/ : flength));
			ByteBuffer buf = ByteBuffer.allocate(bsize);
			FileChannel fc = null;
			try {
				fc = new FileInputStream(f).getChannel();
				while (fc.read(buf) != -1) {
					cs.checkCancelled();
					buf.flip();
					int consumed = sink.write(buf);
					progress.worked(flength > Integer.MAX_VALUE ? 1 : consumed);
					buf.compact();
				}
			} catch (IOException ex) {
				throw new HgInvalidFileException("Working copy read failed", ex, f);
			} finally {
				progress.done();
				if (fc != null) {
					try {
						fc.close();
					} catch (IOException ex) {
						getRepo().getSessionContext().getLog().dump(getClass(), Warn, ex, null);
					}
				}
			}
		} else {
			Nodeid fileRev = getWorkingCopyRevision();
			if (fileRev == null) {
				// no content for this data file in the working copy - it is not part of the actual working state.
				// XXX perhaps, shall report this to caller somehow, not silently pass no data?
				return;
			}
			final int fileRevIndex = getRevisionIndex(fileRev);
			contentWithFilters(fileRevIndex, sink);
		}
	}
	
	/**
	 * @return file revision as recorded in repository manifest for dirstate parent, or <code>null</code> if no file revision can be found 
	 */
	private Nodeid getWorkingCopyRevision() throws HgInvalidControlFileException {
		final Pair<Nodeid, Nodeid> wcParents = getRepo().getWorkingCopyParents();
		Nodeid p = wcParents.first().isNull() ? wcParents.second() : wcParents.first();
		final HgChangelog clog = getRepo().getChangelog();
		final int csetRevIndex;
		if (p.isNull()) {
			// no dirstate parents 
			getRepo().getSessionContext().getLog().dump(getClass(), Info, "No dirstate parents, resort to TIP", getPath());
			// if it's a repository with no dirstate, use TIP then
			csetRevIndex = clog.getLastRevision();
			if (csetRevIndex == -1) {
				// shall not happen provided there's .i for this data file (hence at least one cset)
				// and perhaps exception is better here. However, null as "can't find" indication seems reasonable.
				return null;
			}
		} else {
			// common case to avoid searching complete changelog for nodeid match
			final Nodeid tipRev = clog.getRevision(TIP);
			if (tipRev.equals(p)) {
				csetRevIndex = clog.getLastRevision();
			} else {
				// bad luck, need to search honestly
				csetRevIndex = getRepo().getChangelog().getRevisionIndex(p);
			}
		}
		Nodeid fileRev = getRepo().getManifest().getFileRevision(csetRevIndex, getPath());
		// it's possible for a file to be in working dir and have store/.i but to belong e.g. to a different
		// branch than the one from dirstate. Thus it's possible to get null fileRev
		// which would serve as an indication this data file is not part of working copy
		return fileRev;
	}
	
	/**
	 * Access content of a file revision
	 * XXX not sure distinct method contentWithFilters() is the best way to do, perhaps, callers shall add filters themselves?
	 * 
 	 * @param fileRevisionIndex - revision local index, non-negative. From predefined constants, {@link HgRepository#TIP} and {@link HgRepository#WORKING_COPY} make sense. 
	 * @param sink content consumer
	 * 
	 * @throws CancelledException if execution of the operation was cancelled
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void contentWithFilters(int fileRevisionIndex, ByteChannel sink) throws CancelledException, HgRuntimeException {
		if (fileRevisionIndex == WORKING_COPY) {
			workingCopy(sink); // pass un-mangled sink
		} else {
			content(fileRevisionIndex, new FilterByteChannel(sink, getRepo().getFiltersFromRepoToWorkingDir(getPath())));
		}
	}

	/**
	 * Retrieve content of specific revision. Content is provided as is, without any filters (e.g. keywords, eol, etc.) applied.
	 * For filtered content, use {@link #contentWithFilters(int, ByteChannel)}. 
	 * 
 	 * @param fileRevisionIndex - revision local index, non-negative. From predefined constants, {@link HgRepository#TIP} and {@link HgRepository#WORKING_COPY} make sense. 
	 * @param sink content consumer
	 * 
	 * @throws CancelledException if execution of the operation was cancelled
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void content(int fileRevisionIndex, ByteChannel sink) throws CancelledException, HgRuntimeException {
		// for data files need to check heading of the file content for possible metadata
		// @see http://mercurial.selenic.com/wiki/FileFormats#data.2BAC8-
		if (fileRevisionIndex == TIP) {
			fileRevisionIndex = getLastRevision();
		}
		if (fileRevisionIndex == WORKING_COPY) {
			// sink is supposed to come into workingCopy without filters
			// thus we shall not get here (into #content) from #contentWithFilters(WC)
			workingCopy(sink);
			return;
		}
		if (wrongRevisionIndex(fileRevisionIndex) || fileRevisionIndex == BAD_REVISION) {
			throw new HgInvalidRevisionException(fileRevisionIndex);
		}
		if (sink == null) {
			throw new IllegalArgumentException();
		}
		if (metadata == null) {
			metadata = new Metadata();
		}
		ErrorHandlingInspector insp;
		final LogFacility lf = getRepo().getSessionContext().getLog();
		if (metadata.none(fileRevisionIndex)) {
			insp = new ContentPipe(sink, 0, lf);
		} else if (metadata.known(fileRevisionIndex)) {
			insp = new ContentPipe(sink, metadata.dataOffset(fileRevisionIndex), lf);
		} else {
			// do not know if there's metadata
			insp = new MetadataInspector(metadata, lf, new ContentPipe(sink, 0, lf));
		}
		insp.checkCancelled();
		super.content.iterate(fileRevisionIndex, fileRevisionIndex, true, insp);
		try {
			insp.checkFailed();
		} catch (HgInvalidControlFileException ex) {
			ex = ex.setFileName(getPath());
			throw ex.isRevisionIndexSet() ? ex : ex.setRevisionIndex(fileRevisionIndex);
		} catch (IOException ex) {
			HgInvalidControlFileException e = new HgInvalidControlFileException("Revision content access failed", ex, null);
			throw content.initWithIndexFile(e).setFileName(getPath()).setRevisionIndex(fileRevisionIndex);
		}
	}

	/**
	 * Walk complete change history of the file.
	 * @param inspector callback to visit changesets
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void history(HgChangelog.Inspector inspector) throws HgRuntimeException {
		history(0, getLastRevision(), inspector);
	}

	/**
	 * Walk subset of the file's change history.
	 * @param start revision local index, inclusive; non-negative or {@link HgRepository#TIP}
	 * @param end revision local index, inclusive; non-negative or {@link HgRepository#TIP}
	 * @param inspector callback to visit changesets
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void history(int start, int end, HgChangelog.Inspector inspector) throws HgRuntimeException {
		if (!exists()) {
			throw new IllegalStateException("Can't get history of invalid repository file node"); 
		}
		final int last = getLastRevision();
		if (end == TIP) {
			end = last;
		}
		if (start == TIP) {
			start = last;
		}
		HgInternals.checkRevlogRange(start, end, last);

		final int[] commitRevisions = new int[end - start + 1];
		final boolean[] needsSorting = { false };
		RevlogStream.Inspector insp = new RevlogStream.Inspector() {
			int count = 0;
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
				if (count > 0) {
					if (commitRevisions[count -1] > linkRevision) {
						needsSorting[0] = true;
					}
				}
				commitRevisions[count++] = linkRevision;
			}
		};
		content.iterate(start, end, false, insp);
		final HgChangelog changelog = getRepo().getChangelog();
		if (needsSorting[0]) {
			// automatic tools (svnmerge?) produce unnatural file history
			// (e.g. cpython/Lib/doctest.py, revision 164 points to cset 63509, 165 - to 38453) 
			Arrays.sort(commitRevisions);
		}
		changelog.rangeInternal(inspector, commitRevisions);
	}
	
	/**
	 * For a given revision of the file (identified with revision index), find out index of the corresponding changeset.
	 *
	 * @return changeset revision index
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public int getChangesetRevisionIndex(int fileRevisionIndex) throws HgRuntimeException {
		return content.linkRevision(fileRevisionIndex);
	}

	/**
	 * Complements {@link #getChangesetRevisionIndex(int)} to get changeset revision that corresponds to supplied file revision
	 * 
	 * @param nid revision of the file
	 * @return changeset revision
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public Nodeid getChangesetRevision(Nodeid nid) throws HgRuntimeException {
		int changelogRevision = getChangesetRevisionIndex(getRevisionIndex(nid));
		return getRepo().getChangelog().getRevision(changelogRevision);
	}

	/**
	 * Tells whether this file originates from another repository file
	 * @return <code>true</code> if this file is a copy of another from the repository
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public boolean isCopy() throws HgRuntimeException {
		if (metadata == null || !metadata.checked(0)) {
			checkAndRecordMetadata(0);
		}
		if (!metadata.known(0)) {
			return false;
		}
		return metadata.find(0, "copy") != null;
	}

	/**
	 * Get name of the file this one was copied from.
	 * 
	 * @return name of the file origin
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public Path getCopySourceName() throws HgRuntimeException {
		if (isCopy()) {
			return Path.create(metadata.find(0, "copy"));
		}
		throw new UnsupportedOperationException(); // XXX REVISIT, think over if Exception is good (clients would check isCopy() anyway, perhaps null is sufficient?)
	}
	
	/**
	 * 
	 * @return revision this file was copied from
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public Nodeid getCopySourceRevision() throws HgRuntimeException {
		if (isCopy()) {
			return Nodeid.fromAscii(metadata.find(0, "copyrev")); // XXX reuse/cache Nodeid
		}
		throw new UnsupportedOperationException();
	}

	/**
	 * Get file flags recorded in the manifest
 	 * @param fileRevisionIndex - revision local index, non-negative, or {@link HgRepository#TIP}. 
	 * @see HgManifest#getFileFlags(int, Path) 
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgManifest.Flags getFlags(int fileRevisionIndex) throws HgRuntimeException {
		int changesetRevIndex = getChangesetRevisionIndex(fileRevisionIndex);
		return getRepo().getManifest().getFileFlags(changesetRevIndex, getPath());
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getClass().getSimpleName());
		sb.append('(');
		sb.append(getPath());
		sb.append(')');
		return sb.toString();
	}
	
	private void checkAndRecordMetadata(int localRev) throws HgInvalidControlFileException {
		if (metadata == null) {
			metadata = new Metadata();
		}
		// use MetadataInspector without delegate to process metadata only
		RevlogStream.Inspector insp = new MetadataInspector(metadata, getRepo().getSessionContext().getLog(), null);
		super.content.iterate(localRev, localRev, true, insp);
	}

	private static final class MetadataEntry {
		private final String entry;
		private final int valueStart;

		// key may be null
		/*package-local*/MetadataEntry(String key, String value) {
			if (key == null) {
				entry = value;
				valueStart = -1; // not 0 to tell between key == null and key == ""
			} else {
				entry = key + value;
				valueStart = key.length();
			}
		}
		/*package-local*/boolean matchKey(String key) {
			return key == null ? valueStart == -1 : key.length() == valueStart && entry.startsWith(key);
		}
//		uncomment once/if needed
//		public String key() {
//			return entry.substring(0, valueStart);
//		}
		public String value() {
			return valueStart == -1 ? entry : entry.substring(valueStart);
		}
	}

	private static class Metadata {
		private static class Record {
			public final int offset;
			public final MetadataEntry[] entries;
			
			public Record(int off, MetadataEntry[] entr) {
				offset = off;
				entries = entr;
			}
		}
		// XXX sparse array needed
		private final IntMap<Record> entries = new IntMap<Record>(5);
		
		private final Record NONE = new Record(-1, null); // don't want statics

		// true when there's metadata for given revision
		boolean known(int revision) {
			Record i = entries.get(revision);
			return i != null && NONE != i;
		}

		// true when revision has been checked for metadata presence.
		public boolean checked(int revision) {
			return entries.containsKey(revision);
		}

		// true when revision has been checked and found not having any metadata
		boolean none(int revision) {
			Record i = entries.get(revision);
			return i == NONE;
		}

		// mark revision as having no metadata.
		void recordNone(int revision) {
			Record i = entries.get(revision);
			if (i == NONE) {
				return; // already there
			} 
			if (i != null) {
				throw new IllegalStateException(String.format("Trying to override Metadata state for revision %d (known offset: %d)", revision, i));
			}
			entries.put(revision, NONE);
		}

		// since this is internal class, callers are supposed to ensure arg correctness (i.e. ask known() before)
		int dataOffset(int revision) {
			return entries.get(revision).offset;
		}
		void add(int revision, int dataOffset, Collection<MetadataEntry> e) {
			assert !entries.containsKey(revision);
			entries.put(revision, new Record(dataOffset, e.toArray(new MetadataEntry[e.size()])));
		}

		String find(int revision, String key) {
			for (MetadataEntry me : entries.get(revision).entries) {
				if (me.matchKey(key)) {
					return me.value();
				}
			}
			return null;
		}
	}

	private static class MetadataInspector extends ErrorHandlingInspector implements RevlogStream.Inspector {
		private final Metadata metadata;
		private final RevlogStream.Inspector delegate;
		private final LogFacility log;

		/**
		 * @param _metadata never <code>null</code>
		 * @param logFacility log facility from the session context
		 * @param chain <code>null</code> if no further data processing other than metadata is desired
		 */
		public MetadataInspector(Metadata _metadata, LogFacility logFacility, RevlogStream.Inspector chain) {
			metadata = _metadata;
			log = logFacility;
			delegate = chain;
			setCancelSupport(CancelSupport.Factory.get(chain));
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
			try {
				final int daLength = data.length();
				if (daLength < 4 || data.readByte() != 1 || data.readByte() != 10) {
					metadata.recordNone(revisionNumber);
					data.reset();
				} else {
					ArrayList<MetadataEntry> _metadata = new ArrayList<MetadataEntry>();
					int offset = parseMetadata(data, daLength, _metadata);
					metadata.add(revisionNumber, offset, _metadata);
					// da is in prepared state (i.e. we consumed all bytes up to metadata end).
					// However, it's not safe to assume delegate won't call da.reset() for some reason,
					// and we need to ensure predictable result.
					data.reset();
					data = new FilterDataAccess(data, offset, daLength - offset);
				}
				if (delegate != null) {
					delegate.next(revisionNumber, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, nodeid, data);
				}
			} catch (IOException ex) {
				recordFailure(ex);
			} catch (HgInvalidControlFileException ex) {
				// TODO RevlogStream, where this RevlogStream.Inspector goes, shall set File (as it's the only one having access to it)
				recordFailure(ex.isRevisionIndexSet() ? ex : ex.setRevisionIndex(revisionNumber));
			}
		}

		private int parseMetadata(DataAccess data, final int daLength, ArrayList<MetadataEntry> _metadata) throws IOException, HgInvalidControlFileException {
			int lastEntryStart = 2;
			int lastColon = -1;
			// XXX in fact, need smth like ByteArrayBuilder, similar to StringBuilder,
			// which can't be used here because we can't convert bytes to chars as we read them
			// (there might be multi-byte encoding), and we need to collect all bytes before converting to string 
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			String key = null, value = null;
			boolean byteOne = false;
			boolean metadataIsComplete = false;
			for (int i = 2; i < daLength; i++) {
				byte b = data.readByte();
				if (b == '\n') {
					if (byteOne) { // i.e. \n follows 1
						lastEntryStart = i+1;
						metadataIsComplete = true;
						// XXX is it possible to have here incomplete key/value (i.e. if last pair didn't end with \n)
						// if yes, need to set metadataIsComplete to true in that case as well
						break;
					}
					if (key == null || lastColon == -1 || i <= lastColon) {
						log.dump(getClass(), Error, "Missing key in file revision metadata at index %d", i);
					}
					value = new String(bos.toByteArray()).trim();
					bos.reset();
					_metadata.add(new MetadataEntry(key, value));
					key = value = null;
					lastColon = -1;
					lastEntryStart = i+1;
					continue;
				} 
				// byteOne has to be consumed up to this line, if not yet, consume it
				if (byteOne) {
					// insert 1 we've read on previous step into the byte builder
					bos.write(1);
					byteOne = false;
					// fall-through to consume current byte
				}
				if (b == (int) ':') {
					assert value == null;
					key = new String(bos.toByteArray());
					bos.reset();
					lastColon = i;
				} else if (b == 1) {
					byteOne = true;
				} else {
					bos.write(b);
				}
			}
			// data.isEmpty is not reliable, renamed files of size==0 keep only metadata
			if (!metadataIsComplete) {
				// XXX perhaps, worth a testcase (empty file, renamed, read or ask ifCopy
				throw new HgInvalidControlFileException("Metadata is not closed properly", null, null);
			}
			return lastEntryStart;
		}

		@Override
		public void checkFailed() throws HgRuntimeException, IOException, CancelledException {
			super.checkFailed();
			if (delegate instanceof ErrorHandlingInspector) {
				// TODO need to add ErrorDestination (ErrorTarget/Acceptor?) and pass it around (much like CancelSupport get passed)
				// so that delegate would be able report its failures directly to caller without this hack
				((ErrorHandlingInspector) delegate).checkFailed();
			}
		}
	}
}
