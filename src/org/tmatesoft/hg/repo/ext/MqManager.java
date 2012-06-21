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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgInvalidControlFileException;
import org.tmatesoft.hg.core.HgInvalidFileException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.Path;

/**
 * Mercurial Queues Support. 
 * Access to MqExtension functionality.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class MqManager {
	
	private static final String PATCHES_DIR = "patches";

	private final HgRepository repo;
	private List<PatchRecord> applied = Collections.emptyList();
	private List<PatchRecord> allKnown = Collections.emptyList();
	private List<String> queueNames = Collections.emptyList();
	private String activeQueue = PATCHES_DIR;

	public MqManager(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	/**
	 * Updates manager with up-to-date state of the mercurial queues.
	 */
	public void refresh() throws HgInvalidControlFileException {
		applied = allKnown = Collections.emptyList();
		queueNames = Collections.emptyList();
		File repoDir = HgInternals.getRepositoryDir(repo);
		final LogFacility log = HgInternals.getContext(repo).getLog();
		try {
			File queues = new File(repoDir, "patches.queues");
			if (queues.isFile()) {
				LineReader lr = new LineReader(queues, log).trimLines(true).skipEmpty(true);
				lr.read(new SimpleLineCollector(), queueNames = new LinkedList<String>());
			}
			final String queueLocation; // path under .hg to patch queue information (status, series and diff files)
			File activeQueueFile = new File(repoDir, "patches.queue");
			// file is there only if it's not default queue ('patches') that is active
			if (activeQueueFile.isFile()) {
				ArrayList<String> contents = new ArrayList<String>();
				new LineReader(activeQueueFile, log).read(new SimpleLineCollector(), contents);
				if (contents.isEmpty()) {
					log.warn(getClass(), "File %s with active queue name is empty", activeQueueFile.getName());
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
				
				public Path path(String p) {
					StringBuilder sb = new StringBuilder(64);
					sb.append(".hg/");
					sb.append(queueLocation);
					sb.append(p);
					return Path.create(sb);
				}
			};
			final File fileStatus = new File(repoDir, queueLocation + "status");
			final File fileSeries = new File(repoDir, queueLocation + "series");
			if (fileStatus.isFile()) {
				new LineReader(fileStatus, log).read(new LineConsumer<List<PatchRecord>>() {
	
					public boolean consume(String line, List<PatchRecord> result) throws IOException {
						int sep = line.indexOf(':');
						if (sep == -1) {
							log.warn(MqManager.class, "Bad line in %s:%s", fileStatus.getPath(), line);
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
				new LineReader(fileSeries, log).read(new SimpleLineCollector(), knownPatchNames);
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
		} catch (HgInvalidFileException ex) {
			HgInvalidControlFileException th = new HgInvalidControlFileException(ex.getMessage(), ex.getCause(), ex.getFile());
			th.setStackTrace(ex.getStackTrace());
			throw th;
		}
	}
	
	static class SimpleLineCollector implements LineConsumer<Collection<String>> {

		public boolean consume(String line, Collection<String> result) throws IOException {
			result.add(line);
			return true;
		}
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
	
	public class PatchRecord {
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
	}

	// TODO refine API and extract into separate classes

	interface LineConsumer<T> {
//		boolean begin(File f, T paramObj) throws IOException;
		boolean consume(String line, T paramObj) throws IOException;
//		boolean end(File f, T paramObj) throws IOException;
	}
	
	class LineReader {
		
		private final File file;
		private final LogFacility log;
		private boolean trimLines = true;
		private boolean skipEmpty = true;
		private String ignoreThatStars = null;

		LineReader(File f, LogFacility logFacility) {
			file = f;
			log = logFacility;
		}
		
		/**
		 * default: <code>true</code>
		 * <code>false</code> to return line as is
		 */
		LineReader trimLines(boolean trim) {
			trimLines = trim;
			return this;
		}
		
		/**
		 * default: <code>true</code>
		 * <code>false</code> to pass empty lines to consumer
		 */
		LineReader skipEmpty(boolean skip) {
			skipEmpty = skip;
			return this;
		}
		
		/**
		 * default: doesn't skip any line.
		 * set e.g. to "#" or "//" to skip lines that start with such prefix
		 */
		LineReader ignoreLineComments(String lineStart) {
			ignoreThatStars = lineStart;
			return this;
		}

		<T> void read(LineConsumer<T> consumer, T paramObj) throws HgInvalidFileException {
			BufferedReader statusFileReader = null;
			try {
//				consumer.begin(file, paramObj);
				statusFileReader = new BufferedReader(new FileReader(file));
				String line;
				boolean ok = true;
				while (ok && (line = statusFileReader.readLine()) != null) {
					if (trimLines) {
						line = line.trim();
					}
					if (ignoreThatStars != null && line.startsWith(ignoreThatStars)) {
						continue;
					}
					if (!skipEmpty || line.length() > 0) {
						ok = consumer.consume(line, paramObj);
					}
				}
			} catch (IOException ex) {
				throw new HgInvalidFileException(ex.getMessage(), ex, file);
			} finally {
				try {
					statusFileReader.close();
				} catch (IOException ex) {
					log.warn(MqManager.class, ex, null);
				}
//				try {
//					consumer.end(file, paramObj);
//				} catch (IOException ex) {
//					log.warn(MqManager.class, ex, null);
//				}
			}
		}
	}
}
