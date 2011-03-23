/*
 * Copyright (c) 2011 TMate Software Ltd
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
package org.tmatesoft.hg.console;

import static org.tmatesoft.hg.core.Nodeid.NULL;
import static org.tmatesoft.hg.internal.RequiresFile.*;
import static org.tmatesoft.hg.internal.RequiresFile.DOTENCODE;
import static org.tmatesoft.hg.internal.RequiresFile.FNCACHE;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayDataAccess;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.RequiresFile;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgBundle.GroupElement;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * WORK IN PROGRESS, DO NOT USE
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Clone {
/*
 * Changegroup: 
 * http://mercurial.selenic.com/wiki/Merge 
 * http://mercurial.selenic.com/wiki/WireProtocol 
 * 
 * according to latter, bundleformat data is sent through zlib
 * (there's no header like HG10?? with the server output, though, 
 * as one may expect according to http://mercurial.selenic.com/wiki/BundleFormat)
 */
	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepoFacade hgRepo = new HgRepoFacade();
		if (!hgRepo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getRepository().getLocation());
			return;
		}
		File destDir = new File("/temp/hg/clone-01/");
		if (destDir.exists()) {
			if (!destDir.isDirectory()) {
				throw new IllegalArgumentException();
			} else if (destDir.list().length > 0) {
				throw new IllegalArgumentException();
			}
		} else {
			destDir.mkdirs();
		}
		// if cloning remote repo, which can stream and no revision is specified -
		// can use 'stream_out' wireproto
		//
		// //////// 1. from Remote.java take code that asks changegroup from remote server and write it down to temp file
		// //////// 2. then, read the file with HgBundle
		// //////// 3. process changelog, memorize nodeids to index
		// //////// 4. process manifest, using map from step 3, collect manifest nodeids
		// //////// 5. process every file, using map from 3, and consult set from step 4 to ensure repo is correct
		// access source
		HgRemoteRepository remoteRepo = new HgRemoteRepository();// new HgLookup().detect(new URL("https://asd/hg/"));
		// discover changes
		HgBundle completeChanges = remoteRepo.getChanges(Collections.singletonList(NULL));
		WriteDownMate mate = new WriteDownMate(destDir);
		// instantiate new repo in the destdir
		mate.initEmptyRepository();
		// pull changes
		completeChanges.inspectAll(mate);
		mate.complete();
		// completeChanges.unlink();
	}

	private static class WriteDownMate implements HgBundle.Inspector {
		private final File hgDir;
		private FileOutputStream indexFile;
		private final PathRewrite storagePathHelper;

		private final TreeMap<Nodeid, Integer> changelogIndexes = new TreeMap<Nodeid, Integer>();
		private boolean collectChangelogIndexes = false;

		private int base = -1;
		private long offset = 0;
		private DataAccess prevRevContent;
		private final DigestHelper dh = new DigestHelper();
		private final ArrayList<Nodeid> revisionSequence = new ArrayList<Nodeid>(); // last visited nodes first

		private final LinkedList<String> fncacheFiles = new LinkedList<String>();

		public WriteDownMate(File destDir) {
			hgDir = new File(destDir, ".hg");
			Internals i = new Internals();
			i.setStorageConfig(1, STORE | FNCACHE | DOTENCODE);
			storagePathHelper = i.buildDataFilesHelper();
		}

		public void initEmptyRepository() throws IOException {
			hgDir.mkdir();
			FileOutputStream requiresFile = new FileOutputStream(new File(hgDir, "requires"));
			requiresFile.write("revlogv1\nstore\nfncache\ndotencode\n".getBytes());
			requiresFile.close();
			new File(hgDir, "store").mkdir(); // with that, hg verify says ok.
		}

		public void complete() throws IOException {
			FileOutputStream fncacheFile = new FileOutputStream(new File(hgDir, "store/fncache"));
			for (String s : fncacheFiles) {
				fncacheFile.write(s.getBytes());
				fncacheFile.write(0x0A); // http://mercurial.selenic.com/wiki/fncacheRepoFormat
			}
			fncacheFile.close();
		}

		public void changelogStart() {
			try {
				base = -1;
				offset = 0;
				revisionSequence.clear();
				indexFile = new FileOutputStream(new File(hgDir, "store/00changelog.i"));
				collectChangelogIndexes = true;
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}

		public void changelogEnd() {
			try {
				if (prevRevContent != null) {
					prevRevContent.done();
					prevRevContent = null;
				}
				collectChangelogIndexes = false;
				indexFile.close();
				indexFile = null;
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}

		public void manifestStart() {
			try {
				base = -1;
				offset = 0;
				revisionSequence.clear();
				indexFile = new FileOutputStream(new File(hgDir, "store/00manifest.i"));
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}

		public void manifestEnd() {
			try {
				if (prevRevContent != null) {
					prevRevContent.done();
					prevRevContent = null;
				}
				indexFile.close();
				indexFile = null;
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}
		
		public void fileStart(String name) {
			try {
				base = -1;
				offset = 0;
				revisionSequence.clear();
				fncacheFiles.add("data/" + name + ".i"); // FIXME this is pure guess, 
				// need to investigate more how filenames are kept in fncache
				File file = new File(hgDir, storagePathHelper.rewrite(name));
				file.getParentFile().mkdirs();
				indexFile = new FileOutputStream(file);
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}

		public void fileEnd(String name) {
			try {
				if (prevRevContent != null) {
					prevRevContent.done();
					prevRevContent = null;
				}
				indexFile.close();
				indexFile = null;
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
		}

		private int knownRevision(Nodeid p) {
			if (NULL.equals(p)) {
				return -1;
			} else {
				for (int i = revisionSequence.size() - 1; i >= 0; i--) {
					if (revisionSequence.get(i).equals(p)) {
						return i;
					}
				}
			}
			throw new HgBadStateException(String.format("Can't find index of %s", p.shortNotation()));
		}

		public boolean element(GroupElement ge) {
			try {
				assert indexFile != null;
				boolean writeComplete = false;
				Nodeid p1 = ge.firstParent();
				Nodeid p2 = ge.secondParent();
				if (NULL.equals(p1) && NULL.equals(p2) /* or forced flag, does REVIDX_PUNCHED_FLAG indicate that? */) {
					prevRevContent = new ByteArrayDataAccess(new byte[0]);
					writeComplete = true;
				}
				byte[] content = ge.apply(prevRevContent);
				byte[] calculated = dh.sha1(p1, p2, content).asBinary();
				final Nodeid node = ge.node();
				if (!node.equalsTo(calculated)) {
					throw new HgBadStateException("Checksum failed");
				}
				final int link;
				if (collectChangelogIndexes) {
					changelogIndexes.put(node, revisionSequence.size());
					link = revisionSequence.size();
				} else {
					Integer csRev = changelogIndexes.get(ge.cset());
					if (csRev == null) {
						throw new HgBadStateException(String.format("Changelog doesn't contain revision %s", ge.cset().shortNotation()));
					}
					link = csRev.intValue();
				}
				final int p1Rev = knownRevision(p1), p2Rev = knownRevision(p2);
				DataAccess patchContent = ge.rawData();
				writeComplete = writeComplete || patchContent.length() >= (/* 3/4 of actual */content.length - (content.length >>> 2));
				if (writeComplete) {
					base = revisionSequence.size();
				}
				final byte[] sourceData = writeComplete ? content : patchContent.byteArray();
				final byte[] data;
				ByteArrayOutputStream bos = new ByteArrayOutputStream(content.length);
				DeflaterOutputStream dos = new DeflaterOutputStream(bos);
				dos.write(sourceData);
				dos.close();
				final byte[] compressedData = bos.toByteArray();
				dos = null;
				bos = null;
				final Byte dataPrefix;
				if (compressedData.length >= (sourceData.length - (sourceData.length >>> 2))) {
					// compression wasn't too effective,
					data = sourceData;
					dataPrefix = 'u';
				} else {
					data = compressedData;
					dataPrefix = null;
				}

				ByteBuffer header = ByteBuffer.allocate(64 /* REVLOGV1_RECORD_SIZE */);
				if (offset == 0) {
					final int INLINEDATA = 1 << 16;
					header.putInt(1 /* RevlogNG */ | INLINEDATA);
					header.putInt(0);
				} else {
					header.putLong(offset << 16);
				}
				final int compressedLen = data.length + (dataPrefix == null ? 0 : 1);
				header.putInt(compressedLen);
				header.putInt(content.length);
				header.putInt(base);
				header.putInt(link);
				header.putInt(p1Rev);
				header.putInt(p2Rev);
				header.put(node.toByteArray());
				// assume 12 bytes left are zeros
				indexFile.write(header.array());
				if (dataPrefix != null) {
					indexFile.write(dataPrefix.byteValue());
				}
				indexFile.write(data);
				//
				offset += compressedLen;
				revisionSequence.add(node);
				prevRevContent.done();
				prevRevContent = new ByteArrayDataAccess(content);
			} catch (IOException ex) {
				throw new HgBadStateException(ex);
			}
			return true;
		}
	}
}
