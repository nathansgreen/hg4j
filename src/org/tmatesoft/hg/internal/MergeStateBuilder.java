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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgMergeState;
import org.tmatesoft.hg.repo.HgRepositoryFiles;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 * Constructs merge/state file
 * 
 * @see HgMergeState
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class MergeStateBuilder {

	private final Internals repo;
	private final List<Record> unresolved = new ArrayList<Record>();
	private Nodeid stateParent = Nodeid.NULL;

	public MergeStateBuilder(Internals implRepo) {
		repo = implRepo;
	}
	
	public void prepare(Nodeid nodeid) {
		assert nodeid != null;
		unresolved.clear();
		stateParent = nodeid;
		abandon();
	}
	
	public void resolved() {
		throw Internals.notImplemented();
	}

	public void unresolved(Path file, HgFileRevision first, HgFileRevision second, HgFileRevision base, HgManifest.Flags flags) throws HgIOException {
		Record r = new Record(file, first.getPath(), second.getPath(), base.getPath(), base.getRevision(), flags);
		final File d = mergeStateDir();
		d.mkdirs();
		File f = new File(d, r.hash());
		try {
			FileOutputStream fos = new FileOutputStream(f);
			first.putContentTo(new OutputStreamSink(fos));
			fos.flush();
			fos.close();
			unresolved.add(r);
		} catch (IOException ex) {
			throw new HgIOException(String.format("Failed to write content of unresolved file %s to merge state at %s", file, f), f);
		} catch (CancelledException ex) {
			repo.getLog().dump(getClass(), Severity.Error, ex, "Our impl doesn't throw cancellation");
		}
	}

	// merge/state serialization is not a part of a transaction
	public void serialize() throws HgIOException {
		if (unresolved.isEmpty()) {
			return;
		}
		File mergeStateFile = repo.getRepositoryFile(HgRepositoryFiles.MergeState);
		try {
			final byte NL = '\n';
			FileOutputStream fos = new FileOutputStream(mergeStateFile);
			fos.write(stateParent.toString().getBytes());
			fos.write(NL);
			for(Record r : unresolved) {
				fos.write(r.key.toString().getBytes());
				fos.write(0);
				fos.write('u');
				fos.write(0);
				fos.write(r.hash().toString().getBytes());
				fos.write(0);
				fos.write(r.fnameA.toString().getBytes());
				fos.write(0);
				fos.write(r.fnameAncestor.toString().getBytes());
				fos.write(0);
				fos.write(r.ancestorRev.toString().getBytes());
				fos.write(0);
				fos.write(r.fnameB.toString().getBytes());
				fos.write(0);
				fos.write(r.flags.mercurialString().getBytes());
				fos.write(NL);
			}
			fos.flush();
			fos.close();
		} catch (IOException ex) {
			throw new HgIOException("Failed to serialize merge state", mergeStateFile);
		}
	}
	
	public void abandon() {
		File mergeStateDir = mergeStateDir();
		try {
			FileUtils.rmdir(mergeStateDir);
		} catch (IOException ex) {
			// ignore almost silently
			repo.getLog().dump(getClass(), Severity.Warn, ex, String.format("Failed to delete merge state in %s", mergeStateDir));
		}
	}

	private File mergeStateDir() {
		return repo.getRepositoryFile(HgRepositoryFiles.MergeState).getParentFile();
	}

	private static class Record {
		public final Path key;
		public final Path fnameA, fnameB, fnameAncestor;
		public final Nodeid ancestorRev;
		public final HgManifest.Flags flags;
		private String hash;

		public Record(Path fname, Path a, Path b, Path ancestor, Nodeid rev, HgManifest.Flags f) {
			key = fname;
			fnameA = a;
			fnameB = b;
			fnameAncestor = ancestor;
			ancestorRev = rev;
			flags = f;
		}
		
		public String hash() {
			if (hash == null) {
				hash = new DigestHelper().sha1(key).asHexString();
			}
			return hash;
		}
	}

	private static class OutputStreamSink implements ByteChannel {
		private final OutputStream out;

		public OutputStreamSink(OutputStream outputStream) {
			out = outputStream;
		}

		public int write(ByteBuffer buffer) throws IOException {
			final int toWrite = buffer.remaining();
			if (toWrite <= 0) {
				return 0;
			}
			if (buffer.hasArray()) {
				out.write(buffer.array(), buffer.arrayOffset(), toWrite);
			} else {
				while (buffer.hasRemaining()) {
					out.write(buffer.get());
				}
			}
			buffer.position(buffer.limit());
			return toWrite;
		}
	}
}
