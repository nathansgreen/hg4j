/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepository.*;

import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.PathPool;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;


/**
 * Gives access to list of files in each revision (Mercurial manifest information), 'hg manifest' counterpart.
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgManifestCommand extends HgAbstractCommand<HgManifestCommand> {
	
	private final HgRepository repo;
	private Path.Matcher matcher;
	private int startRev = 0, endRev = TIP;
	private HgManifestHandler visitor;
	private boolean needDirs = false;
	
	private final Mediator mediator = new Mediator();

	public HgManifestCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}

	/**
	 * Parameterize command to visit revisions <code>[rev1..rev2]</code>.
	 * @param rev1 - revision local index to start from. Non-negative. May be {@link HgRepository#TIP} (rev2 argument shall be {@link HgRepository#TIP} as well, then) 
	 * @param rev2 - revision local index to end with, inclusive. Non-negative, greater or equal to rev1. May be {@link HgRepository#TIP}.
	 * @return <code>this</code> for convenience.
	 * @throws IllegalArgumentException if revision arguments are incorrect (see above).
	 */
	public HgManifestCommand range(int rev1, int rev2) {
		// XXX if manifest range is different from that of changelog, need conversion utils (external?)
		boolean badArgs = rev1 == BAD_REVISION || rev2 == BAD_REVISION || rev1 == WORKING_COPY || rev2 == WORKING_COPY;
		badArgs |= rev2 != TIP && rev2 < rev1; // range(3, 1);
		badArgs |= rev1 == TIP && rev2 != TIP; // range(TIP, 2), although this may be legitimate when TIP points to 2
		if (badArgs) {
			// TODO [2.0 API break] throw checked HgBadArgumentException instead
			throw new IllegalArgumentException(String.format("Bad range: [%d, %d]", rev1, rev2));
		}
		startRev = rev1;
		endRev = rev2;
		return this;
	}
	
	/**
	 * Select changeset for the command using revision index 
	 * @param csetRevisionIndex index of changeset revision
	 * @return <code>this</code> for convenience.
	 */
	public HgManifestCommand changeset(int csetRevisionIndex) {
		// TODO [2.0 API break] shall throw HgBadArgumentException, like other commands do
		return range(csetRevisionIndex, csetRevisionIndex);
	}
	
	/**
	 * Select changeset for the command
	 * 
	 * @param nid changeset revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset revision 
	 */
	public HgManifestCommand changeset(Nodeid nid) throws HgBadArgumentException {
		// XXX also see HgLogCommand#changeset(Nodeid)
		final int csetRevIndex = new CsetParamKeeper(repo).set(nid).get();
		return range(csetRevIndex, csetRevIndex);
	}

	public HgManifestCommand dirs(boolean include) {
		// XXX whether directories with directories only are include or not
		// now lists only directories with files
		needDirs = include;
		return this;
	}
	
	/**
	 * Limit manifest walk to a subset of files. 
	 * @param pathMatcher - filter, pass <code>null</code> to clear.
	 * @return <code>this</code> instance for convenience
	 */
	public HgManifestCommand match(Path.Matcher pathMatcher) {
		matcher = pathMatcher;
		return this;
	}
	
	/**
	 * With all parameters set, execute the command.
	 * 
	 * @param handler - callback to get the outcome
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws IllegalArgumentException if handler is <code>null</code>
	 * @throws ConcurrentModificationException if this command is already in use (running)
	 */
	public void execute(HgManifestHandler handler) throws HgCallbackTargetException, HgException, CancelledException {
		if (handler == null) {
			throw new IllegalArgumentException();
		}
		if (visitor != null) {
			throw new ConcurrentModificationException();
		}
		try {
			visitor = handler;
			mediator.start(getCancelSupport(handler, true));
			repo.getManifest().walk(startRev, endRev, mediator);
			mediator.checkFailure();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			mediator.done();
			visitor = null;
		}
	}

	// I'd rather let HgManifestCommand implement HgManifest.Inspector directly, but this pollutes API alot
	private class Mediator implements HgManifest.Inspector {
		// file names are likely to repeat in each revision, hence caching of Paths.
		// However, once HgManifest.Inspector switches to Path objects, perhaps global Path pool
		// might be more effective?
		private PathPool pathPool;
		private List<HgFileRevision> manifestContent;
		private Nodeid manifestNodeid;
		private Exception failure;
		private CancelSupport cancelHelper;
		
		public void start(CancelSupport cs) {
			assert cs != null;
			// Manifest keeps normalized paths
			pathPool = new PathPool(new PathRewrite.Empty());
			cancelHelper = cs;
		}
		
		public void done() {
			manifestContent = null;
			pathPool = null;
		}
		
		private void recordFailure(HgCallbackTargetException ex) {
			failure = ex;
		}
		private void recordCancel(CancelledException ex) {
			failure = ex;
		}

		public void checkFailure() throws HgCallbackTargetException, CancelledException {
			// TODO post-1.0 perhaps, can combine this code (record/checkFailure) for reuse in more classes (e.g. in Revlog)
			if (failure instanceof HgCallbackTargetException) {
				HgCallbackTargetException ex = (HgCallbackTargetException) failure;
				failure = null;
				throw ex;
			}
			if (failure instanceof CancelledException) {
				CancelledException ex = (CancelledException) failure;
				failure = null;
				throw ex;
			}
		}
	
		public boolean begin(int manifestRevision, Nodeid nid, int changelogRevision) throws HgRuntimeException {
			if (needDirs && manifestContent == null) {
				manifestContent = new LinkedList<HgFileRevision>();
			}
			try {
				visitor.begin(manifestNodeid = nid);
				cancelHelper.checkCancelled();
				return true;
			} catch (HgCallbackTargetException ex) {
				recordFailure(ex);
				return false;
			} catch (CancelledException ex) {
				recordCancel(ex);
				return false;
			}
		}
		public boolean end(int revision) throws HgRuntimeException {
			try {
				if (needDirs) {
					LinkedHashMap<Path, LinkedList<HgFileRevision>> breakDown = new LinkedHashMap<Path, LinkedList<HgFileRevision>>();
					for (HgFileRevision fr : manifestContent) {
						Path filePath = fr.getPath();
						Path dirPath = pathPool.parent(filePath);
						LinkedList<HgFileRevision> revs = breakDown.get(dirPath);
						if (revs == null) {
							revs = new LinkedList<HgFileRevision>();
							breakDown.put(dirPath, revs);
						}
						revs.addLast(fr);
					}
					for (Path dir : breakDown.keySet()) {
						visitor.dir(dir);
						cancelHelper.checkCancelled();
						for (HgFileRevision fr : breakDown.get(dir)) {
							visitor.file(fr);
						}
					}
					manifestContent.clear();
				}
				visitor.end(manifestNodeid);
				cancelHelper.checkCancelled();
				return true;
			} catch (HgCallbackTargetException ex) {
				recordFailure(ex);
				return false;
			} catch (CancelledException ex) {
				recordCancel(ex);
				return false;
			} finally {
				manifestNodeid = null;
			}
		}
		
		public boolean next(Nodeid nid, Path fname, Flags flags) throws HgRuntimeException {
			if (matcher != null && !matcher.accept(fname)) {
				return true;
			}
			try {
				HgFileRevision fr = new HgFileRevision(repo, nid, flags, fname);
				if (needDirs) {
					manifestContent.add(fr);
				} else {
					visitor.file(fr);
				}
				return true;
			} catch (HgCallbackTargetException ex) {
				recordFailure(ex);
				return false;
			}
		}
	}
}
