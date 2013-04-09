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
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FileAnnotation;
import org.tmatesoft.hg.internal.FileAnnotation.LineDescriptor;
import org.tmatesoft.hg.internal.FileAnnotation.LineInspector;
import org.tmatesoft.hg.repo.HgBlameFacility.BlockData;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelledException;

/**
 * WORK IN PROGRESS. UNSTABLE API
 * 
 * 'hg annotate' counterpart, report origin revision and file line-by-line 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress. Unstable API")
public class HgAnnotateCommand extends HgAbstractCommand<HgAnnotateCommand> {
	
	private final HgRepository repo;
	private final CsetParamKeeper annotateRevision;
	private HgDataFile file;

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
	
	public HgAnnotateCommand file(HgDataFile fileNode) {
		file = fileNode;
		return this;
	}
	
	// TODO [1.1] set encoding and provide String line content from LineInfo

	// FIXME [1.1] follow and no-follow parameters
	
	public void execute(Inspector inspector) throws HgException, HgCallbackTargetException, CancelledException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		if (file == null) {
			throw new HgBadArgumentException("Command needs file argument", null);
		}
		Collector c = new Collector();
		FileAnnotation.annotate(file, annotateRevision.get(), c);
		LineImpl li = new LineImpl();
		for (int i = 0; i < c.lineRevisions.length; i++) {
			li.init(i+1, c.lineRevisions[i], c.line(i));
			inspector.next(li);
		}
	}
	
	/**
	 * Callback to receive annotated lines
	 */
	@Callback
	public interface Inspector {
		// start(FileDescriptor);
		void next(LineInfo lineInfo);
		// end(FileDescriptor);
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

	// FIXME there's no need in FileAnnotation.LineInspector, merge it here
	private static class Collector implements LineInspector {
		private int[] lineRevisions;
		private byte[][] lines;
		
		Collector() {
		}
		
		public void line(int lineNumber, int changesetRevIndex, BlockData lineContent, LineDescriptor ld) {
			if (lineRevisions == null) {
				lineRevisions = new int [ld.totalLines()];
				Arrays.fill(lineRevisions, NO_REVISION);
				lines = new byte[ld.totalLines()][];
			}
			lineRevisions[lineNumber] = changesetRevIndex;
			lines[lineNumber] = lineContent.asArray();
		}
		
		public byte[] line(int i) {
			return lines[i];
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
