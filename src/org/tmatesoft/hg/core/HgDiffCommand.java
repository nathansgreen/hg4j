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

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.FileHistory;
import org.tmatesoft.hg.internal.FileRevisionHistoryChunk;
import org.tmatesoft.hg.internal.diff.BlameHelper;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * 'hg diff' counterpart, with similar, although not identical, functionality.
 * Despite both 'hg diff' and this command are diff-based, implementation
 * peculiarities may lead to slightly different diff results. Either is valid
 * as there's no strict diff specification. 
 * 
 * <p>
 * <strong>Note</strong>, at the moment this command annotates single file only. Diff over
 * complete repository (all the file changed in a given changeset) might
 * be added later.
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgDiffCommand extends HgAbstractCommand<HgDiffCommand> {

	private final HgRepository repo;
	private HgDataFile df;
	private final CsetParamKeeper clogRevIndexStart, clogRevIndexEnd;
	private HgIterateDirection iterateDirection = HgIterateDirection.NewToOld;

	public HgDiffCommand(HgRepository hgRepo) {
		repo = hgRepo;
		clogRevIndexStart = new CsetParamKeeper(hgRepo);
		clogRevIndexEnd = new CsetParamKeeper(hgRepo);
	}
	
	public HgDiffCommand file(Path file) {
		df = repo.getFileNode(file);
		return this;
	}

	/**
	 * Selects the file which history to blame, mandatory.
	 * 
	 * @param file repository file
	 * @return <code>this</code> for convenience
	 */
	public HgDiffCommand file(HgDataFile file) {
		df = file;
		return this;
	}

	/**
	 * Select range of file's history for {@link #executeDiff(HgBlameInspector)}
	 * and {@link #executeAnnotate(HgBlameInspector)}.
	 * <p>
	 * {@link #executeDiff(HgBlameInspector) diff} uses these as revisions to diff against each other, while 
	 * {@link #executeAnnotate(HgBlameInspector) annotate} walks the range. 
	 * 
	 * @param changelogRevIndexStart index of changelog revision, left range boundary
	 * @param changelogRevIndexEnd index of changelog revision, right range boundary
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find any of supplied changeset 
	 */
	public HgDiffCommand range(int changelogRevIndexStart, int changelogRevIndexEnd) throws HgBadArgumentException {
		clogRevIndexStart.set(changelogRevIndexStart);
		clogRevIndexEnd.set(changelogRevIndexEnd);
		return this;
	}
	
	/**
	 * Select range of file history, limited by changesets.
	 * @see #range(int, int)
	 * @param cset1 changelog revision, left range boundary
	 * @param cset2 changelog revision, right range boundary
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if revisions are not valid changeset identifiers
	 */
	public HgDiffCommand range(Nodeid cset1, Nodeid cset2) throws HgBadArgumentException {
		clogRevIndexStart.set(cset1);
		clogRevIndexEnd.set(cset2);
		return this;
	}
	
	/**
	 * Selects revision for {@link #executeParentsAnnotate(HgBlameInspector)}, the one 
	 * to diff against its parents. 
	 * 
	 * Besides, it is handy when range of interest spans up to the very beginning of the file history 
	 * (and thus is equivalent to <code>range(0, changelogRevIndex)</code>)
	 * 
	 * @param changelogRevIndex index of changelog revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset 
	 */
	public HgDiffCommand changeset(int changelogRevIndex) throws HgBadArgumentException {
		clogRevIndexStart.set(0);
		clogRevIndexEnd.set(changelogRevIndex);
		return this;
	}
	
	/**
	 * Select specific changeset or a range [0..changeset], like {@link #changeset(int)}
	 * 
	 * @param nid changeset
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset revision 
	 */
	public HgDiffCommand changeset(Nodeid nid) throws HgBadArgumentException {
		clogRevIndexStart.set(0);
		clogRevIndexEnd.set(nid);
		return this;
	}


	/**
	 * Revision differences are reported in selected order when 
	 * annotating {@link #range(int, int) range} of changesets with
	 * {@link #executeAnnotate(HgBlameInspector)}.
	 * <p>
	 * This method doesn't affect {@link #executeParentsAnnotate(HgBlameInspector)} and
	 * {@link #executeDiff(HgBlameInspector)}
	 * 
	 * @param order desired iteration order 
	 * @return <code>this</code> for convenience
	 */
	public HgDiffCommand order(HgIterateDirection order) {
		iterateDirection = order;
		return this;
	}
	
	/**
	 * Diff two revisions selected with {@link #range(int, int)} against each other.
	 * <p>mimics 'hg diff -r clogRevIndex1 -r clogRevIndex2'
	 * 
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 */
	public void executeDiff(HgBlameInspector insp) throws HgCallbackTargetException, CancelledException, HgException {
		checkFile();
		final ProgressSupport progress = getProgressSupport(insp);
		progress.start(2);
		try {
			final int startRevIndex = clogRevIndexStart.get(0);
			final int endRevIndex = clogRevIndexEnd.get(TIP);
			final CancelSupport cancel = getCancelSupport(insp, true);
			int fileRevIndex1 = fileRevIndex(df, startRevIndex);
			int fileRevIndex2 = fileRevIndex(df, endRevIndex);
			BlameHelper bh = new BlameHelper(insp);
			bh.prepare(df, startRevIndex, endRevIndex);
			progress.worked(1);
			cancel.checkCancelled();
			bh.diff(fileRevIndex1, startRevIndex, fileRevIndex2, endRevIndex);
			progress.worked(1);
			cancel.checkCancelled();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			progress.done();
		}
	}

	/**
	 * Walk file history {@link #range(int, int) range} and report changes (diff) for each revision
	 * 
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 */
	public void executeAnnotate(HgBlameInspector insp) throws HgCallbackTargetException, CancelledException, HgException {
		checkFile();
		ProgressSupport progress = null;
		try {
			if (!df.exists()) {
				return;
			}
			final CancelSupport cancel = getCancelSupport(insp, true);
			BlameHelper bh = new BlameHelper(insp);
			final int startRevIndex = clogRevIndexStart.get(0);
			final int endRevIndex = clogRevIndexEnd.get(TIP);
			FileHistory fileHistory = bh.prepare(df, startRevIndex, endRevIndex);
			//
			cancel.checkCancelled();
			int totalWork = 0;
			for (FileRevisionHistoryChunk fhc : fileHistory.iterate(iterateDirection)) {
				totalWork += fhc.revisionCount();
			}
			progress = getProgressSupport(insp);
			progress.start(totalWork + 1);
			progress.worked(1); // BlameHelper.prepare
			//
			int[] fileClogParentRevs = new int[2];
			int[] fileParentRevs = new int[2];
			for (FileRevisionHistoryChunk fhc : fileHistory.iterate(iterateDirection)) {
				for (int fri : fhc.fileRevisions(iterateDirection)) {
					int clogRevIndex = fhc.changeset(fri);
					// the way we built fileHistory ensures we won't walk past [changelogRevIndexStart..changelogRevIndexEnd]
					assert clogRevIndex >= startRevIndex;
					assert clogRevIndex <= endRevIndex;
					fhc.fillFileParents(fri, fileParentRevs);
					fhc.fillCsetParents(fri, fileClogParentRevs);
					bh.annotateChange(fri, clogRevIndex, fileParentRevs, fileClogParentRevs);
					progress.worked(1);
					cancel.checkCancelled();
				}
			}
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			if (progress != null) {
				progress.done();
			}
		}
	}

	/**
	 * Annotates changes of the file against its parent(s). 
	 * Unlike {@link #annotate(HgDataFile, int, Inspector, HgIterateDirection)}, doesn't
	 * walk file history, looks at the specified revision only. Handles both parents (if merge revision).
	 * 
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 */
	public void executeParentsAnnotate(HgBlameInspector insp) throws HgCallbackTargetException, CancelledException, HgException {
		checkFile();
		final ProgressSupport progress = getProgressSupport(insp);
		progress.start(2);
		try {
			final CancelSupport cancel = getCancelSupport(insp, true);
			int changelogRevisionIndex = clogRevIndexEnd.get(TIP);
			// TODO detect if file is text/binary (e.g. looking for chars < ' ' and not \t\r\n\f
			int fileRevIndex = fileRevIndex(df, changelogRevisionIndex);
			int[] fileRevParents = new int[2];
			df.parents(fileRevIndex, fileRevParents, null, null);
			if (changelogRevisionIndex == TIP) {
				changelogRevisionIndex = df.getChangesetRevisionIndex(fileRevIndex);
			}
			int[] fileClogParentRevs = new int[2];
			fileClogParentRevs[0] = fileRevParents[0] == NO_REVISION ? NO_REVISION : df.getChangesetRevisionIndex(fileRevParents[0]);
			fileClogParentRevs[1] = fileRevParents[1] == NO_REVISION ? NO_REVISION : df.getChangesetRevisionIndex(fileRevParents[1]);
			BlameHelper bh = new BlameHelper(insp);
			int clogIndexStart = fileClogParentRevs[0] == NO_REVISION ? (fileClogParentRevs[1] == NO_REVISION ? 0 : fileClogParentRevs[1]) : fileClogParentRevs[0];
			bh.prepare(df, clogIndexStart, changelogRevisionIndex);
			progress.worked(1);
			cancel.checkCancelled();
			bh.annotateChange(fileRevIndex, changelogRevisionIndex, fileRevParents, fileClogParentRevs);
			progress.worked(1);
			cancel.checkCancelled();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			progress.done();
		}
	}

	private void checkFile() {
		if (df == null) {
			throw new IllegalArgumentException("File is not set");
		}
	}

	private static int fileRevIndex(HgDataFile df, int csetRevIndex) throws HgRuntimeException {
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(csetRevIndex, df.getPath());
		return df.getRevisionIndex(fileRev);
	}
}
