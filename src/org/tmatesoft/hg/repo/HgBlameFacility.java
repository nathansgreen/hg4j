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

import static org.tmatesoft.hg.core.HgIterateDirection.NewToOld;
import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;
import static org.tmatesoft.hg.repo.HgInternals.wrongRevisionIndex;
import static org.tmatesoft.hg.repo.HgRepository.*;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedList;

import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.BlameHelper;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.util.Adaptable;

/**
 * Facility with diff/annotate functionality.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Unstable API")
public final class HgBlameFacility {
	private final HgDataFile df;
	
	public HgBlameFacility(HgDataFile file) {
		if (file == null) {
			throw new IllegalArgumentException();
		}
		df = file;
	}
	
	/**
	 * mimic 'hg diff -r clogRevIndex1 -r clogRevIndex2'
	 */
	public void diff(int clogRevIndex1, int clogRevIndex2, Inspector insp) throws HgCallbackTargetException {
		// FIXME clogRevIndex1 and clogRevIndex2 may point to different files, need to decide whether to throw an exception
		// or to attempt to look up correct file node (tricky)
		int fileRevIndex1 = fileRevIndex(df, clogRevIndex1);
		int fileRevIndex2 = fileRevIndex(df, clogRevIndex2);
		BlameHelper bh = new BlameHelper(insp, 5);
		bh.useFileUpTo(df, clogRevIndex2);
		bh.diff(fileRevIndex1, clogRevIndex1, fileRevIndex2, clogRevIndex2);
	}
	
	/**
	 * Walk file history up/down to revision at given changeset and report changes for each revision
	 */
	public void annotate(int changelogRevisionIndex, Inspector insp, HgIterateDirection iterateOrder) throws HgCallbackTargetException {
		annotate(0, changelogRevisionIndex, insp, iterateOrder);
	}

	/**
	 * Walk file history range and report changes for each revision
	 */
	public void annotate(int changelogRevIndexStart, int changelogRevIndexEnd, Inspector insp, HgIterateDirection iterateOrder) throws HgCallbackTargetException {
		if (wrongRevisionIndex(changelogRevIndexStart) || wrongRevisionIndex(changelogRevIndexEnd)) {
			throw new IllegalArgumentException();
		}
		// Note, changelogRevisionIndex may be TIP, while the code below doesn't tolerate constants
		//
		int lastRevision = df.getRepo().getChangelog().getLastRevision();
		if (changelogRevIndexEnd == TIP) {
			changelogRevIndexEnd = lastRevision;
		}
		HgInternals.checkRevlogRange(changelogRevIndexStart, changelogRevIndexEnd, lastRevision);
		if (!df.exists()) {
			return;
		}
		BlameHelper bh = new BlameHelper(insp, 10);
		HgDataFile currentFile = df;
		int fileLastClogRevIndex = changelogRevIndexEnd;
		FileRevisionHistoryChunk nextChunk = null;
		LinkedList<FileRevisionHistoryChunk> fileCompleteHistory = new LinkedList<FileRevisionHistoryChunk>();
		do {
			FileRevisionHistoryChunk fileHistory = new FileRevisionHistoryChunk(currentFile);
			fileHistory.init(fileLastClogRevIndex);
			fileHistory.linkTo(nextChunk);
			fileCompleteHistory.addFirst(fileHistory); // to get the list in old-to-new order
			nextChunk = fileHistory;
			bh.useFileUpTo(currentFile, fileLastClogRevIndex);
			if (currentFile.isCopy()) {
				// TODO SessionContext.getPathFactory() and replace all Path.create
				HgRepository repo = currentFile.getRepo();
				Nodeid originLastRev = currentFile.getCopySourceRevision();
				currentFile = repo.getFileNode(currentFile.getCopySourceName());
				fileLastClogRevIndex = currentFile.getChangesetRevisionIndex(currentFile.getRevisionIndex(originLastRev));
				// XXX perhaps, shall fail with meaningful exception if new file doesn't exist (.i/.d not found for whatever reason)
				// or source revision is missing?
			} else {
				currentFile = null; // stop iterating
			}
		} while (currentFile != null && fileLastClogRevIndex >= changelogRevIndexStart);
		// fileCompleteHistory is in (origin, intermediate target, ultimate target) order

		int[] fileClogParentRevs = new int[2];
		int[] fileParentRevs = new int[2];
		if (iterateOrder == NewToOld) {
			Collections.reverse(fileCompleteHistory);
		}
		boolean shallFilterStart = changelogRevIndexStart != 0; // no reason if complete history is walked
		for (FileRevisionHistoryChunk fileHistory : fileCompleteHistory) {
			for (int fri : fileHistory.fileRevisions(iterateOrder)) {
				int clogRevIndex = fileHistory.changeset(fri);
				if (shallFilterStart) {
					if (iterateOrder == NewToOld) {
						// clogRevIndex decreases
						if (clogRevIndex < changelogRevIndexStart) {
							break;
						}
						// fall-through, clogRevIndex is in the [start..end] range
					} else { // old to new
						// the way we built fileHistory ensures we won't walk past changelogRevIndexEnd
						// here we ensure we start from the right one, the one indicated with changelogRevIndexStart
						if (clogRevIndex < changelogRevIndexStart) {
							continue;
						} else {
							shallFilterStart = false; // once boundary is crossed, no need to check
							// fall-through
						}
					}
				}
				fileHistory.fillFileParents(fri, fileParentRevs);
				fileHistory.fillCsetParents(fri, fileClogParentRevs);
				bh.annotateChange(fri, clogRevIndex, fileParentRevs, fileClogParentRevs);
			}
		}
	}

	/**
	 * Annotates changes of the file against its parent(s). 
	 * Unlike {@link #annotate(HgDataFile, int, Inspector, HgIterateDirection)}, doesn't
	 * walk file history, looks at the specified revision only. Handles both parents (if merge revision).
	 */
	public void annotateSingleRevision(int changelogRevisionIndex, Inspector insp) throws HgCallbackTargetException {
		// TODO detect if file is text/binary (e.g. looking for chars < ' ' and not \t\r\n\f
		int fileRevIndex = fileRevIndex(df, changelogRevisionIndex);
		int[] fileRevParents = new int[2];
		df.parents(fileRevIndex, fileRevParents, null, null);
		if (changelogRevisionIndex == TIP) {
			changelogRevisionIndex = df.getChangesetRevisionIndex(fileRevIndex);
		}
		BlameHelper bh = new BlameHelper(insp, 5);
		bh.useFileUpTo(df, changelogRevisionIndex);
		int[] fileClogParentRevs = new int[2];
		fileClogParentRevs[0] = fileRevParents[0] == NO_REVISION ? NO_REVISION : df.getChangesetRevisionIndex(fileRevParents[0]);
		fileClogParentRevs[1] = fileRevParents[1] == NO_REVISION ? NO_REVISION : df.getChangesetRevisionIndex(fileRevParents[1]);
		bh.annotateChange(fileRevIndex, changelogRevisionIndex, fileRevParents, fileClogParentRevs);
	}

	/**
	 * Client's sink for revision differences.
	 * 
	 * When implemented, clients shall not expect new {@link Block blocks} instances in each call.
	 * 
	 * In case more information about annotated revision is needed, inspector instances may supply 
	 * {@link RevisionDescriptor.Recipient} through {@link Adaptable}.  
	 */
	@Callback
	public interface Inspector {
		void same(EqualBlock block) throws HgCallbackTargetException;
		void added(AddBlock block) throws HgCallbackTargetException;
		void changed(ChangeBlock block) throws HgCallbackTargetException;
		void deleted(DeleteBlock block) throws HgCallbackTargetException;
	}
	
	/**
	 * No need to keep "Block" prefix as long as there's only one {@link Inspector}
	 */
	@Deprecated
	public interface BlockInspector extends Inspector {
	}
	
	/**
	 * Represents content of a block, either as a sequence of bytes or a 
	 * sequence of smaller blocks (lines), if appropriate (according to usage context).
	 * 
	 * This approach allows line-by-line access to content data along with complete byte sequence for the whole block, i.e.
	 * <pre>
	 *    BlockData bd = addBlock.addedLines()
	 *    // bd describes data from the addition completely.
	 *    // elements of the BlockData are lines
	 *    bd.elementCount() == addBlock.totalAddedLines();
	 *    // one cat obtain complete addition with
	 *    byte[] everythingAdded = bd.asArray();
	 *    // or iterate line by line
	 *    for (int i = 0; i < bd.elementCount(); i++) {
	 *    	 byte[] lineContent = bd.elementAt(i);
	 *       String line = new String(lineContent, fileEncodingCharset);
	 *    }
	 *    where bd.elementAt(0) is the line at index addBlock.firstAddedLine() 
	 * </pre> 
	 * 
	 * LineData or ChunkData? 
	 */
	public interface BlockData {
		BlockData elementAt(int index);
		int elementCount();
		byte[] asArray();
	}
	
	/**
	 * {@link Inspector} may optionally request extra information about revisions
	 * being inspected, denoting itself as a {@link RevisionDescriptor.Recipient}. This class 
	 * provides complete information about file revision under annotation now. 
	 */
	public interface RevisionDescriptor {
		/**
		 * @return complete source of the diff origin, never <code>null</code>
		 */
		BlockData origin();
		/**
		 * @return complete source of the diff target, never <code>null</code>
		 */
		BlockData target();
		/**
		 * @return changeset revision index of original file, or {@link HgRepository#NO_REVISION} if it's the very first revision
		 */
		int originChangesetIndex();
		/**
		 * @return changeset revision index of the target file
		 */
		int targetChangesetIndex();
		/**
		 * @return <code>true</code> if this revision is merge
		 */
		boolean isMerge();
		/**
		 * @return changeset revision index of the second, merged parent
		 */
		int mergeChangesetIndex();
		/**
		 * @return revision index of the change in target file's revlog
		 */
		int fileRevisionIndex();

		/**
		 * @return file object under blame (target file)
		 */
		HgDataFile file();

		/**
		 * Implement to indicate interest in {@link RevisionDescriptor}.
		 * 
		 * Note, instance of {@link RevisionDescriptor} is the same for 
		 * {@link #start(RevisionDescriptor)} and {@link #done(RevisionDescriptor)} 
		 * methods, and not necessarily a new one (i.e. <code>==</code>) for the next
		 * revision announced.
		 */
		@Callback
		public interface Recipient {
			/**
			 * Comes prior to any change {@link Block blocks}
			 */
			void start(RevisionDescriptor revisionDescription) throws HgCallbackTargetException;
			/**
			 * Comes after all change {@link Block blocks} were dispatched
			 */
			void done(RevisionDescriptor revisionDescription) throws HgCallbackTargetException;
		}
	}
	
	/**
	 * Each change block comes from a single origin, blocks that are result of a merge
	 * have {@link #originChangesetIndex()} equal to {@link RevisionDescriptor#mergeChangesetIndex()}.
	 */
	public interface Block {
		int originChangesetIndex();
		int targetChangesetIndex();
	}
	
	public interface EqualBlock extends Block {
		int originStart();
		int targetStart();
		int length();
		BlockData content();
	}
	
	public interface AddBlock extends Block {
		/**
		 * @return line index in the origin where this block is inserted
		 */
		int insertedAt();  
		/**
		 * @return line index of the first added line in the target revision
		 */
		int firstAddedLine();
		/**
		 * @return number of added lines in this block
		 */
		int totalAddedLines();
		/**
		 * @return content of added lines
		 */
		BlockData addedLines();
	}
	public interface DeleteBlock extends Block {
		/**
		 * @return line index in the target revision were this deleted block would be
		 */
		int removedAt();
		/**
		 * @return line index of the first removed line in the original revision
		 */
		int firstRemovedLine();
		/**
		 * @return number of deleted lines in this block
		 */
		int totalRemovedLines();
		/**
		 * @return content of deleted lines
		 */
		BlockData removedLines();
	}
	public interface ChangeBlock extends AddBlock, DeleteBlock {
	}


	private static int fileRevIndex(HgDataFile df, int csetRevIndex) {
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(csetRevIndex, df.getPath());
		return df.getRevisionIndex(fileRev);
	}
	
	private static class FileRevisionHistoryChunk {
		private final HgDataFile df;
		// change ancestry, sequence of file revisions
		private IntVector fileRevsToVisit;
		// parent pairs of complete file history
		private IntVector fileParentRevs;
		// map file revision to changelog revision (sparse array, only file revisions to visit are set)
		private int[] file2changelog;
		private int originChangelogRev = BAD_REVISION, originFileRev = BAD_REVISION;

		public FileRevisionHistoryChunk(HgDataFile file) {
			df = file;
		}
		
		public void init(int changelogRevisionIndex) {
			// XXX df.indexWalk(0, fileRevIndex, ) might be more effective
			int fileRevIndex = fileRevIndex(df, changelogRevisionIndex);
			int[] fileRevParents = new int[2];
			fileParentRevs = new IntVector((fileRevIndex+1) * 2, 0);
			fileParentRevs.add(NO_REVISION, NO_REVISION); // parents of fileRevIndex == 0
			for (int i = 1; i <= fileRevIndex; i++) {
				df.parents(i, fileRevParents, null, null);
				fileParentRevs.add(fileRevParents[0], fileRevParents[1]);
			}
			// fileRevsToVisit keep file change ancestry from new to old
			fileRevsToVisit = new IntVector(fileRevIndex + 1, 0);
			// keep map of file revision to changelog revision
			file2changelog = new int[fileRevIndex+1];
			// only elements worth visit would get mapped, so there would be unfilled areas in the file2changelog,
			// prevent from error (make it explicit) by bad value
			Arrays.fill(file2changelog, BAD_REVISION);
			LinkedList<Integer> queue = new LinkedList<Integer>();
			BitSet seen = new BitSet(fileRevIndex + 1);
			queue.add(fileRevIndex);
			do {
				int x = queue.removeFirst();
				if (seen.get(x)) {
					continue;
				}
				seen.set(x);
				fileRevsToVisit.add(x);
				file2changelog[x] = df.getChangesetRevisionIndex(x);
				int p1 = fileParentRevs.get(2*x);
				int p2 = fileParentRevs.get(2*x + 1);
				if (p1 != NO_REVISION) {
					queue.addLast(p1);
				}
				if (p2 != NO_REVISION) {
					queue.addLast(p2);
				}
			} while (!queue.isEmpty());
			// make sure no child is processed before we handled all (grand-)parents of the element
			fileRevsToVisit.sort(false);
		}
		
		public void linkTo(FileRevisionHistoryChunk target) {
			// assume that target.init() has been called already 
			if (target == null) {
				return;
			}
			target.originFileRev = fileRevsToVisit.get(0); // files to visit are new to old
			target.originChangelogRev = changeset(target.originFileRev);
		}
		
		public int[] fileRevisions(HgIterateDirection iterateOrder) {
			// fileRevsToVisit is { r10, r7, r6, r5, r0 }, new to old
			int[] rv = fileRevsToVisit.toArray();
			if (iterateOrder == OldToNew) {
				// reverse return value
				for (int a = 0, b = rv.length-1; a < b; a++, b--) {
					int t = rv[b];
					rv[b] = rv[a];
					rv[a] = t;
				}
			}
			return rv;
		}
		
		public int changeset(int fileRevIndex) {
			return file2changelog[fileRevIndex];
		}
		
		public void fillFileParents(int fileRevIndex, int[] fileParents) {
			if (fileRevIndex == 0 && originFileRev != BAD_REVISION) {
				// this chunk continues another file
				assert originFileRev != NO_REVISION;
				fileParents[0] = originFileRev;
				fileParents[1] = NO_REVISION;
				return;
			}
			fileParents[0] = fileParentRevs.get(fileRevIndex * 2);
			fileParents[1] = fileParentRevs.get(fileRevIndex * 2 + 1);
		}
		
		public void fillCsetParents(int fileRevIndex, int[] csetParents) {
			if (fileRevIndex == 0 && originFileRev != BAD_REVISION) {
				assert originFileRev != NO_REVISION;
				csetParents[0] = originChangelogRev;
				csetParents[1] = NO_REVISION; // I wonder if possible to start a copy with two parents?
				return;
			}
			int fp1 = fileParentRevs.get(fileRevIndex * 2);
			int fp2 = fileParentRevs.get(fileRevIndex * 2 + 1);
			csetParents[0] = fp1 == NO_REVISION ? NO_REVISION : changeset(fp1);
			csetParents[1] = fp2 == NO_REVISION ? NO_REVISION : changeset(fp2);
		}
	}
}
