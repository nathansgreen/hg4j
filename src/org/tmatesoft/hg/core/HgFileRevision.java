/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 * Keeps together information about specific file revision
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgFileRevision {
	private final HgRepository repo;
	private final Nodeid revision;
	private final Path path;
	private Path origin;
	private Boolean isCopy = null; // null means not yet known
	private Pair<Nodeid, Nodeid> parents;
	private Flags flags; // null unless set/extracted

	/**
	 * New description of a file revision from a specific repository.
	 * 
	 * <p>Although this constructor is public, and clients can use it to construct own file revisions to pass e.g. to commands, its use is discouraged.  
	 * 
	 * @param hgRepo repository
	 * @param rev file revision
	 * @param manifestEntryFlags file flags at this revision (optional, may be null) 
	 * @param p path of the file at the given revision
	 */
	public HgFileRevision(HgRepository hgRepo, Nodeid rev, HgManifest.Flags manifestEntryFlags, Path p) {
		if (hgRepo == null || rev == null || p == null) {
			// since it's package local, it is our code to blame for non validated arguments
			throw new IllegalArgumentException();
		}
		repo = hgRepo;
		revision = rev;
		flags = manifestEntryFlags;
		path = p;
	}

	// this cons shall be used when we know whether p was a copy. Perhaps, shall pass Map<Path,Path> instead to stress orig argument is not optional  
	HgFileRevision(HgRepository hgRepo, Nodeid rev, HgManifest.Flags flags, Path p, Path orig) {
		this(hgRepo, rev, flags, p);
		isCopy = Boolean.valueOf(orig == null);
		origin = orig; 
	}
	
	HgFileRevision(HgDataFile fileNode, Nodeid fileRevision, Path origin) {
		this(fileNode.getRepo(), fileRevision, null, fileNode.getPath(), origin); 
	}
	
	public Path getPath() {
		return path;
	}

	/**
	 * Revision of the file
	 * @return never <code>null</code>
	 */
	public Nodeid getRevision() {
		return revision;
	}
	
	/**
	 * Extract flags of the file as recorded in the manifest for this file revision 
	 * @return whether regular file, executable or a symbolic link
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgManifest.Flags getFileFlags() throws HgRuntimeException {
		if (flags == null) {
			/*
			 * Note, for uses other than HgManifestCommand or HgChangesetFileSneaker, when no flags come through the cons,
			 * it's possible to face next shortcoming:
			 * Imagine csetA and csetB, with corresponding manifestA and manifestB, the file didn't change (revision/nodeid is the same)
			 * but flag of the file has changed (e.g. became executable). Since HgFileRevision doesn't keep reference to 
			 * an actual manifest revision, but only file's, and it's likely the flags returned from this method would 
			 * yield result as from manifestA (i.e. no flag change in manifestB ever noticed).
			 */
			HgDataFile df = repo.getFileNode(path);
			int revIdx = df.getRevisionIndex(revision);
			flags = df.getFlags(revIdx);
		}
		return flags;
	}

	/**
	 * @return <code>true</code> if this file revision was created as a result of a copy/rename
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public boolean wasCopied() throws HgRuntimeException {
		if (isCopy == null) {
			checkCopy();
		}
		return isCopy.booleanValue();
	}
	/**
	 * @return <code>null</code> if {@link #wasCopied()} is <code>false</code>, name of the copy source otherwise.
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public Path getOriginIfCopy() throws HgRuntimeException {
		if (wasCopied()) {
			return origin;
		}
		return null;
	}

	/**
	 * Access revisions this file revision originates from.
	 * Note, these revisions are records in the file history, not that of the whole repository (aka changeset revisions) 
	 * In most cases, only one parent revision would be present, only for merge revisions one can expect both.
	 * 
	 * @return parent revisions of this file revision, with {@link Nodeid#NULL} for missing values.
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public Pair<Nodeid, Nodeid> getParents() throws HgRuntimeException {
		if (parents == null) {
			HgDataFile fn = repo.getFileNode(path);
			int revisionIndex = fn.getRevisionIndex(revision);
			int[] pr = new int[2];
			byte[] p1 = new byte[20], p2 = new byte[20];
			// XXX Revlog#parents is not the best method to use here
			// need smth that gives Nodeids (piped through Pool<Nodeid> from repo's context)
			fn.parents(revisionIndex, pr, p1, p2);
			parents = new Pair<Nodeid, Nodeid>(Nodeid.fromBinary(p1, 0), Nodeid.fromBinary(p2, 0));
		}
		return parents;
	}

	/**
	 * Pipe content of this file revision into the sink
	 * @param sink accepts file revision content
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 * @throws CancelledException if execution of the operation was cancelled
	 */
	public void putContentTo(ByteChannel sink) throws HgRuntimeException, CancelledException {
		HgDataFile fn = repo.getFileNode(path);
		int revisionIndex = fn.getRevisionIndex(revision);
		fn.contentWithFilters(revisionIndex, sink);
	}
	
	@Override
	public String toString() {
		return String.format("HgFileRevision(%s, %s)", getPath().toString(), revision.shortNotation());
	}

	private void checkCopy() throws HgRuntimeException {
		HgDataFile df = repo.getFileNode(path);
		int revIdx = df.getRevisionIndex(revision);
		if (df.isCopy(revIdx)) {
			isCopy = Boolean.TRUE;
			origin = df.getCopySource(revIdx).getPath();
			return;
		}
		isCopy = Boolean.FALSE;
	}
}
