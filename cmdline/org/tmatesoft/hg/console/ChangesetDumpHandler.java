/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ChangesetDumpHandler implements HgChangesetHandler {
	// params
	private boolean complete = false; // roughly --debug
	private boolean reverseOrder = false;
	private boolean verbose = false; // roughly -v
	// own
	private LinkedList<String> l = new LinkedList<String>();
	private final HgRepository repo;
	private final int tip;

	public ChangesetDumpHandler(HgRepository hgRepo) {
		repo = hgRepo;
		tip = hgRepo.getChangelog().getLastRevision();
	}

	public ChangesetDumpHandler complete(boolean b) {
		complete = b;
		return this;
	}

	public ChangesetDumpHandler reversed(boolean b) {
		reverseOrder = b;
		return this;
	}

	public ChangesetDumpHandler verbose(boolean b) {
		verbose = b;
		return this;
	}

	public void cset(HgChangeset changeset) {
		try {
			final String s = print(changeset);
			if (reverseOrder) {
				// XXX in fact, need to insert s into l according to changeset.getRevision()
				// because when file history is being followed, revisions of the original file (with smaller revNumber)
				// are reported *after* revisions of present file and with addFirst appear above them
				l.addFirst(s);
			} else {
				System.out.print(s);
			}
		} catch (HgException ex) {
			ex.printStackTrace();
		}
	}

	public void done() {
		if (!reverseOrder) {
			return;
		}
		for (String s : l) {
			System.out.print(s);
		}
		l.clear();
	}

	private String print(HgChangeset cset) throws HgException {
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);
		final Nodeid csetNodeid = cset.getNodeid();
		f.format("changeset:   %d:%s\n", cset.getRevisionIndex(), complete ? csetNodeid : csetNodeid.shortNotation());
		if (cset.getRevisionIndex() == tip || repo.getTags().isTagged(csetNodeid)) {
			sb.append("tag:         ");
			for (String t : repo.getTags().tags(csetNodeid)) {
				sb.append(t);
				sb.append(' ');
			}
			if (cset.getRevisionIndex() == tip) {
				sb.append("tip");
			}
			sb.append('\n');
		}
		if (complete) {
			f.format("phase:       %s\n", cset.getPhase().name());
			Nodeid p1 = cset.getFirstParentRevision();
			Nodeid p2 = cset.getSecondParentRevision();
			int p1x = p1.isNull() ? -1 : repo.getChangelog().getRevisionIndex(p1);
			int p2x = p2.isNull() ? -1 : repo.getChangelog().getRevisionIndex(p2);
			int mx = repo.getManifest().getRevisionIndex(cset.getManifestRevision());
			f.format("parent:      %d:%s\nparent:      %d:%s\nmanifest:    %d:%s\n", p1x, p1, p2x, p2, mx, cset.getManifestRevision());
		}
		f.format("user:        %s\ndate:        %s\n", cset.getUser(), cset.getDate().toString());
		if (!complete && verbose) {
			final List<Path> files = cset.getAffectedFiles();
			sb.append("files:      ");
			for (Path s : files) {
				sb.append(' ');
				sb.append(s);
			}
			sb.append('\n');
		}
		if (complete) {
			if (!cset.getModifiedFiles().isEmpty()) {
				sb.append("files:      ");
				for (HgFileRevision s : cset.getModifiedFiles()) {
					sb.append(' ');
					sb.append(s.getPath());
				}
				sb.append('\n');
			}
			if (!cset.getAddedFiles().isEmpty()) {
				sb.append("files+:     ");
				for (HgFileRevision s : cset.getAddedFiles()) {
					sb.append(' ');
					sb.append(s.getPath());
				}
				sb.append('\n');
			}
			if (!cset.getRemovedFiles().isEmpty()) {
				sb.append("files-:     ");
				for (Path s : cset.getRemovedFiles()) {
					sb.append(' ');
					sb.append(s);
				}
				sb.append('\n');
			}
			// if (cset.extras() != null) {
			// sb.append("extra:      ");
			// for (Map.Entry<String, String> e : cset.extras().entrySet()) {
			// sb.append(' ');
			// sb.append(e.getKey());
			// sb.append('=');
			// sb.append(e.getValue());
			// }
			// sb.append('\n');
			// }
		}
		if (complete || verbose) {
			f.format("description:\n%s\n\n", cset.getComment());
		} else {
			f.format("summary:     %s\n\n", cset.getComment());
		}
		return sb.toString();
	}
}
