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

import java.util.Arrays;

import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.FileAnnotation;
import org.tmatesoft.hg.internal.FileAnnotation.LineDescriptor;
import org.tmatesoft.hg.internal.FileAnnotation.LineInspector;
import org.tmatesoft.hg.repo.HgBlameInspector.BlockData;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
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
		annotateRevision.doSet(HgRepository.TIP);
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
	
	// TODO [post-1.1] set encoding and provide String line content from LineInfo

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
		progress.start(2);
		HgDataFile df = repo.getFileNode(file);
		if (!df.exists()) {
			return;
		}
		final int changesetStart = followRename ? 0 : df.getChangesetRevisionIndex(0);
		Collector c = new Collector(cancellation);
		FileAnnotation fa = new FileAnnotation(c);
		df.annotate(changesetStart, annotateRevision.get(), fa, HgIterateDirection.NewToOld);
		progress.worked(1);
		c.throwIfCancelled();
		cancellation.checkCancelled();
		ProgressSupport.Sub subProgress = new ProgressSupport.Sub(progress, 1);
		subProgress.start(c.lineRevisions.length);
		LineImpl li = new LineImpl();
		for (int i = 0; i < c.lineRevisions.length; i++) {
			li.init(i+1, c.lineRevisions[i], c.line(i));
			inspector.next(li);
			subProgress.worked(1);
			cancellation.checkCancelled();
		}
		subProgress.done();
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
		int getLineNumber();
		int getChangesetIndex();
		byte[] getContent();
	}

	// TODO [post-1.1] there's no need in FileAnnotation.LineInspector, merge it here
	// ok for 1.1 as this LineInspector is internal class
	private static class Collector implements LineInspector {
		private int[] lineRevisions;
		private byte[][] lines;
		private final CancelSupport cancelSupport;
		private CancelledException cancelEx;
		
		Collector(CancelSupport cancellation) {
			cancelSupport = cancellation;
		}
		
		public void line(int lineNumber, int changesetRevIndex, BlockData lineContent, LineDescriptor ld) {
			if (cancelEx != null) {
				return;
			}
			if (lineRevisions == null) {
				lineRevisions = new int [ld.totalLines()];
				Arrays.fill(lineRevisions, NO_REVISION);
				lines = new byte[ld.totalLines()][];
			}
			lineRevisions[lineNumber] = changesetRevIndex;
			lines[lineNumber] = lineContent.asArray();
			try {
				cancelSupport.checkCancelled();
			} catch (CancelledException ex) {
				cancelEx = ex;
			}
		}
		
		public byte[] line(int i) {
			return lines[i];
		}
		
		public void throwIfCancelled() throws CancelledException {
			if (cancelEx != null) {
				throw cancelEx;
			}
		}
	}
	
	
	private static class LineImpl implements LineInfo {
		private int ln;
		private int rev;
		private byte[] content;

		void init(int line, int csetRev, byte[] cnt) {
			ln = line;
			rev = csetRev;
			content = cnt;
		}

		public int getLineNumber() {
			return ln;
		}

		public int getChangesetIndex() {
			return rev;
		}

		public byte[] getContent() {
			return content;
		}
	}
}
