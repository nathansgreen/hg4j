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
package org.tmatesoft.hg.core;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.DirstateBuilder;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FileUtils;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.MergeStateBuilder;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.internal.Transaction;
import org.tmatesoft.hg.internal.WorkingDirFileWriter;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryLock;
import org.tmatesoft.hg.repo.HgRevisionMap;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;

/**
 * Merge two revisions, 'hg merge REV' counterpart
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 * @since 1.2
 */
@Experimental(reason="Provisional API. Work in progress")
public class HgMergeCommand extends HgAbstractCommand<HgMergeCommand> {

	private final HgRepository repo;
	private int firstCset, secondCset, ancestorCset;

	public HgMergeCommand(HgRepository hgRepo) {
		repo = hgRepo;
		firstCset = secondCset = ancestorCset = BAD_REVISION;
	}
	
	public HgMergeCommand changeset(Nodeid changeset) throws HgBadArgumentException {
		initHeadsAndAncestor(new CsetParamKeeper(repo).set(changeset).get());
		return this;
	}
	
	public HgMergeCommand changeset(int revisionIndex) throws HgBadArgumentException {
		initHeadsAndAncestor(new CsetParamKeeper(repo).set(revisionIndex).get());
		return this;
	}

	public void execute(Mediator mediator) throws HgCallbackTargetException, HgRepositoryLockException, HgIOException, HgLibraryFailureException, CancelledException {
		if (firstCset == BAD_REVISION || secondCset == BAD_REVISION || ancestorCset == BAD_REVISION) {
			throw new IllegalArgumentException("Merge heads and their ancestors are not initialized");
		}
		final HgRepositoryLock wdLock = repo.getWorkingDirLock();
		wdLock.acquire();
		try {
			Pool<Nodeid> cacheRevs = new Pool<Nodeid>();
			Pool<Path> cacheFiles = new Pool<Path>();

			Internals implRepo = Internals.getInstance(repo);
			final DirstateBuilder dirstateBuilder = new DirstateBuilder(implRepo);
			dirstateBuilder.fillFrom(new DirstateReader(implRepo, new Path.SimpleSource(repo.getSessionContext().getPathFactory(), cacheFiles)));
			final HgChangelog clog = repo.getChangelog();
			dirstateBuilder.parents(clog.getRevision(firstCset), clog.getRevision(secondCset));
			//
			MergeStateBuilder mergeStateBuilder = new MergeStateBuilder(implRepo);

			ManifestRevision m1, m2, ma;
			m1 = new ManifestRevision(cacheRevs, cacheFiles).init(repo, firstCset);
			m2 = new ManifestRevision(cacheRevs, cacheFiles).init(repo, secondCset);
			ma = new ManifestRevision(cacheRevs, cacheFiles).init(repo, ancestorCset);
			Transaction transaction = implRepo.getTransactionFactory().create(repo);
			ResolverImpl resolver = new ResolverImpl(implRepo, dirstateBuilder, mergeStateBuilder);
			try {
				for (Path f : m1.files()) {
					Nodeid fileRevBase, fileRevA, fileRevB;
					if (m2.contains(f)) {
						fileRevA = m1.nodeid(f);
						fileRevB = m2.nodeid(f);
						fileRevBase = ma.contains(f) ? ma.nodeid(f) : null;
						if (fileRevA.equals(fileRevB)) {
							HgFileRevision fr = new HgFileRevision(repo, fileRevA, m1.flags(f), f);
							resolver.presentState(f, fr, fr);
							mediator.same(fr, resolver);
						} else if (fileRevBase == fileRevA) {
							assert fileRevBase != null;
							HgFileRevision frBase = new HgFileRevision(repo, fileRevBase, ma.flags(f), f);
							HgFileRevision frSecond= new HgFileRevision(repo, fileRevB, m2.flags(f), f);
							resolver.presentState(f, frBase, frSecond);
							mediator.fastForwardB(frBase, frSecond, resolver);
						} else if (fileRevBase == fileRevB) {
							assert fileRevBase != null;
							HgFileRevision frBase = new HgFileRevision(repo, fileRevBase, ma.flags(f), f);
							HgFileRevision frFirst = new HgFileRevision(repo, fileRevA, m1.flags(f), f);
							resolver.presentState(f, frFirst, frBase);
							mediator.fastForwardA(frBase, frFirst, resolver);
						} else {
							HgFileRevision frBase = fileRevBase == null ? null : new HgFileRevision(repo, fileRevBase, ma.flags(f), f);
							HgFileRevision frFirst = new HgFileRevision(repo, fileRevA, m1.flags(f), f);
							HgFileRevision frSecond= new HgFileRevision(repo, fileRevB, m2.flags(f), f);
							resolver.presentState(f, frFirst, frSecond);
							mediator.resolve(frBase, frFirst, frSecond, resolver);
						}
					} else {
						// m2 doesn't contain the file, either new in m1, or deleted in m2
						HgFileRevision frFirst = new HgFileRevision(repo, m1.nodeid(f), m1.flags(f), f);
						resolver.presentState(f, frFirst, null);
						if (ma.contains(f)) {
							// deleted in m2
							HgFileRevision frBase = new HgFileRevision(repo, ma.nodeid(f), ma.flags(f), f);
							mediator.onlyA(frBase, frFirst, resolver);
						} else {
							// new in m1
							mediator.newInA(frFirst, resolver);
						}
					}
					resolver.apply();
				} // for m1 files
				for (Path f : m2.files()) {
					if (m1.contains(f)) {
						continue;
					}
					HgFileRevision frSecond= new HgFileRevision(repo, m2.nodeid(f), m2.flags(f), f);
					// file in m2 is either new or deleted in m1
					resolver.presentState(f, null, frSecond);
					if (ma.contains(f)) {
						// deleted in m1
						HgFileRevision frBase = new HgFileRevision(repo, ma.nodeid(f), ma.flags(f), f);
						mediator.onlyB(frBase, frSecond, resolver);
					} else {
						// new in m2
						mediator.newInB(frSecond, resolver);
					}
					resolver.apply();
				}
				resolver.serializeChanged(transaction);
				transaction.commit();
			} catch (HgRuntimeException ex) {
				transaction.rollback();
				throw ex;
			} catch (HgIOException ex) {
				transaction.rollback();
				throw ex;
			}
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			wdLock.release();
		}
	}

	private void initHeadsAndAncestor(int csetIndexB) throws HgBadArgumentException {
		firstCset = secondCset = ancestorCset = BAD_REVISION;
		if (csetIndexB == HgRepository.BAD_REVISION) {
			throw new HgBadArgumentException("Need valid second head for merge", null);
		}
		// TODO cache/share parent-child map, e.g. right in HgChangelog?! #getOrCreate
		HgParentChildMap<HgChangelog> pmap = new HgParentChildMap<HgChangelog>(repo.getChangelog());
		pmap.init();
		final HgRevisionMap<HgChangelog> rmap = pmap.getRevisionMap();
		final Nodeid csetA = repo.getWorkingCopyParents().first();
		final Nodeid csetB = rmap.revision(csetIndexB);
		final Nodeid ancestor = pmap.ancestor(csetA, csetB);
		assert !ancestor.isNull();
		if (ancestor.equals(csetA) || ancestor.equals(csetB)) {
			throw new HgBadArgumentException(String.format("Revisions %s and %s are on the same line of descent, use update instead of merge", csetA.shortNotation(), csetB.shortNotation()), null);
		}
		firstCset = rmap.revisionIndex(csetA);
		secondCset = csetIndexB;
		ancestorCset = rmap.revisionIndex(ancestor);
	}

	/**
	 * This is the way client code takes part in the merge process. 
	 * It's advised to subclass {@link MediatorBase} unless special treatment for regular cases is desired
	 */
	@Experimental(reason="Provisional API. Work in progress")
	@Callback
	public interface Mediator {
		/**
		 * file revisions are identical in both heads
		 */
		public void same(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * file left in first/left/A trunk only, deleted in second/right/B trunk
		 */
		public void onlyA(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * file left in second/right/B trunk only, deleted in first/left/A trunk
		 */
		public void onlyB(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * file is missing in ancestor revision and second/right/B trunk, introduced in first/left/A trunk
		 */
		public void newInA(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * file is missing in ancestor revision and first/left/A trunk, introduced in second/right/B trunk
		 */
		public void newInB(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * file was changed in first/left/A trunk, unchanged in second/right/B trunk
		 */
		public void fastForwardA(HgFileRevision base, HgFileRevision first, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * file was changed in second/right/B trunk, unchanged in first/left/A trunk 
		 */
		public void fastForwardB(HgFileRevision base, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException;
		/**
		 * File changed (or added, if base is <code>null</code>) in both trunks 
		 */
		public void resolve(HgFileRevision base, HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException;
	}

	/**
	 * Clients shall not implement this interface.
	 * They use this API from inside {@link Mediator#resolve(HgFileRevision, HgFileRevision, HgFileRevision, Resolver)}
	 */
	@Experimental(reason="Provisional API. Work in progress")
	public interface Resolver {
		public void use(HgFileRevision rev);
		/**
		 * Replace current revision with stream content.
		 * Note, callers are not expected to {@link InputStream#close()} this stream. 
		 * It will be {@link InputStream#close() closed} at <b>Hg4J</b>'s discretion
		 * not necessarily during invocation of this method. IOW, the library may decide to 
		 * use this stream not right away, at some point of time later, and streams supplied
		 * shall respect this.
		 * 
		 * @param content New content to replace current revision, shall not be <code>null</code> 
		 * @throws IOException propagated exceptions from content
		 */
		public void use(InputStream content) throws IOException;
		public void forget(HgFileRevision rev);
		public void unresolved(); // record the file for later processing by 'hg resolve'
	}

	/**
	 * Base mediator implementation, with regular resolution
	 */
	@Experimental(reason="Provisional API. Work in progress")
	public abstract class MediatorBase implements Mediator {
		public void same(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(rev);
		}
		public void onlyA(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(rev);
		}
		public void onlyB(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(rev);
		}
		public void newInA(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(rev);
		}
		public void newInB(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(rev);
		}
		public void fastForwardA(HgFileRevision base, HgFileRevision first, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(first);
		}
		public void fastForwardB(HgFileRevision base, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
			resolver.use(second);
		}
	}

	private static class ResolverImpl implements Resolver {
		
		private final Internals repo;
		private final DirstateBuilder dirstateBuilder;
		private final MergeStateBuilder mergeStateBuilder;
		private boolean changedDirstate;
		private HgFileRevision revA;
		private HgFileRevision revB;
		private Path file;
		// resolutions:
		private HgFileRevision resolveUse, resolveForget;
		private File resolveContent;
		private boolean resolveMarkUnresolved;
		
		public ResolverImpl(Internals implRepo, DirstateBuilder dirstateBuilder, MergeStateBuilder mergeStateBuilder) {
			repo = implRepo;
			this.dirstateBuilder = dirstateBuilder;
			this.mergeStateBuilder = mergeStateBuilder;
			changedDirstate = false;
		}
		
		void serializeChanged(Transaction tr) throws HgIOException {
			if (changedDirstate) {
				dirstateBuilder.serialize(tr);
			}
			mergeStateBuilder.serialize(tr);
		}

		void presentState(Path p, HgFileRevision revA, HgFileRevision revB) {
			assert revA != null || revB != null;
			file = p;
			this.revA = revA;
			this.revB = revB;
			resolveUse = resolveForget = null;
			resolveContent = null;
			resolveMarkUnresolved = false;
		}

		void apply() throws HgIOException, HgRuntimeException {
			if (resolveMarkUnresolved) {
				mergeStateBuilder.unresolved(file);
			} else if (resolveForget != null) {
				if (resolveForget == revA) {
					changedDirstate = true;
					dirstateBuilder.recordRemoved(file);
				}
			} else if (resolveUse != null) {
				if (resolveUse != revA) {
					changedDirstate = true;
					final WorkingDirFileWriter fw = new WorkingDirFileWriter(repo);
					fw.processFile(resolveUse);
					if (resolveUse == revB) {
						dirstateBuilder.recordMergedFromP2(file);
					} else {
						dirstateBuilder.recordMerged(file, fw.fmode(), fw.mtime(), fw.bytesWritten());
					}
				} // if resolution is to use revA, nothing to do
			} else if (resolveContent != null) {
				changedDirstate = true;
				// FIXME write content to file using transaction?
				InputStream is;
				try {
					is = new FileInputStream(resolveContent);
				} catch (IOException ex) {
					throw new HgIOException("Failed to read temporary content", ex, resolveContent);
				}
				final WorkingDirFileWriter fw = new WorkingDirFileWriter(repo);
				fw.processFile(file, is, revA == null ? revB.getFileFlags() : revA.getFileFlags());
				// XXX if presentState(null, fileOnlyInB), and use(InputStream) - i.e.
				// resolution is to add file with supplied content - shall I put 'Merged', MergedFromP2 or 'Added' into dirstate?
				if (revA == null && revB != null) {
					dirstateBuilder.recordMergedFromP2(file);
				} else {
					dirstateBuilder.recordMerged(file, fw.fmode(), fw.mtime(), fw.bytesWritten());
				}
			} else {
				assert false;
			}
		}

		public void use(HgFileRevision rev) {
			if (rev == null) {
				throw new IllegalArgumentException();
			}
			assert resolveContent == null;
			assert resolveForget == null;
			resolveUse = rev;
		}

		public void use(InputStream content) throws IOException {
			if (content == null) {
				throw new IllegalArgumentException();
			}
			assert resolveUse == null;
			assert resolveForget == null;
			try {
				// cache new contents just to fail fast if there are troubles with content
				final FileUtils fileUtils = new FileUtils(repo.getLog(), this);
				resolveContent = fileUtils.createTempFile();
				fileUtils.write(content, resolveContent);
			} finally {
				content.close();
			}
			// do not care deleting file in case of failure to allow analyze of the issue
		}

		public void forget(HgFileRevision rev) {
			if (rev == null) {
				throw new IllegalArgumentException();
			}
			if (rev != revA || rev != revB) {
				throw new IllegalArgumentException("Can't forget revision which doesn't represent actual state in either merged trunk");
			}
			assert resolveUse == null;
			assert resolveContent == null;
			resolveForget = rev;
		}

		public void unresolved() {
			resolveMarkUnresolved = true;
		}
	}
}
