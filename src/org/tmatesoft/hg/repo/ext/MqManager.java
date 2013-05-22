/*
 * Copyright (c) 2012 TMate Software Ltd
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
package org.tmatesoft.hg.repo.ext;

import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.LineReader;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.Path;

/**
 * Mercurial Queues Support. 
 * Access to MqExtension functionality.
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class MqManager {
	
	private static final String PATCHES_DIR = "patches";

	private final Internals repo;
	private List<PatchRecord> applied = Collections.emptyList();
	private List<PatchRecord> allKnown = Collections.emptyList();
	private List<String> queueNames = Collections.emptyList();
	private String activeQueue = PATCHES_DIR;

	/*package-local*/ MqManager(Internals internalRepo) {
		repo = internalRepo;
	}
	
	/**
	 * Updates manager with up-to-date state of the mercurial queues.
	 * @return <code>this</code> for convenience
	 */
	public MqManager refresh() throws HgInvalidControlFileException {
		// MQ doesn't seem to use any custom lock mechanism.
		// MQ uses Mercurial's wc/store lock when updating repository (strip/new queue)
		applied = allKnown = Collections.emptyList();
		queueNames = Collections.emptyList();
		final LogFacility log = repo.getSessionContext().getLog();
		try {
			File queues = repo.getFileFromRepoDir("patches.queues");
			if (queues.isFile()) {
				LineReader lr = new LineReader(queues, log).trimLines(true).skipEmpty(true);
				lr.read(new LineReader.SimpleLineCollector(), queueNames = new LinkedList<String>());
			}
			final String queueLocation; // path under .hg to patch queue information (status, series and diff files)
			File activeQueueFile = repo.getFileFromRepoDir("patches.queue");
			// file is there only if it's not default queue ('patches') that is active
			if (activeQueueFile.isFile()) {
				ArrayList<String> contents = new ArrayList<String>();
				new LineReader(activeQueueFile, log).read(new LineReader.SimpleLineCollector(), contents);
				if (contents.isEmpty()) {
					log.dump(getClass(), Warn, "File %s with active queue name is empty", activeQueueFile.getName());
					activeQueue = PATCHES_DIR;
					queueLocation = PATCHES_DIR + '/';
				} else {
					activeQueue = contents.get(0);
					queueLocation = PATCHES_DIR + '-' + activeQueue +  '/';
				}
			} else {
				activeQueue = PATCHES_DIR;
				queueLocation = PATCHES_DIR + '/';
			}
			final Path.Source patchLocation = new Path.Source() {
				
				public Path path(CharSequence p) {
					StringBuilder sb = new StringBuilder(64);
					sb.append(".hg/");
					sb.append(queueLocation);
					sb.append(p);
					return Path.create(sb);
				}
			};
			final File fileStatus = repo.getFileFromRepoDir(queueLocation + "status");
			final File fileSeries = repo.getFileFromRepoDir(queueLocation + "series");
			if (fileStatus.isFile()) {
				new LineReader(fileStatus, log).read(new LineReader.LineConsumer<List<PatchRecord>>() {
	
					public boolean consume(String line, List<PatchRecord> result) throws IOException {
						int sep = line.indexOf(':');
						if (sep == -1) {
							log.dump(MqManager.class, Warn, "Bad line in %s:%s", fileStatus.getPath(), line);
							return true;
						}
						Nodeid nid = Nodeid.fromAscii(line.substring(0, sep));
						String name = new String(line.substring(sep+1));
						result.add(new PatchRecord(nid, name, patchLocation.path(name)));
						return true;
					}
				}, applied = new LinkedList<PatchRecord>());
			}
			if (fileSeries.isFile()) {
				final Map<String,PatchRecord> name2patch = new HashMap<String, PatchRecord>();
				for (PatchRecord pr : applied) {
					name2patch.put(pr.getName(), pr);
				}
				LinkedList<String> knownPatchNames = new LinkedList<String>();
				new LineReader(fileSeries, log).read(new LineReader.SimpleLineCollector(), knownPatchNames);
				// XXX read other queues?
				allKnown = new ArrayList<PatchRecord>(knownPatchNames.size());
				for (String name : knownPatchNames) {
					PatchRecord pr = name2patch.get(name);
					if (pr == null) {
						pr = new PatchRecord(null, name, patchLocation.path(name));
					}
					allKnown.add(pr);
				}
			}
		} catch (HgIOException ex) {
			throw new HgInvalidControlFileException(ex, true);
		}
		return this;
	}
	
	/**
	 * Number of patches not yet applied
	 * @return positive value when there are 
	 */
	public int getQueueSize() {
		return getAllKnownPatches().size() - getAppliedPatches().size();
	}

	/**
	 * Subset of the patches from the queue that were already applied to the repository
	 * <p>Analog of 'hg qapplied'
	 * 
	 * <p>Clients shall call {@link #refresh()} prior to first use
	 * @return collection of records in no particular order, may be empty if none applied
	 */
	public List<PatchRecord> getAppliedPatches() {
		return Collections.unmodifiableList(applied);
	}
	
	/**
	 * All of the patches in the active queue that MQ knows about for this repository
	 * 
	 * <p>Clients shall call {@link #refresh()} prior to first use
	 * @return collection of records in no particular order, may be empty if there are no patches in the queue
	 */
	public List<PatchRecord> getAllKnownPatches() {
		return Collections.unmodifiableList(allKnown);
	}
	
	/**
	 * Name of the patch queue <code>hg qqueue --active</code> which is active now.
	 * @return patch queue name
	 */
	public String getActiveQueueName() {
		return activeQueue;
	}

	/**
	 * Patch queues known in the repository, <code>hg qqueue -l</code> analog.
	 * There's at least one patch queue (default one names 'patches'). Only one patch queue at a time is active.
	 * 
	 * @return names of patch queues
	 */
	public List<String> getQueueNames() {
		return Collections.unmodifiableList(queueNames);
	}
	
	public final class PatchRecord {
		private final Nodeid nodeid;
		private final String name;
		private final Path location;
		
		// hashCode/equals might be useful if cons becomes public

		PatchRecord(Nodeid revision, String name, Path diffLocation) {
			nodeid = revision;
			this.name = name;
			this.location = diffLocation;
		}

		/**
		 * Identifies changeset of the patch that has been applied to the repository
		 * 
		 * @return changeset revision or <code>null</code> if this patch is not yet applied
		 */
		public Nodeid getRevision() {
			return nodeid;
		}

		/**
		 * Identifies patch, either based on a user-supplied name (<code>hg qnew <i>patch-name</i></code>) or 
		 * an automatically generated name (like <code><i>revisionIndex</i>.diff</code> for imported changesets).
		 * Clients shall not rely on this naming scheme, though.
		 * 
		 * @return never <code>null</code>
		 */
		public String getName() {
			return name;
		}
		
		/**
		 * Location of diff file with the patch, relative to repository root
		 * @return path to the patch, never <code>null</code>
		 */
		public Path getPatchLocation() {
			return location;
		}
		
		@Override
		public String toString() {
			String fmt = "mq.PatchRecord[name:%s; %spath:%s]";
			String ni = nodeid != null ? String.format("applied as: %s; ", nodeid.shortNotation()) : "";
			return String.format(fmt, name, ni, location);
		}
	}
}
