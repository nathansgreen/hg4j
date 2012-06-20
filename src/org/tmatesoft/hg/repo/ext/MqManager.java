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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

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
	
	private final HgRepository repo;
	private List<PatchRecord> applied = Collections.emptyList();
	private List<PatchRecord> allKnown = Collections.emptyList();

	public MqManager(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	/**
	 * Updates manager with up-to-date state of the mercurial queues.
	 */
	public void refresh() throws HgInvalidControlFileException {
		File repoDir = HgInternals.getRepositoryDir(repo);
		final LogFacility log = HgInternals.getContext(repo).getLog();
		final File fileStatus = new File(repoDir, "patches/status");
		final File fileSeries = new File(repoDir, "patches/series");
		try {
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
						result.add(new PatchRecord(nid, name, Path.create(".hg/patches/" + name)));
						return true;
					}
				}, applied = new LinkedList<PatchRecord>());
			}
			if (fileSeries.isFile()) {
				new LineReader(fileSeries, log).read(new LineConsumer<List<PatchRecord>>() {

					public boolean consume(String line, List<PatchRecord> result) throws IOException {
						result.add(new PatchRecord(null, line, Path.create(".hg/patches/" + line)));
						return true;
					}
				}, allKnown = new LinkedList<PatchRecord>());
			}
		} catch (HgInvalidFileException ex) {
			HgInvalidControlFileException th = new HgInvalidControlFileException(ex.getMessage(), ex.getCause(), ex.getFile());
			th.setStackTrace(ex.getStackTrace());
			throw th;
		}
	}

	/**
	 * Subset of the patches from the queue that were already applied to the repository
	 * 
	 * <p>Clients shall call {@link #refresh()} prior to first use
	 * @return collection of records in no particular order, may be empty if none applied
	 */
	public List<PatchRecord> getAppliedPatches() {
		return Collections.unmodifiableList(applied);
	}
	
	/**
	 * All of the patches that MQ knows about for this repository
	 * 
	 * <p>Clients shall call {@link #refresh()} prior to first use
	 * @return collection of records in no particular order, may be empty if there are no patches in the queue
	 */
	public List<PatchRecord> getAllKnownPatches() {
		return Collections.unmodifiableList(allKnown);
	}
	
	public class PatchRecord {
		private final Nodeid nodeid;
		private final String name;
		private final Path location;

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

		LineReader(File f, LogFacility logFacility) {
			file = f;
			log = logFacility;
		}

		<T> void read(LineConsumer<T> consumer, T paramObj) throws HgInvalidFileException {
			BufferedReader statusFileReader = null;
			try {
//				consumer.begin(file, paramObj);
				statusFileReader = new BufferedReader(new FileReader(file));
				String line;
				boolean ok = true;
				while (ok && (line = statusFileReader.readLine()) != null) {
					line = line.trim();
					if (line.length() > 0) {
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
