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

import static org.tmatesoft.hg.repo.HgInternals.wrongRevisionIndex;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.internal.BlameHelper;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FileHistory;
import org.tmatesoft.hg.internal.FileRevisionHistoryChunk;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;

/**
 * 'hg diff' counterpart, with similar, diff-based, functionality
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="#execute* methods might get renamed")
public class HgDiffCommand extends HgAbstractCommand<HgDiffCommand> {

	private final HgRepository repo;
	private HgDataFile df;
	private int clogRevIndexStart, clogRevIndexEnd;
	private int clogRevIndexToParents;
	private HgIterateDirection iterateDirection = HgIterateDirection.NewToOld;

	public HgDiffCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	public HgDiffCommand file(Path file) {
		df = repo.getFileNode(file);
		return this;
	}

	public HgDiffCommand file(HgDataFile file) {
		df = file;
		return this;
	}

	public HgDiffCommand range(int changelogRevIndexStart, int changelogRevIndexEnd) {
		clogRevIndexStart = changelogRevIndexStart;
		clogRevIndexEnd = changelogRevIndexEnd;
		return this;
	}
	
	// FIXME javadoc when needed and difference with range
	public HgDiffCommand changeset(int changelogRevIndex) {
		clogRevIndexToParents = changelogRevIndex;
		return this;
	}

	// FIXME javadoc when needed
	public HgDiffCommand order(HgIterateDirection order) {
		iterateDirection = order;
		return this;
	}

	// FIXME progress and cancellation
	
	/**
	 * mimic 'hg diff -r clogRevIndex1 -r clogRevIndex2'
	 */
	public void executeDiff(HgBlameInspector insp) throws HgCallbackTargetException, CancelledException, HgException {
		try {
			int fileRevIndex1 = fileRevIndex(df, clogRevIndexStart);
			int fileRevIndex2 = fileRevIndex(df, clogRevIndexEnd);
			BlameHelper bh = new BlameHelper(insp);
			bh.prepare(df, clogRevIndexStart, clogRevIndexEnd);
			bh.diff(fileRevIndex1, clogRevIndexStart, fileRevIndex2, clogRevIndexEnd);
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}

	/**
	 * Walk file history range and report changes (diff) for each revision
	 */
	public void executeAnnotate(HgBlameInspector insp) throws HgCallbackTargetException, CancelledException, HgException {
		if (wrongRevisionIndex(clogRevIndexStart) || wrongRevisionIndex(clogRevIndexEnd)) {
			throw new IllegalArgumentException();
		}
		// FIXME check file and range are set
		try {
			// Note, changelogRevIndexEnd may be TIP, while the code below doesn't tolerate constants
			//
			int lastRevision = repo.getChangelog().getLastRevision();
			if (clogRevIndexEnd == TIP) {
				clogRevIndexEnd = lastRevision;
			}
			HgInternals.checkRevlogRange(clogRevIndexStart, clogRevIndexEnd, lastRevision);
			if (!df.exists()) {
				return;
			}
			BlameHelper bh = new BlameHelper(insp);
			FileHistory fileHistory = bh.prepare(df, clogRevIndexStart, clogRevIndexEnd);
	
			int[] fileClogParentRevs = new int[2];
			int[] fileParentRevs = new int[2];
			for (FileRevisionHistoryChunk fhc : fileHistory.iterate(iterateDirection)) {
				for (int fri : fhc.fileRevisions(iterateDirection)) {
					int clogRevIndex = fhc.changeset(fri);
					// the way we built fileHistory ensures we won't walk past [changelogRevIndexStart..changelogRevIndexEnd]
					assert clogRevIndex >= clogRevIndexStart;
					assert clogRevIndex <= clogRevIndexEnd;
					fhc.fillFileParents(fri, fileParentRevs);
					fhc.fillCsetParents(fri, fileClogParentRevs);
					bh.annotateChange(fri, clogRevIndex, fileParentRevs, fileClogParentRevs);
				}
			}
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}

	/**
	 * Annotates changes of the file against its parent(s). 
	 * Unlike {@link #annotate(HgDataFile, int, Inspector, HgIterateDirection)}, doesn't
	 * walk file history, looks at the specified revision only. Handles both parents (if merge revision).
	 */
	public void executeAnnotateSingleRevision(HgBlameInspector insp) throws HgCallbackTargetException, CancelledException, HgException {
		try {
			int changelogRevisionIndex = clogRevIndexToParents;
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
			bh.annotateChange(fileRevIndex, changelogRevisionIndex, fileRevParents, fileClogParentRevs);
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}


	private static int fileRevIndex(HgDataFile df, int csetRevIndex) throws HgRuntimeException {
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(csetRevIndex, df.getPath());
		return df.getRevisionIndex(fileRev);
	}
}
