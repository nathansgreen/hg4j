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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.diff.ForwardAnnotateInspector;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * 'hg annotate' counterpart, report origin revision and file line-by-line 
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgAnnotateCommand extends HgAbstractCommand<HgAnnotateCommand> {
	
	private final HgRepository repo;
	private final CsetParamKeeper annotateRevision;
	private Path file;
	private boolean followRename;

	public HgAnnotateCommand(HgRepository hgRepo) {
		repo = hgRepo;
		annotateRevision = new CsetParamKeeper(repo);
		annotateRevision.doSet(TIP);
	}

	public HgAnnotateCommand changeset(Nodeid nodeid) throws HgBadArgumentException {
		annotateRevision.set(nodeid);
		return this;
	}
	
	public HgAnnotateCommand changeset(int changelogRevIndex) throws HgBadArgumentException {
		annotateRevision.set(changelogRevIndex);
		return this;
	}
	
	/**
	 * Select file to annotate, origin of renamed/copied file would be followed, too.
	 *  
	 * @param filePath path relative to repository root
	 * @return <code>this</code> for convenience
	 */
	public HgAnnotateCommand file(Path filePath) {
		return file(filePath, true);
	}

	/**
	 * Select file to annotate.
	 * 
	 * @param filePath path relative to repository root
	 * @param followCopyRename true to follow copies/renames.
	 * @return <code>this</code> for convenience
	 */
	public HgAnnotateCommand file(Path filePath, boolean followCopyRename) {
		file = filePath;
		followRename = followCopyRename;
		return this;
	}
	
	
	/**
	 * Select file to annotate,
	 * @param fileNode repository file to annotate 
	 * @param followCopyRename true to follow copies/renames.
	 * @return <code>this</code> for convenience
	 */
	public HgAnnotateCommand file(HgDataFile fileNode, boolean followCopyRename) {
		return file(fileNode.getPath(), followCopyRename);
	}

	// TODO [post-1.1] set encoding and provide String line content from LineInfo
	// TODO FWIW: diff algorithms: http://bramcohen.livejournal.com/73318.html

	/**
	 * Annotate selected file
	 * 
	 * @param inspector
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws HgCallbackTargetException
	 * @throws CancelledException if execution of the command was cancelled
	 */
	public void execute(Inspector inspector) throws HgException, HgCallbackTargetException, CancelledException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		if (file == null) {
			throw new HgBadArgumentException("Command needs file argument", null);
		}
		final ProgressSupport progress = getProgressSupport(inspector);
		final CancelSupport cancellation = getCancelSupport(inspector, true);
		cancellation.checkCancelled();
		progress.start(200);
		try {
			HgDataFile df = repo.getFileNode(file);
			if (!df.exists()) {
				return;
			}
			final int changesetStart = followRename ? 0 : df.getChangesetRevisionIndex(0);
			final int annotateRevIndex = annotateRevision.get(TIP);
			HgDiffCommand cmd = new HgDiffCommand(repo).file(df);
			cmd.range(changesetStart, annotateRevIndex);
			cmd.set(cancellation);
			cmd.set(new ProgressSupport.Sub(progress, 100));
			//
//			ReverseAnnotateInspector ai = new ReverseAnnotateInspector();
			ForwardAnnotateInspector ai = new ForwardAnnotateInspector();
			cmd.order(ai.iterateDirection());
			//
			cmd.executeAnnotate(ai);
			cancellation.checkCancelled();
			final int lastCsetWithFileChange;
			Nodeid fileRev = repo.getManifest().getFileRevision(annotateRevIndex, df.getPath());
			if (fileRev != null) {
				lastCsetWithFileChange = df.getChangesetRevisionIndex(df.getRevisionIndex(fileRev));
			} else {
				lastCsetWithFileChange = annotateRevIndex;
			}
			ai.report(lastCsetWithFileChange, inspector, new ProgressSupport.Sub(progress, 100), cancellation);
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
		progress.done();
	}
	
	/**
	 * Callback to receive annotated lines
	 */
	@Callback
	public interface Inspector {
		// start(FileDescriptor) throws HgCallbackTargetException;
		void next(LineInfo lineInfo) throws HgCallbackTargetException;
		// end(FileDescriptor) throws HgCallbackTargetException;
	}
	
	/**
	 * Describes a line reported through {@link Inspector#next(LineInfo)}
	 * 
	 * Clients shall not implement this interface
	 */
	public interface LineInfo {
		/**
		 * @return 1-based index of the line in the annotated revision
		 */
		int getLineNumber();

		/**
		 * @return 1-based line number at the first appearance, at changeset {@link #getChangesetIndex()} 
		 */
		int getOriginLineNumber();
		/**
		 * @return changeset revision this line was introduced at
		 */
		int getChangesetIndex();

		/**
		 * @return line content
		 */
		byte[] getContent();
	}
}
