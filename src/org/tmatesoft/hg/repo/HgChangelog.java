/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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
package org.tmatesoft.hg.repo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.ChangesetParser;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.LifecycleBridge;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Representation of the Mercurial changelog file (list of ChangeSets)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgChangelog extends Revlog {

	/* package-local */HgChangelog(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content, true);
	}

	/**
	 * Iterate over whole changelog
	 * @param inspector callback to process entries
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void all(final HgChangelog.Inspector inspector) throws HgRuntimeException {
		range(0, getLastRevision(), inspector);
	}

	/**
	 * Iterate over changelog part
	 * @param start first changelog entry to process
	 * @param end last changelog entry to process
	 * @param inspector callback to process entries
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void range(int start, int end, final HgChangelog.Inspector inspector) throws HgRuntimeException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		content.iterate(start, end, true, new RawCsetParser(getRepo(), inspector));
	}

	/**
	 * @see #range(int, int, Inspector)
	 * @return changeset entry objects, never <code>null</code>
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public List<RawChangeset> range(int start, int end) throws HgRuntimeException {
		final RawCsetCollector c = new RawCsetCollector(end - start + 1);
		range(start, end, c);
		return c.result;
	}

	/**
	 * Access individual revisions. Note, regardless of supplied revision order, inspector gets
	 * changesets strictly in the order they are in the changelog.
	 * @param inspector callback to get changesets
	 * @param revisions revisions to read, unrestricted ordering.
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify revision from this revlog <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void range(final HgChangelog.Inspector inspector, final int... revisions) throws HgRuntimeException {
		Arrays.sort(revisions);
		rangeInternal(inspector, revisions);
	}

	/**
	 * Friends-only version of {@link #range(Inspector, int...)}, when callers know array is sorted
	 */
	/*package-local*/ void rangeInternal(HgChangelog.Inspector inspector, int[] sortedRevisions) throws HgRuntimeException {
		if (sortedRevisions == null || sortedRevisions.length == 0) {
			return;
		}
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		content.iterate(sortedRevisions, true, new RawCsetParser(getRepo(), inspector));
	}

	/**
	 * Get changeset entry object
	 * @throws HgInvalidRevisionException if supplied nodeid doesn't identify any revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public RawChangeset changeset(Nodeid nid)  throws HgRuntimeException {
		int x = getRevisionIndex(nid);
		return range(x, x).get(0);
	}

	@Callback
	public interface Inspector {
		/**
		 * Access next changeset
		 * TODO describe what revisionNumber is when Inspector is used with HgBundle (BAD_REVISION or bundle's local order?)
		 * 
		 * @param revisionIndex index of revision being inspected, local to the inspected object 
		 * @param nodeid revision being inspected
		 * @param cset changeset raw data
		 */
		void next(int revisionIndex, Nodeid nodeid, RawChangeset cset) throws HgRuntimeException;
	}

	/**
	 * Entry in the Changelog
	 */
	public static final class RawChangeset implements Cloneable /* for those that would like to keep a copy */{
		// would be nice to get it immutable, but then we can't reuse instances
		/* final */Nodeid manifest;
		String user;
		String comment;
		String[] files; // shall not be modified (#clone() does shallow copy)
		Date time;
		int timezone;
		// http://mercurial.selenic.com/wiki/PruningDeadBranches - Closing changesets can be identified by close=1 in the changeset's extra field.
		Map<String, String> extras;

		private RawChangeset() {
		}

		public Nodeid manifest() {
			return manifest;
		}

		public String user() {
			return user;
		}

		public String comment() {
			return comment;
		}

		public List<String> files() {
			return Arrays.asList(files);
		}

		public Date date() {
			return time;
		}
		
		/**
		 * @return time zone value, as is, positive for Western Hemisphere.
		 */
		public int timezone() {
			return timezone;
		}

		public String dateString() {
			// XXX keep once formatted? Perhaps, there's faster way to set up calendar/time zone?
			StringBuilder sb = new StringBuilder(30);
			Formatter f = new Formatter(sb, Locale.US);
			TimeZone tz = TimeZone.getTimeZone(TimeZone.getAvailableIDs(timezone * 1000)[0]);
			// apparently timezone field records number of seconds time differs from UTC,
			// i.e. value to substract from time to get UTC time. Calendar seems to add
			// timezone offset to UTC, instead, hence sign change.
//			tz.setRawOffset(timezone * -1000);
			Calendar c = Calendar.getInstance(tz, Locale.US);
			c.setTime(time);
			f.format("%ta %<tb %<td %<tH:%<tM:%<tS %<tY %<tz", c);
			return sb.toString();
		}

		public Map<String, String> extras() {
			return extras;
		}

		public String branch() {
			return extras.get("branch");
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("Changeset {");
			sb.append("User: ").append(user).append(", ");
			sb.append("Comment: ").append(comment).append(", ");
			sb.append("Manifest: ").append(manifest).append(", ");
			sb.append("Date: ").append(time).append(", ");
			sb.append("Files: ").append(files.length);
			for (String s : files) {
				sb.append(", ").append(s);
			}
			if (extras != null) {
				sb.append(", Extra: ").append(extras);
			}
			sb.append("}");
			return sb.toString();
		}

		@Override
		public RawChangeset clone() {
			try {
				return (RawChangeset) super.clone();
			} catch (CloneNotSupportedException ex) {
				throw new InternalError(ex.toString());
			}
		}
	}
	
	/*package-local*/static final class RawCsetFactory implements ChangesetParser.CsetFactory {
		private RawChangeset cset;

		public RawCsetFactory(boolean shallReuseCsetInstance) {
			if (shallReuseCsetInstance) {
				cset = new RawChangeset();
			}
		}

		public RawChangeset create(Nodeid nodeidManifest, String user, Date time, int timezone, List<String> files, String comment, Map<String, String> extrasMap) {
			RawChangeset target;
			if (cset != null) {
				target = cset;
			} else {
				target = new RawChangeset();
			}
			target.manifest = nodeidManifest;
			target.user = user;
			target.time = time;
			target.timezone = timezone;
			target.files = files == null ? new String[0] : files.toArray(new String[files.size()]);
			target.comment = comment;
			target.extras = extrasMap;
			return target;
		}
	}
	
	private static class RawCsetCollector implements Inspector {
		final ArrayList<RawChangeset> result;
		
		public RawCsetCollector(int count) {
			result = new ArrayList<RawChangeset>(count > 0 ? count : 5);
		}

		public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
			result.add(cset.clone());
		}
	}

	private static final class RawCsetParser implements RevlogStream.Inspector, Adaptable, Lifecycle {
		
		private final Inspector inspector;
		private final ChangesetParser csetBuilder;
		// non-null when inspector uses high-level lifecycle entities (progress and/or cancel supports)
		private final LifecycleBridge lifecycleStub;
		// non-null when inspector relies on low-level lifecycle and is responsible
		// to proceed any possible high-level entities himself.
		private final Lifecycle inspectorLifecycle;

		public RawCsetParser(SessionContext.Source sessionContext, HgChangelog.Inspector delegate) {
			assert delegate != null;
			inspector = delegate;
			csetBuilder = new ChangesetParser(sessionContext, new RawCsetFactory(true));
			inspectorLifecycle = Adaptable.Factory.getAdapter(delegate, Lifecycle.class, null);
			if (inspectorLifecycle == null) {
				ProgressSupport ph = Adaptable.Factory.getAdapter(delegate, ProgressSupport.class, null);
				CancelSupport cs = Adaptable.Factory.getAdapter(delegate, CancelSupport.class, null);
				if (cs != null || ph != null) {
					lifecycleStub = new LifecycleBridge(ph, cs);
				} else {
					lifecycleStub = null;
				}
			} else {
				lifecycleStub = null;
			}
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) throws HgRuntimeException {
			try {
				RawChangeset cset = csetBuilder.parse(da);
				// XXX there's no guarantee for Changeset.Callback that distinct instance comes each time, consider instance reuse
				inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
				if (lifecycleStub != null) {
					lifecycleStub.nextStep();
				}
			} catch (HgInvalidDataFormatException ex) {
				throw ex.setRevisionIndex(revisionNumber);  
			} catch (IOException ex) {
				// XXX need better exception, perhaps smth like HgChangelogException (extends HgInvalidControlFileException)
				throw new HgInvalidControlFileException("Failed reading changelog", ex, null).setRevisionIndex(revisionNumber);  
			}
		}
		
		public <T> T getAdapter(Class<T> adapterClass) {
			if (adapterClass == Lifecycle.class) {
				return adapterClass.cast(this);
			}
			// XXX what if caller takes Progress/Cancel (which we update through lifecycleStub, too)
			return Adaptable.Factory.getAdapter(inspector, adapterClass, null);
		}

		public void start(int count, Callback callback, Object token) {
			if (inspectorLifecycle != null) {
				inspectorLifecycle.start(count, callback, token);
			} else if (lifecycleStub != null) {
				lifecycleStub.start(count, callback, token);
			}
		}

		public void finish(Object token) {
			if (inspectorLifecycle != null) {
				inspectorLifecycle.finish(token);
			} else if (lifecycleStub != null) {
				lifecycleStub.finish(token);
			}
			csetBuilder.dispose();
		}

	}
}
