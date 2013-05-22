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

import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.util.Adaptable;

/**
 * Client's sink for revision differences, diff/annotate functionality.
 * 
 * When implemented, clients shall not expect new {@link Block blocks} instances in each call.
 * 
 * In case more information about annotated revision is needed, inspector instances may supply 
 * {@link RevisionDescriptor.Recipient} through {@link Adaptable}.  
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 * @since 1.1
 */
@Callback
@Experimental(reason="Unstable API")
public interface HgBlameInspector {

	void same(EqualBlock block) throws HgCallbackTargetException;
	void added(AddBlock block) throws HgCallbackTargetException;
	void changed(ChangeBlock block) throws HgCallbackTargetException;
	void deleted(DeleteBlock block) throws HgCallbackTargetException;
	
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
	 * {@link HgBlameInspector} may optionally request extra information about revisions
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
}
