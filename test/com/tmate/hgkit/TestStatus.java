/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit;

import java.io.File;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import com.tmate.hgkit.fs.FileWalker;
import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.StatusCollector;
import com.tmate.hgkit.ll.WorkingCopyStatusCollector;

/**
 *
 * @author artem
 */
public class TestStatus {

	public static void main(String[] args) throws Exception {
		final StatusOutputParser statusParser = new StatusOutputParser();
		ExecHelper eh = new ExecHelper(statusParser, null);
		eh.run("hg", "status", "-A");
		// run java equivalent
		HgRepository repo = new RepositoryLookup().detectFromWorkingDir();
		final WorkingCopyStatusCollector wcc = new WorkingCopyStatusCollector(repo, new FileWalker(new File(System.getProperty("user.dir"))));
		StatusCollector.Record r = wcc.status(HgRepository.TIP);
		// compare result
		reportNotEqual("MODIFIED", r.getModified(), statusParser.getModified());
		reportNotEqual("ADDED", r.getAdded(), statusParser.getAdded());
		reportNotEqual("REMOVED", r.getRemoved(), statusParser.getRemoved());
		reportNotEqual("CLEAN", r.getClean(), statusParser.getClean());
		reportNotEqual("IGNORED", r.getIgnored(), statusParser.getIgnored());
		reportNotEqual("MISSING", r.getMissing(), statusParser.getMissing());
		reportNotEqual("UNKNOWN", r.getUnknown(), statusParser.getUnknown());
		// TODO compare equals
	}
	
	private static <T> void reportNotEqual(String what, Collection<T> l1, Collection<T> l2) {
		List<T> diff = difference(l1, l2);
		System.out.print(what);
		if (!diff.isEmpty()) {
			System.out.print(" are NOT the same: ");
			for (T t : diff) {
				System.out.print(t);
				System.out.print(", ");
			}
			System.out.println();
		} else {
			System.out.println(" are the same");
		}
	}

	private static <T> List<T> difference(Collection<T> l1, Collection<T> l2) {
		LinkedList<T> result = new LinkedList<T>(l2);
		for (T t : l1) {
			if (l2.contains(t)) {
				result.remove(t);
			} else {
				result.add(t);
			}
		}
		return result;
	}
}
