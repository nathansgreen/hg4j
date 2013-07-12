/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.core.Nodeid.NULL;
import static org.tmatesoft.hg.internal.RequiresFile.*;
import static org.tmatesoft.hg.internal.RevlogStreamWriter.preferCompleteOverPatch;
import static org.tmatesoft.hg.internal.RevlogStreamWriter.preferCompressedOverComplete;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;

import org.tmatesoft.hg.internal.ByteArrayDataAccess;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DataSerializer;
import org.tmatesoft.hg.internal.DataSerializer.ByteArrayDataSource;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.FNCacheFile;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.Patch;
import org.tmatesoft.hg.internal.RepoInitializer;
import org.tmatesoft.hg.internal.RevlogCompressor;
import org.tmatesoft.hg.internal.RevlogStreamWriter;
import org.tmatesoft.hg.internal.Transaction;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgBundle.GroupElement;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * WORK IN PROGRESS, DO NOT USE
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgCloneCommand extends HgAbstractCommand<HgCloneCommand> {

	private File destination;
	private HgRemoteRepository srcRepo;

	public HgCloneCommand() {
	}
	
	/**
	 * @param folder location to become root of the repository (i.e. where <em>.hg</em> folder would reside). Either 
	 * shall not exist or be empty otherwise. 
	 * @return <code>this</code> for convenience
	 */
	public HgCloneCommand destination(File folder) {
		destination = folder;
		return this;
	}

	public HgCloneCommand source(HgRemoteRepository hgRemote) {
		srcRepo = hgRemote;
		return this;
	}

	/**
	 * 
	 * @return
	 * @throws HgBadArgumentException
	 * @throws HgRemoteConnectionException
	 * @throws HgRepositoryNotFoundException
	 * @throws HgException
	 * @throws CancelledException
	 */
	public HgRepository execute() throws HgException, CancelledException {
		if (destination == null) {
			throw new IllegalArgumentException("Destination not set", null);
		}
		if (srcRepo == null || srcRepo.isInvalid()) {
			throw new HgBadArgumentException("Bad source repository", null);
		}
		if (destination.exists()) {
			if (!destination.isDirectory()) {
				throw new HgBadArgumentException(String.format("%s is not a directory", destination), null);
			} else if (destination.list().length > 0) {
				throw new HgBadArgumentException(String.format("%s shall be empty", destination), null);
			}
		} else {
			destination.mkdirs();
		}
		ProgressSupport progress = getProgressSupport(null);
		CancelSupport cancel = getCancelSupport(null, true);
		cancel.checkCancelled();
		// if cloning remote repo, which can stream and no revision is specified -
		// can use 'stream_out' wireproto
		//
		try {
			// pull all changes from the very beginning
			// XXX consult getContext() if by any chance has a bundle ready, if not, then read and register
			HgBundle completeChanges = srcRepo.getChanges(Collections.singletonList(NULL));
			cancel.checkCancelled();
			WriteDownMate mate = new WriteDownMate(srcRepo.getSessionContext(), destination, progress, cancel);
			try {
				// instantiate new repo in the destdir
				mate.initEmptyRepository();
				// pull changes
				completeChanges.inspectAll(mate);
				mate.checkFailure();
				mate.complete();
			} finally {
				completeChanges.unlink();
				progress.done();
			}
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
		return new HgLookup().detect(destination);
	}


	// 1. process changelog, memorize nodeids to index
	// 2. process manifest, using map from step 3, collect manifest nodeids
	// 3. process every file, using map from 3, and consult set from step 4 to ensure repo is correct
	private static class WriteDownMate implements HgBundle.Inspector, Lifecycle {
		private final File hgDir;
		private final PathRewrite storagePathHelper;
		private final ProgressSupport progressSupport;
		private final CancelSupport cancelSupport;
		private final SessionContext ctx;
		private final Path.Source pathFactory;
		private FileOutputStream indexFile;
		private File currentFile;
		private String filename; // human-readable name of the file being written, for log/exception purposes 

		private final TreeMap<Nodeid, Integer> changelogIndexes = new TreeMap<Nodeid, Integer>();
		private boolean collectChangelogIndexes = false;

		private DataAccess prevRevContent;
		private final DigestHelper dh = new DigestHelper();
		// recently processed nodes last, so that index in the array may be used as a linkRevision or baseRevision
		private final ArrayList<Nodeid> revisionSequence = new ArrayList<Nodeid>();

		private FNCacheFile fncacheFile;
		private RepoInitializer repoInit;
		private Lifecycle.Callback lifecycleCallback;
		private CancelledException cancelException;

		private RevlogStreamWriter.HeaderWriter revlogHeader = new RevlogStreamWriter.HeaderWriter(true);
		private RevlogCompressor revlogDataZip;

		public WriteDownMate(SessionContext sessionCtx, File destDir, ProgressSupport progress, CancelSupport cancel) {
			ctx = sessionCtx;
			hgDir = new File(destDir, ".hg");
			repoInit = new RepoInitializer();
			repoInit.setRequires(REVLOGV1 | STORE | FNCACHE | DOTENCODE);
			storagePathHelper = repoInit.buildDataFilesHelper(sessionCtx);
			progressSupport = progress;
			cancelSupport = cancel;
			revlogDataZip = new RevlogCompressor(sessionCtx);
			pathFactory = ctx.getPathFactory();
		}

		public void initEmptyRepository() throws HgIOException, HgRepositoryNotFoundException {
			repoInit.initEmptyRepository(hgDir);
			assert (repoInit.getRequires() & FNCACHE) != 0;
			// XXX perhaps, with WriteDownMate moving to a more appropriate location,
			// we could instantiate HgRepository (or Internals) by other means, without exception?
			fncacheFile = new FNCacheFile(Internals.getInstance(new HgLookup(ctx).detect(hgDir)));
		}

		public void complete() throws HgIOException {
			fncacheFile.write(new Transaction.NoRollback());
		}

		public void changelogStart() throws HgInvalidControlFileException {
			try {
				revlogHeader.offset(0).baseRevision(-1);
				revisionSequence.clear();
				indexFile = new FileOutputStream(currentFile = new File(hgDir, filename = "store/00changelog.i"));
				collectChangelogIndexes = true;
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Failed to write changelog", ex, new File(hgDir, filename));
			}
			stopIfCancelled();
		}

		public void changelogEnd() throws HgInvalidControlFileException {
			try {
				clearPreviousContent();
				collectChangelogIndexes = false;
				closeIndexFile();
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Failed to write changelog", ex, new File(hgDir, filename));
			}
			progressSupport.worked(1);
			stopIfCancelled();
		}

		public void manifestStart() throws HgInvalidControlFileException {
			try {
				revlogHeader.offset(0).baseRevision(-1);
				revisionSequence.clear();
				indexFile = new FileOutputStream(currentFile = new File(hgDir, filename = "store/00manifest.i"));
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Failed to write manifest", ex, new File(hgDir, filename));
			}
			stopIfCancelled();
		}

		public void manifestEnd() throws HgInvalidControlFileException {
			try {
				clearPreviousContent();
				closeIndexFile();
			} catch (IOException ex) {
				throw new HgInvalidControlFileException("Failed to write manifest", ex, new File(hgDir, filename));
			}
			progressSupport.worked(1);
			stopIfCancelled();
		}
		
		public void fileStart(String name) throws HgInvalidControlFileException {
			try {
				revlogHeader.offset(0).baseRevision(-1);
				revisionSequence.clear();
				File file = new File(hgDir, filename = storagePathHelper.rewrite(name).toString());
				file.getParentFile().mkdirs();
				indexFile = new FileOutputStream(currentFile = file);
			} catch (IOException ex) {
				String m = String.format("Failed to write file %s", filename);
				throw new HgInvalidControlFileException(m, ex, new File(filename));
			}
			stopIfCancelled();
		}

		public void fileEnd(String name) throws HgInvalidControlFileException {
			try {
				fncacheFile.addIndex(pathFactory.path(name)); 
				clearPreviousContent();
				closeIndexFile();
			} catch (IOException ex) {
				String m = String.format("Failed to write file %s", filename);
				throw new HgInvalidControlFileException(m, ex, new File(filename));
			}
			progressSupport.worked(1);
			stopIfCancelled();
		}
		
		private void clearPreviousContent() {
			if (prevRevContent != null) {
				prevRevContent.done();
				prevRevContent = null;
			}
		}
		
		private void closeIndexFile() throws IOException {
			indexFile.close();
			indexFile = null;
			filename = null;
			currentFile = null;
		}

		private int knownRevision(Nodeid p) throws HgInvalidControlFileException {
			if (p.isNull()) {
				return -1;
			} else {
				for (int i = revisionSequence.size() - 1; i >= 0; i--) {
					if (revisionSequence.get(i).equals(p)) {
						return i;
					}
				}
			}
			String m = String.format("Can't find index of %s for file %s", p.shortNotation(), filename);
			throw new HgInvalidControlFileException(m, null, new File(hgDir, filename)).setRevision(p);
		}
		
		public boolean element(GroupElement ge) throws HgRuntimeException {
			try {
				assert indexFile != null;
				boolean writeComplete = false;
				Nodeid deltaBase = ge.patchBase();
				if (deltaBase.isNull()) {
					// NOTE, can't use both parents isNull == true to empty prevRevContent
					// see build.gradle sample below why.
					prevRevContent = new DataAccess(); // empty data
					writeComplete = true;
					// if (writeComplete) would set baseRevision correctly,
				} else {
					Nodeid prevRevision = revisionSequence.size() > 0 ? revisionSequence.get(revisionSequence.size()-1) : Nodeid.NULL;
					if (!prevRevision.equals(deltaBase)) {
						// presently, bundle group elements always patch previous, see
						// (a) changegroup.py#builddeltaheader(): # do nothing with basenode, it is implicitly the previous one in HG10
						// (b) revlog.py#group(): prev, curr = revs[r], revs[r + 1]
						//               for c in bundler.revchunk(self, curr, prev):
						// so there's no reason to have code here to extract contents of deltaBase revision
						String m = String.format("Revision %s import failed: delta base %s is not the last node we've handled (and know content for) %s", ge.node(), deltaBase, prevRevision);
						throw new HgInvalidStateException(m);
					}
				}
				//
				Patch patch = HgInternals.patchFromData(ge);
				byte[] content = patch.apply(prevRevContent, -1);
				Nodeid p1 = ge.firstParent();
				Nodeid p2 = ge.secondParent();
				byte[] calculated = dh.sha1(p1, p2, content).asBinary();
				final Nodeid node = ge.node();
				if (!node.equalsTo(calculated)) {
					String m = String.format("Checksum failed: expected %s, calculated %s. File %s", node, calculated, filename);
					throw new HgRevisionIntegrityException(m, null, new File(hgDir, filename));
				}
				revlogHeader.nodeid(node);
				//
				if (collectChangelogIndexes) {
					changelogIndexes.put(node, revisionSequence.size());
					revlogHeader.linkRevision(revisionSequence.size());
				} else {
					Integer csRev = changelogIndexes.get(ge.cset());
					if (csRev == null) {
						throw new HgInvalidStateException(String.format("Changelog doesn't contain revision %s of %s", ge.cset().shortNotation(), filename));
					}
					revlogHeader.linkRevision(csRev.intValue());
				}
				//
				revlogHeader.parents(knownRevision(p1), knownRevision(p2));
				//
				int patchSerializedLength = patch.serializedLength();
				// no reason to keep patch if it's close (here, >75%) in size to the complete contents,
				// save patching effort in this case
				writeComplete = writeComplete || preferCompleteOverPatch(patchSerializedLength, content.length);

				if (writeComplete) {
					revlogHeader.baseRevision(revisionSequence.size());
				}
				assert revlogHeader.baseRevision() >= 0;

				DataSerializer.DataSource dataSource = writeComplete ? new ByteArrayDataSource(content) : patch.new PatchDataSource();
				revlogDataZip.reset(dataSource);
				final int compressedLen;
				final boolean useUncompressedData = preferCompressedOverComplete(revlogDataZip.getCompressedLength(), dataSource.serializeLength());
				if (useUncompressedData) {
					// compression wasn't too effective,
					compressedLen = dataSource.serializeLength() + 1 /*1 byte for 'u' - uncompressed prefix byte*/;
				} else {
					compressedLen= revlogDataZip.getCompressedLength();
				}
		
				revlogHeader.length(content.length, compressedLen);
				
				// XXX may be wise not to create DataSerializer for each revision, but for a file
				DataSerializer sds = new DataSerializer() {
					@Override
						public void write(byte[] data, int offset, int length) throws HgIOException {
							try {
								indexFile.write(data, offset, length);
							} catch (IOException ex) {
								throw new HgIOException("Write failure", ex, currentFile);
							}
						}
				};
				revlogHeader.serialize(sds);

				if (useUncompressedData) {
					sds.writeByte((byte) 'u');
					dataSource.serialize(sds);
				} else {
					int actualCompressedLenWritten = revlogDataZip.writeCompressedData(sds);
					if (actualCompressedLenWritten != compressedLen) {
						throw new HgInvalidStateException(String.format("Expected %d bytes of compressed data, but actually wrote %d in %s", compressedLen, actualCompressedLenWritten, filename));
					}
				}
				sds.done();
				//
				revisionSequence.add(node);
				prevRevContent.done();
				prevRevContent = new ByteArrayDataAccess(content);
			} catch (HgIOException ex) {
				String m = String.format("Failed to write revision %s of file %s", ge.node().shortNotation(), filename);
				throw new HgInvalidControlFileException(m, ex, currentFile);
			} catch (IOException ex) {
				String m = String.format("Failed to write revision %s of file %s", ge.node().shortNotation(), filename);
				throw new HgInvalidControlFileException(m, ex, currentFile);
			}
			return cancelException == null;
		}
/*
 $ hg debugindex build.gradle
   rev    offset  length   base linkrev nodeid       p1           p2
     0         0     857      0     454 b2a1b20d1933 000000000000 000000000000
     1       857     319      0     455 5324c8f2b550 b2a1b20d1933 000000000000
     2      1176     533      0     460 4011d52141cd 5324c8f2b550 000000000000
     3      1709      85      0     463 d0be58845306 4011d52141cd 000000000000
     4      1794     105      0     464 3ddd456244a0 d0be58845306 000000000000
     5      1899     160      0     466 a3f374fbf33a 3ddd456244a0 000000000000
     6      2059     133      0     468 0227d28e0db6 a3f374fbf33a 000000000000

once we get a bundle for this repository and look into it for the same file:

 $hg debugbundle -a /tmp/hg-bundle-4418325145435980614.tmp
format: id, p1, p2, cset, delta base, len(delta)

build.gradle
62a101b7994c6c5b0423ba6c802f8c64d24ef784 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000 6ec4af642ba8024edd636af15e672c97cc3294e4 0000000000000000000000000000000000000000 1368
b2a1b20d1933d0605aab6780ee52fe5ab3073832 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000 7dcc920e2d57d5850ee9f08ac863251460565bd3 62a101b7994c6c5b0423ba6c802f8c64d24ef784 2373
5324c8f2b5503a4d1ead3ac40a9851c27572166b b2a1b20d1933d0605aab6780ee52fe5ab3073832 0000000000000000000000000000000000000000 7b883bf03b14ccea8ee74db0a34f9f66ca644a3c b2a1b20d1933d0605aab6780ee52fe5ab3073832 579
4011d52141cd717c92cbf350a93522d2f3ee415e 5324c8f2b5503a4d1ead3ac40a9851c27572166b 0000000000000000000000000000000000000000 55e9588b84b83aa96fe76a06ee8bf067c5d3c90e 5324c8f2b5503a4d1ead3ac40a9851c27572166b 1147
d0be588453068787dcb3ee05f8edfe47fdd5ae78 4011d52141cd717c92cbf350a93522d2f3ee415e 0000000000000000000000000000000000000000 ad0322a4af204547c400e1846b2b83d446ab8da5 4011d52141cd717c92cbf350a93522d2f3ee415e 85
3ddd456244a08f81779163d9faf922a6dcd9e53e d0be588453068787dcb3ee05f8edfe47fdd5ae78 0000000000000000000000000000000000000000 3ace1fc95d0a1a941b6427c60b6e624f96dd71ad d0be588453068787dcb3ee05f8edfe47fdd5ae78 151
a3f374fbf33aba1cc3b4f472db022b5185880f5d 3ddd456244a08f81779163d9faf922a6dcd9e53e 0000000000000000000000000000000000000000 3ca4ae7bdd3890b8ed89bfea1b42af593e04b373 3ddd456244a08f81779163d9faf922a6dcd9e53e 195
0227d28e0db69afebee34cd5a4151889fb6271da a3f374fbf33aba1cc3b4f472db022b5185880f5d 0000000000000000000000000000000000000000 31bd09da0dcfe48e1fc662143f91ff402238aa84 a3f374fbf33aba1cc3b4f472db022b5185880f5d 145

but there's no delta base information in the bundle file, it's merely a hard-coded convention 

It's unclear where the first chunk (identified 62a101b7...) comes from (by the way, there's no such changeset as 6ec4af... as specified in the chunk, while 7dcc920e.. IS changeset 454)

EXPLANATION:
if cloned repository comes from svnkit repo (where's the gradle branch):
$hg debugindex build.gradle
   rev    offset  length   base linkrev nodeid       p1           p2
     0         0     590      0     213 62a101b7994c 000000000000 000000000000
     1       590     872      0     452 b2a1b20d1933 000000000000 000000000000
     2      1462     319      0     453 5324c8f2b550 b2a1b20d1933 000000000000
     3      1781     533      0     459 4011d52141cd 5324c8f2b550 000000000000
     4      2314      85      0     462 d0be58845306 4011d52141cd 000000000000
     5      2399     105      0     466 3ddd456244a0 d0be58845306 000000000000
     6      2504     160      0     468 a3f374fbf33a 3ddd456244a0 000000000000
     7      2664     133      0     470 0227d28e0db6 a3f374fbf33a 000000000000

and the aforementioned bundle was result of hg incoming svnkit!!! 
 */

		public void start(int count, Callback callback, Object token) {
			progressSupport.start(count);
			lifecycleCallback = callback;
		}

		public void finish(Object token) {
			progressSupport.done();
			lifecycleCallback = null;
		}
		
		public void checkFailure() throws CancelledException {
			if (cancelException != null) {
				throw cancelException;
			}
		}
		
		private void stopIfCancelled() {
			try {
				cancelSupport.checkCancelled();
				return;
			} catch (CancelledException ex) {
				cancelException = ex;
				lifecycleCallback.stop();
			}
		}
	}
}
