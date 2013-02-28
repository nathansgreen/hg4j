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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.tmatesoft.hg.core.HgRepositoryLockException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.ChangelogEntryBuilder;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FNCacheFile;
import org.tmatesoft.hg.internal.ManifestEntryBuilder;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.internal.RevlogStreamWriter;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 * WORK IN PROGRESS
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public final class CommitFacility {
	private final HgRepository repo;
	private final int p1Commit, p2Commit;
	private Map<Path, Pair<HgDataFile, ByteDataSupplier>> files = new LinkedHashMap<Path, Pair<HgDataFile, ByteDataSupplier>>();
	private Set<Path> removals = new TreeSet<Path>();
	private String branch;

	public CommitFacility(HgRepository hgRepo, int parentCommit) {
		this(hgRepo, parentCommit, NO_REVISION);
	}
	
	public CommitFacility(HgRepository hgRepo, int parent1Commit, int parent2Commit) {
		repo = hgRepo;
		p1Commit = parent1Commit;
		p2Commit = parent2Commit;
		if (parent1Commit != NO_REVISION && parent1Commit == parent2Commit) {
			throw new IllegalArgumentException("Merging same revision is dubious");
		}
	}

	public boolean isMerge() {
		return p1Commit != NO_REVISION && p2Commit != NO_REVISION;
	}

	public void add(HgDataFile dataFile, ByteDataSupplier content) {
		if (content == null) {
			throw new IllegalArgumentException();
		}
		removals.remove(dataFile.getPath());
		files.put(dataFile.getPath(), new Pair<HgDataFile, ByteDataSupplier>(dataFile, content));
	}

	public void forget(HgDataFile dataFile) {
		files.remove(dataFile.getPath());
		removals.add(dataFile.getPath());
	}
	
	public void branch(String branchName) {
		branch = branchName;
	}
	
	public Nodeid commit(String message) throws HgRepositoryLockException {
		
		final HgChangelog clog = repo.getChangelog();
		final int clogRevisionIndex = clog.getRevisionCount();
		ManifestRevision c1Manifest = new ManifestRevision(null, null);
		ManifestRevision c2Manifest = new ManifestRevision(null, null);
		if (p1Commit != NO_REVISION) {
			repo.getManifest().walk(p1Commit, p1Commit, c1Manifest);
		}
		if (p2Commit != NO_REVISION) {
			repo.getManifest().walk(p2Commit, p2Commit, c2Manifest);
		}
//		Pair<Integer, Integer> manifestParents = getManifestParents();
		Pair<Integer, Integer> manifestParents = new Pair<Integer, Integer>(c1Manifest.revisionIndex(), c2Manifest.revisionIndex());
		TreeMap<Path, Nodeid> newManifestRevision = new TreeMap<Path, Nodeid>();
		HashMap<Path, Pair<Integer, Integer>> fileParents = new HashMap<Path, Pair<Integer,Integer>>();
		for (Path f : c1Manifest.files()) {
			HgDataFile df = repo.getFileNode(f);
			Nodeid fileKnownRev1 = c1Manifest.nodeid(f), fileKnownRev2;
			final int fileRevIndex1 = df.getRevisionIndex(fileKnownRev1);
			final int fileRevIndex2;
			if ((fileKnownRev2 = c2Manifest.nodeid(f)) != null) {
				// merged files
				fileRevIndex2 = df.getRevisionIndex(fileKnownRev2);
			} else {
				fileRevIndex2 = NO_REVISION;
			}
				
			fileParents.put(f, new Pair<Integer, Integer>(fileRevIndex1, fileRevIndex2));
			newManifestRevision.put(f, fileKnownRev1);
		}
		//
		// Forget removed
		for (Path p : removals) {
			newManifestRevision.remove(p);
		}
		//
		// Register new/changed
		ArrayList<Path> newlyAddedFiles = new ArrayList<Path>();
		for (Pair<HgDataFile, ByteDataSupplier> e : files.values()) {
			HgDataFile df = e.first();
			Pair<Integer, Integer> fp = fileParents.get(df.getPath());
			if (fp == null) {
				// NEW FILE
				fp = new Pair<Integer, Integer>(NO_REVISION, NO_REVISION);
			}
			ByteDataSupplier bds = e.second();
			// FIXME quickfix, instead, pass ByteDataSupplier directly to RevlogStreamWriter
			ByteBuffer bb = ByteBuffer.allocate(2048);
			ByteArrayChannel bac = new ByteArrayChannel();
			while (bds.read(bb) != -1) {
				bb.flip();
				bac.write(bb);
				bb.clear();
			}
			RevlogStream contentStream;
			if (df.exists()) {
				contentStream = df.content;
			} else {
				contentStream = repo.createStoreFile(df.getPath());
				newlyAddedFiles.add(df.getPath());
				// FIXME df doesn't get df.content updated, and clients
				// that would attempt to access newly added file after commit would fail
				// (despite the fact the file is in there)
			}
			RevlogStreamWriter fileWriter = new RevlogStreamWriter(repo.getSessionContext(), contentStream);
			Nodeid fileRev = fileWriter.addRevision(bac.toArray(), clogRevisionIndex, fp.first(), fp.second());
			newManifestRevision.put(df.getPath(), fileRev);
		}
		//
		// Manifest
		final ManifestEntryBuilder manifestBuilder = new ManifestEntryBuilder();
		for (Map.Entry<Path, Nodeid> me : newManifestRevision.entrySet()) {
			manifestBuilder.add(me.getKey().toString(), me.getValue());
		}
		RevlogStreamWriter manifestWriter = new RevlogStreamWriter(repo.getSessionContext(), repo.getManifest().content);
		Nodeid manifestRev = manifestWriter.addRevision(manifestBuilder.build(), clogRevisionIndex, manifestParents.first(), manifestParents.second());
		//
		// Changelog
		final ChangelogEntryBuilder changelogBuilder = new ChangelogEntryBuilder();
		changelogBuilder.setModified(files.keySet());
		changelogBuilder.branch(branch == null ? HgRepository.DEFAULT_BRANCH_NAME : branch);
		byte[] clogContent = changelogBuilder.build(manifestRev, message);
		RevlogStreamWriter changelogWriter = new RevlogStreamWriter(repo.getSessionContext(), clog.content);
		Nodeid changesetRev = changelogWriter.addRevision(clogContent, clogRevisionIndex, p1Commit, p2Commit);
		// FIXME move fncache update to an external facility, along with dirstate update
		if (!newlyAddedFiles.isEmpty() && repo.getImplHelper().fncacheInUse()) {
			FNCacheFile fncache = new FNCacheFile(repo.getImplHelper());
			for (Path p : newlyAddedFiles) {
				fncache.add(p);
			}
			try {
				fncache.write();
			} catch (IOException ex) {
				// see comment above for fnchache.read()
				repo.getSessionContext().getLog().dump(getClass(), Severity.Error, ex, "Failed to write fncache, error ignored");
			}
		}
		return changesetRev;
	}
/*
	private Pair<Integer, Integer> getManifestParents() {
		return new Pair<Integer, Integer>(extractManifestRevisionIndex(p1Commit), extractManifestRevisionIndex(p2Commit));
	}

	private int extractManifestRevisionIndex(int clogRevIndex) {
		if (clogRevIndex == NO_REVISION) {
			return NO_REVISION;
		}
		RawChangeset commitObject = repo.getChangelog().range(clogRevIndex, clogRevIndex).get(0);
		Nodeid manifestRev = commitObject.manifest();
		if (manifestRev.isNull()) {
			return NO_REVISION;
		}
		return repo.getManifest().getRevisionIndex(manifestRev);
	}
*/

	// unlike DataAccess (which provides structured access), this one 
	// deals with a sequence of bytes, when there's no need in structure of the data
	public interface ByteDataSupplier { // TODO look if can resolve DataAccess in HgCloneCommand visibility issue
		// FIXME needs lifecycle, e.g. for supplier that reads from WC
		int read(ByteBuffer buf);
	}
	
	public interface ByteDataConsumer {
		void write(ByteBuffer buf);
	}
}
