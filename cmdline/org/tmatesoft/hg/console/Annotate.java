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
package org.tmatesoft.hg.console;

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Collections;

import org.tmatesoft.hg.core.HgAnnotateCommand;
import org.tmatesoft.hg.core.HgAnnotateCommand.LineInfo;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.repo.HgDataFile;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Annotate {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, Collections.<String>emptySet());
		HgRepoFacade repo = new HgRepoFacade();
		if (!repo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", repo.getRepository().getLocation());
			return;
		}
		int rev = cmdLineOpts.getSingleInt(TIP, "-r", "--rev");
		HgAnnotateCommand cmd = repo.createAnnotateCommand();
		AnnotateDumpInspector insp = new AnnotateDumpInspector(cmdLineOpts.getBoolean(false, "-l", "--line-number"));
		cmd.changeset(rev);
		for (String fname : cmdLineOpts.getList("")) {
			HgDataFile fn = repo.getRepository().getFileNode(fname);
			cmd.file(fn);
			cmd.execute(insp);
		}
	}

	private static class AnnotateDumpInspector implements HgAnnotateCommand.Inspector {
		private final boolean lineNumbers;
		
		public AnnotateDumpInspector(boolean printLineNumbers) {
			lineNumbers = printLineNumbers;
		}

		public void next(LineInfo lineInfo) {
			if (lineNumbers) {
				System.out.printf("%3d:%3d:%s", lineInfo.getChangesetIndex(), lineInfo.getLineNumber(), new String(lineInfo.getContent()));
			} else {
				System.out.printf("%3d:%s", lineInfo.getChangesetIndex(), new String(lineInfo.getContent()));
			}
		}
	}
}
