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

import java.util.ArrayDeque;

import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Outcome;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 * Primary purpose is to provide information about file revisions at specific changeset. Multiple {@link #check(Path)} calls 
 * are possible once {@link #changeset(Nodeid)} (and optionally, {@link #followRenames(boolean)}) were set.
 * 
 * <p>Sample:
 * <pre><code>
 *   HgChangesetFileSneaker i = new HgChangesetFileSneaker(hgRepo).changeset(Nodeid.fromString("<40 digits>")).followRenames(true);
 *   if (i.check(file)) {
 *   	HgCatCommand catCmd = new HgCatCommand(hgRepo).revision(i.getFileRevision());
 *   	catCmd.execute(...);
 *   	...
 *   }
 * </pre></code>
 *
 * TODO may add #manifest(Nodeid) to select manifest according to its revision (not only changeset revision as it's now)
 * 
 * <p>Unlike {@link HgManifest#getFileRevision(int, Path)}, this class is useful when few files from the same changeset have to be inspected
 * 
 * @see HgManifest#getFileRevision(int, Path)
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgChangesetFileSneaker {

	private final HgRepository repo;
	private boolean followRenames;
	private Nodeid cset;
	private ManifestRevision cachedManifest;
	private HgFileRevision fileRevision;
	private boolean renamed;
	private Outcome checkResult;

	public HgChangesetFileSneaker(HgRepository hgRepo) {
		repo = hgRepo;
	}

	/**
	 * Select specific changelog revision
	 * 
	 * @param nid changeset identifier
	 * @return <code>this</code> for convenience
	 */
	public HgChangesetFileSneaker changeset(Nodeid nid) {
		if (nid == null || nid.isNull()) {
			throw new IllegalArgumentException(); 
		}
		cset = nid;
		cachedManifest = null;
		fileRevision = null;
		return this;
	}

	/**
	 * Whether to check file origins, default is false (look up only the name supplied)
	 *
	 * @param follow <code>true</code> to check copy/rename origin of the file if it is a copy.
	 * @return <code>this</code> for convenience
	 */
	public HgChangesetFileSneaker followRenames(boolean follow) {
		followRenames = follow;
		fileRevision = null;
		return this;
	}

	/**
	 * Shortcut to perform {@link #check(Path)} and {@link #exists()}. Result of the check may be accessed via {@link #getCheckResult()}.
	 * Errors during the check, if any, are reported through exception.
	 * 
	 * @param file name of the file in question
	 * @return <code>true</code> if file is known at the selected changeset.
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws IllegalArgumentException if {@link #changeset(Nodeid)} not specified or file argument is bad.
	 */
	public boolean checkExists(Path file) throws HgException {
		check(file);
		// next seems reasonable, however renders boolean return value useless. perhaps void or distinct method?
//		if (checkResult.isOk() && !exists()) {
//			throw new HgPathNotFoundException(checkResult.getMessage(), file);
//		}
		if (!checkResult.isOk() && checkResult.getException() instanceof HgRuntimeException) {
			throw new HgLibraryFailureException((HgRuntimeException) checkResult.getException());
		}
		return checkResult.isOk() && exists();
	}

	/**
	 * Find file (or its origin, if {@link #followRenames(boolean)} was set to <code>true</code>) among files known at specified {@link #changeset(Nodeid)}.
	 * 
	 * @param file name of the file in question
	 * @return status object that describes outcome, {@link Outcome#isOk() Ok} status indicates successful completion of the operation, but doesn't imply
	 * file existence, use {@link #exists()} for that purpose. Message of the status may provide further hints on what exactly had happened. 
	 * @throws IllegalArgumentException if {@link #changeset(Nodeid)} not specified or file argument is bad.
	 */
	public Outcome check(Path file) {
		fileRevision = null;
		checkResult = null;
		renamed = false;
		if (cset == null || file == null || file.isDirectory()) {
			throw new IllegalArgumentException();
		}
		HgDataFile dataFile = repo.getFileNode(file);
		if (!dataFile.exists()) {
			checkResult = new Outcome(Outcome.Kind.Success, String.format("File named %s is not known in the repository", file));
			return checkResult;
		}
		Nodeid toExtract = null;
		String phaseMsg = "Extract manifest revision failed";
		try {
			if (cachedManifest == null) {
				int csetRev = repo.getChangelog().getRevisionIndex(cset);
				cachedManifest = new ManifestRevision(null, null); // XXX how about context and cached manifest revisions
				repo.getManifest().walk(csetRev, csetRev, cachedManifest);
				// cachedManifest shall be meaningful - changelog.getRevisionIndex() above ensures we've got version that exists.
			}
			toExtract = cachedManifest.nodeid(file);
			phaseMsg = "Follow copy/rename failed";
			if (toExtract == null && followRenames) {
				int csetIndex = repo.getChangelog().getRevisionIndex(cset);
				int ccFileRevIndex = dataFile.getLastRevision(); // copy candidate
				int csetFileEnds = dataFile.getChangesetRevisionIndex(ccFileRevIndex);
				if (csetIndex > csetFileEnds) {
					return new Outcome(Outcome.Kind.Success, String.format("%s: last known changeset for the file %s is %d. Follow renames is possible towards older changesets only", phaseMsg, file, csetFileEnds));
				}
				// XXX code is similar to that in HgStatusCollector#getOriginIfCopy. Why it's different in lastOrigin processing then?
				// traceback stack keeps record of all files with isCopy(fileRev) == true we've tried to follow, so that we can try earlier file
				// revisions in case followed fileRev didn't succeed
				ArrayDeque<Pair<HgDataFile, Integer>> traceback = new ArrayDeque<Pair<HgDataFile, Integer>>();
				do {
					int ccCsetIndex = dataFile.getChangesetRevisionIndex(ccFileRevIndex);
					if (ccCsetIndex <= csetIndex) {
						// present dataFile is our (distant) origin
						toExtract = dataFile.getRevision(ccFileRevIndex);
						renamed = true;
						break;
					}
					if (!dataFile.isCopy(ccFileRevIndex)) {
						// nothing left to return to when traceback.isEmpty()
						while (ccFileRevIndex == 0 && !traceback.isEmpty()) {
							Pair<HgDataFile, Integer> lastTurnPoint = traceback.pop();
							dataFile = lastTurnPoint.first();
							ccFileRevIndex = lastTurnPoint.second(); // generally ccFileRevIndex != 0 here, but doesn't hurt to check, hence while
							// fall through to shift down from the file revision we've already looked at
						}
						ccFileRevIndex--;
						continue;
					}
					if (ccFileRevIndex > 0) {
						// there's no reason to memorize turn point if it's the very first revision
						// of the file and we won't be able to try any other earlier revision
						traceback.push(new Pair<HgDataFile, Integer>(dataFile, ccFileRevIndex));
					}
					HgFileRevision origin = dataFile.getCopySource(ccFileRevIndex);
					dataFile = repo.getFileNode(origin.getPath());
					ccFileRevIndex = dataFile.getRevisionIndex(origin.getRevision());
				} while (ccFileRevIndex >= 0);
				// didn't get to csetIndex, no ancestor in file rename history found.
			}
		} catch (HgRuntimeException ex) {
			checkResult = new Outcome(Outcome.Kind.Failure, phaseMsg, ex);
			return checkResult;
		}
		if (toExtract != null) {
			Flags extractRevFlags = cachedManifest.flags(dataFile.getPath());
			fileRevision = new HgFileRevision(repo, toExtract, extractRevFlags, dataFile.getPath());
			checkResult = new Outcome(Outcome.Kind.Success, String.format("File %s, revision %s found at changeset %s", dataFile.getPath(), toExtract.shortNotation(), cset.shortNotation()));
			return checkResult;
		} 
		checkResult = new Outcome(Outcome.Kind.Success, String.format("File %s nor its origins were known at revision %s", file, cset.shortNotation()));
		return checkResult;
	}
	
	/**
	 * Re-get latest check status object
	 */
	public Outcome getCheckResult() {
		assertCheckRan();
		return checkResult;
	}
	
	/**
	 * @return result of the last {@link #check(Path)} call.
	 */
	public boolean exists() {
		assertCheckRan();
		return fileRevision != null;
	}
	
	/**
	 * @return <code>true</code> if checked file was known by another name at the time of specified changeset.
	 */
	public boolean hasAnotherName() {
		assertCheckRan();
		return renamed;
	}

	/**
	 * @return holder for file revision information
	 */
	public HgFileRevision getFileRevision() {
		assertCheckRan();
		return fileRevision;
	}

	/**
	 * Name of the checked file as it was known at the time of the specified changeset.
	 * 
	 * @return handy shortcut for <code>getFileRevision().getPath()</code>
	 */
	public Path filename() {
		assertCheckRan();
		return fileRevision.getPath();
	}
	
	/**
	 * Revision of the checked file
	 * 
	 * @return handy shortcut for <code>getFileRevision().getRevision()</code>
	 */
	public Nodeid revision() {
		assertCheckRan();
		return fileRevision.getRevision();
	}

	private void assertCheckRan() {
		if (checkResult == null) {
			throw new HgInvalidStateException("Shall invoke #check(Path) first");
		}
	}

}
