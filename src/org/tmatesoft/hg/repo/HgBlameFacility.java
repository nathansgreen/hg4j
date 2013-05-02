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

import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;
import static org.tmatesoft.hg.repo.HgInternals.wrongRevisionIndex;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.BlameHelper;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FileHistory;
import org.tmatesoft.hg.internal.FileRevisionHistoryChunk;
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
		// Note, changelogRevIndexEnd may be TIP, while the code below doesn't tolerate constants
		//
		int lastRevision = df.getRepo().getChangelog().getLastRevision();
		if (changelogRevIndexEnd == TIP) {
			changelogRevIndexEnd = lastRevision;
		}
		HgInternals.checkRevlogRange(changelogRevIndexStart, changelogRevIndexEnd, lastRevision);
		if (!df.exists()) {
			return;
		}
		FileHistory fileHistory = new FileHistory(df, changelogRevIndexStart, changelogRevIndexEnd);
		fileHistory.build();
		BlameHelper bh = new BlameHelper(insp, 10);
		for (FileRevisionHistoryChunk fhc : fileHistory.iterate(OldToNew)) {
			// iteration order is not important here
			bh.useFileUpTo(fhc.getFile(), fhc.getEndChangeset());
		}
		int[] fileClogParentRevs = new int[2];
		int[] fileParentRevs = new int[2];
		for (FileRevisionHistoryChunk fhc : fileHistory.iterate(iterateOrder)) {
			for (int fri : fhc.fileRevisions(iterateOrder)) {
				int clogRevIndex = fhc.changeset(fri);
				// the way we built fileHistory ensures we won't walk past [changelogRevIndexStart..changelogRevIndexEnd]
				assert clogRevIndex >= changelogRevIndexStart;
				assert clogRevIndex <= changelogRevIndexEnd;
				fhc.fillFileParents(fri, fileParentRevs);
				fhc.fillCsetParents(fri, fileClogParentRevs);
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
}
