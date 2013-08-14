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

import java.io.InputStream;

import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.Pool;
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

	public void execute(Mediator mediator) throws HgCallbackTargetException, HgRepositoryLockException, HgLibraryFailureException, CancelledException {
		if (firstCset == BAD_REVISION || secondCset == BAD_REVISION || ancestorCset == BAD_REVISION) {
			throw new IllegalArgumentException("Merge heads and their ancestors are not initialized");
		}
		final HgRepositoryLock wdLock = repo.getWorkingDirLock();
		wdLock.acquire();
		try {
			Pool<Nodeid> cacheRevs = new Pool<Nodeid>();
			Pool<Path> cacheFiles = new Pool<Path>();
			ManifestRevision m1, m2, ma;
			m1 = new ManifestRevision(cacheRevs, cacheFiles).init(repo, firstCset);
			m2 = new ManifestRevision(cacheRevs, cacheFiles).init(repo, secondCset);
			ma = new ManifestRevision(cacheRevs, cacheFiles).init(repo, ancestorCset);
			ResolverImpl resolver = new ResolverImpl();
			for (Path f : m1.files()) {
				Nodeid fileRevBase, fileRevA, fileRevB;
				if (m2.contains(f)) {
					fileRevA = m1.nodeid(f);
					fileRevB = m2.nodeid(f);
					fileRevBase = ma.contains(f) ? ma.nodeid(f) : null;
					if (fileRevA.equals(fileRevB)) {
						HgFileRevision fr = new HgFileRevision(repo, fileRevA, m1.flags(f), f);
						mediator.same(fr, fr, resolver);
					} else if (fileRevBase == fileRevA) {
						assert fileRevBase != null;
						HgFileRevision frBase = new HgFileRevision(repo, fileRevBase, ma.flags(f), f);
						HgFileRevision frSecond= new HgFileRevision(repo, fileRevB, m2.flags(f), f);
						mediator.fastForwardB(frBase, frSecond, resolver);
					} else if (fileRevBase == fileRevB) {
						assert fileRevBase != null;
						HgFileRevision frBase = new HgFileRevision(repo, fileRevBase, ma.flags(f), f);
						HgFileRevision frFirst = new HgFileRevision(repo, fileRevA, m1.flags(f), f);
						mediator.fastForwardA(frBase, frFirst, resolver);
					} else {
						HgFileRevision frBase = fileRevBase == null ? null : new HgFileRevision(repo, fileRevBase, ma.flags(f), f);
						HgFileRevision frFirst = new HgFileRevision(repo, fileRevA, m1.flags(f), f);
						HgFileRevision frSecond= new HgFileRevision(repo, fileRevB, m2.flags(f), f);
						mediator.resolve(frBase, frFirst, frSecond, resolver);
					}
				} else {
					// m2 doesn't contain the file, either new in m1, or deleted in m2
					HgFileRevision frFirst = new HgFileRevision(repo, m1.nodeid(f), m1.flags(f), f);
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
	 * This is the way client code takes part in the merge process
	 */
	@Experimental(reason="Provisional API. Work in progress")
	@Callback
	public interface Mediator {
		public void same(HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException;
		public void onlyA(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		public void onlyB(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		public void newInA(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		public void newInB(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException;
		public void fastForwardA(HgFileRevision base, HgFileRevision first, Resolver resolver) throws HgCallbackTargetException;
		public void fastForwardB(HgFileRevision base, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException;
		public void resolve(HgFileRevision base, HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException;
	}

	/**
	 * Clients shall not implement this interface.
	 * They use this API from inside {@link Mediator#resolve(HgFileRevision, HgFileRevision, HgFileRevision, Resolver)}
	 */
	@Experimental(reason="Provisional API. Work in progress")
	public interface Resolver {
		public void use(HgFileRevision rev);
		public void use(InputStream content);
		public void unresolved(); // record the file for later processing by 'hg resolve'
	}

	private static class ResolverImpl implements Resolver {
		void apply() {
		}

		public void use(HgFileRevision rev) {
			// TODO Auto-generated method stub
		}

		public void use(InputStream content) {
			// TODO Auto-generated method stub
		}

		public void unresolved() {
			// TODO Auto-generated method stub
		}
	}
}
