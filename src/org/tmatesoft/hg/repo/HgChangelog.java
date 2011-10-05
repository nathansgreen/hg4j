/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.IterateControlMediator;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Representation of the Mercurial changelog file (list of ChangeSets)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgChangelog extends Revlog {

	/* package-local */HgChangelog(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content);
	}

	public void all(final HgChangelog.Inspector inspector) {
		range(0, getLastRevision(), inspector);
	}

	public void range(int start, int end, final HgChangelog.Inspector inspector) {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		content.iterate(start, end, true, new RawCsetParser(inspector));
	}

	public List<RawChangeset> range(int start, int end) {
		final RawCsetCollector c = new RawCsetCollector(end - start + 1);
		range(start, end, c);
		return c.result;
	}

	/**
	 * Access individual revisions. Note, regardless of supplied revision order, inspector gets
	 * changesets strictly in the order they are in the changelog.
	 * @param inspector callback to get changesets
	 * @param revisions revisions to read, unrestricted ordering.
	 */
	public void range(final HgChangelog.Inspector inspector, final int... revisions) {
		Arrays.sort(revisions);
		rangeInternal(inspector, revisions);
	}

	/**
	 * Friends-only version of {@link #range(Inspector, int...)}, when callers know array is sorted
	 */
	/*package-local*/ void rangeInternal(HgChangelog.Inspector inspector, int[] sortedRevisions) {
		if (sortedRevisions == null || sortedRevisions.length == 0) {
			return;
		}
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		content.iterate(sortedRevisions, true, new RawCsetParser(inspector));
	}
	
	public RawChangeset changeset(Nodeid nid) {
		int x = getLocalRevision(nid);
		return range(x, x).get(0);
	}

	public interface Inspector {
		// TODO describe whether cset is new instance each time
		// describe what revisionNumber is when Inspector is used with HgBundle (BAD_REVISION or bundle's local order?) 
		void next(int revisionNumber, Nodeid nodeid, RawChangeset cset);
	}

	/**
	 * Unlike regular {@link Inspector}, this one supplies changeset revision along with its parents and children according
	 * to parent information of the revlog this inspector visits.
	 * @see HgDataFile#history(TreeInspector)
	 * @deprecated use {@link HgChangesetTreeHandler} and HgLogCommand#execute(HgChangesetTreeHandler)}
	 */
	@Deprecated
	public interface TreeInspector {
		// the reason TreeInsector is in HgChangelog, not in Revlog, because despite the fact it can
		// be applied to any revlog, it's not meant to provide revisions of any revlog it's beeing applied to, 
		// but changeset revisions always.
		// TODO HgChangelog.walk(TreeInspector)
		void next(Nodeid changesetRevision, Pair<Nodeid, Nodeid> parentChangesets, Collection<Nodeid> childChangesets);
	}

	/**
	 * Entry in the Changelog
	 */
	public static class RawChangeset implements Cloneable /* for those that would like to keep a copy */{
		// TODO immutable
		private/* final */Nodeid manifest;
		private String user;
		private String comment;
		private List<String> files; // unmodifiable collection (otherwise #files() and implicit #clone() shall be revised)
		private Date time;
		private int timezone;
		// http://mercurial.selenic.com/wiki/PruningDeadBranches - Closing changesets can be identified by close=1 in the changeset's extra field.
		private Map<String, String> extras;

		/**
		 * @see mercurial/changelog.py:read()
		 * 
		 *      <pre>
		 *         format used:
		 *         nodeid\n        : manifest node in ascii
		 *         user\n          : user, no \n or \r allowed
		 *         time tz extra\n : date (time is int or float, timezone is int)
		 *                         : extra is metadatas, encoded and separated by '\0'
		 *                         : older versions ignore it
		 *         files\n\n       : files modified by the cset, no \n or \r allowed
		 *         (.*)            : comment (free text, ideally utf-8)
		 * 
		 *         changelog v0 doesn't use extra
		 * </pre>
		 */
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
			return files;
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
			sb.append("Files: ").append(files.size());
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

		public static RawChangeset parse(DataAccess da) {
			try {
				byte[] data = da.byteArray();
				RawChangeset rv = new RawChangeset();
				rv.init(data, 0, data.length, null);
				return rv;
			} catch (IOException ex) {
				throw new HgBadStateException(ex); // FIXME "Error reading changeset data"
			}
		}

		// @param usersPool - it's likely user names get repeated again and again throughout repository. can be null
		/* package-local */void init(byte[] data, int offset, int length, Pool<String> usersPool) {
			final int bufferEndIndex = offset + length;
			final byte lineBreak = (byte) '\n';
			int breakIndex1 = indexOf(data, lineBreak, offset, bufferEndIndex);
			if (breakIndex1 == -1) {
				throw new IllegalArgumentException("Bad Changeset data");
			}
			Nodeid _nodeid = Nodeid.fromAscii(data, 0, breakIndex1);
			int breakIndex2 = indexOf(data, lineBreak, breakIndex1 + 1, bufferEndIndex);
			if (breakIndex2 == -1) {
				throw new IllegalArgumentException("Bad Changeset data");
			}
			String _user = new String(data, breakIndex1 + 1, breakIndex2 - breakIndex1 - 1);
			if (usersPool != null) {
				_user = usersPool.unify(_user);
			}
			int breakIndex3 = indexOf(data, lineBreak, breakIndex2 + 1, bufferEndIndex);
			if (breakIndex3 == -1) {
				throw new IllegalArgumentException("Bad Changeset data");
			}
			String _timeString = new String(data, breakIndex2 + 1, breakIndex3 - breakIndex2 - 1);
			int space1 = _timeString.indexOf(' ');
			if (space1 == -1) {
				throw new IllegalArgumentException("Bad Changeset data");
			}
			int space2 = _timeString.indexOf(' ', space1 + 1);
			if (space2 == -1) {
				space2 = _timeString.length();
			}
			long unixTime = Long.parseLong(_timeString.substring(0, space1)); // XXX Float, perhaps
			int _timezone = Integer.parseInt(_timeString.substring(space1 + 1, space2));
			// XXX not sure need to add timezone here - I can't figure out whether Hg keeps GMT time, and records timezone just for info, or unixTime is taken local
			// on commit and timezone is recorded to adjust it to UTC.
			Date _time = new Date(unixTime * 1000);
			String _extras = space2 < _timeString.length() ? _timeString.substring(space2 + 1) : null;
			Map<String, String> _extrasMap;
			final String extras_branch_key = "branch";
			if (_extras == null) {
				_extrasMap = Collections.singletonMap(extras_branch_key, HgRepository.DEFAULT_BRANCH_NAME);
			} else {
				_extrasMap = new HashMap<String, String>();
				for (String pair : _extras.split("\00")) {
					int eq = pair.indexOf(':');
					// FIXME need to decode key/value, @see changelog.py:decodeextra
					_extrasMap.put(pair.substring(0, eq), pair.substring(eq + 1));
				}
				if (!_extrasMap.containsKey(extras_branch_key)) {
					_extrasMap.put(extras_branch_key, HgRepository.DEFAULT_BRANCH_NAME);
				}
				_extrasMap = Collections.unmodifiableMap(_extrasMap);
			}

			//
			int lastStart = breakIndex3 + 1;
			int breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
			ArrayList<String> _files = null;
			if (breakIndex4 > lastStart) {
				// if breakIndex4 == lastStart, we already found \n\n and hence there are no files (e.g. merge revision)
				_files = new ArrayList<String>(5);
				while (breakIndex4 != -1 && breakIndex4 + 1 < bufferEndIndex) {
					_files.add(new String(data, lastStart, breakIndex4 - lastStart));
					lastStart = breakIndex4 + 1;
					if (data[breakIndex4 + 1] == lineBreak) {
						// found \n\n
						break;
					} else {
						breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
					}
				}
				if (breakIndex4 == -1 || breakIndex4 >= bufferEndIndex) {
					throw new IllegalArgumentException("Bad Changeset data");
				}
			} else {
				breakIndex4--;
			}
			String _comment;
			try {
				_comment = new String(data, breakIndex4 + 2, bufferEndIndex - breakIndex4 - 2, "UTF-8");
				// FIXME respect ui.fallbackencoding and try to decode if set
			} catch (UnsupportedEncodingException ex) {
				_comment = "";
				throw new IllegalStateException("Could hardly happen");
			}
			// change this instance at once, don't leave it partially changes in case of error
			this.manifest = _nodeid;
			this.user = _user;
			this.time = _time;
			this.timezone = _timezone;
			this.files = _files == null ? Collections.<String> emptyList() : Collections.unmodifiableList(_files);
			this.comment = _comment;
			this.extras = _extrasMap;
		}

		private static int indexOf(byte[] src, byte what, int startOffset, int endIndex) {
			for (int i = startOffset; i < endIndex; i++) {
				if (src[i] == what) {
					return i;
				}
			}
			return -1;
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

	private static class RawCsetParser implements RevlogStream.Inspector, Lifecycle {
		
		private final Inspector inspector;
		private final Pool<String> usersPool;
		private final RawChangeset cset = new RawChangeset();
		private final ProgressSupport progressHelper;
		private IterateControlMediator iterateControl;

		public RawCsetParser(HgChangelog.Inspector delegate) {
			assert delegate != null;
			inspector = delegate;
			usersPool = new Pool<String>();
			progressHelper = ProgressSupport.Factory.get(delegate);
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) {
			try {
				byte[] data = da.byteArray();
				cset.init(data, 0, data.length, usersPool);
				// XXX there's no guarantee for Changeset.Callback that distinct instance comes each time, consider instance reuse
				inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
				progressHelper.worked(1);
			} catch (Exception ex) {
				throw new HgBadStateException(ex); // FIXME exception handling
			}
			if (iterateControl != null) {
				iterateControl.checkCancelled();
			}
		}

		public void start(int count, Callback callback, Object token) {
			CancelSupport cs = CancelSupport.Factory.get(inspector, null);
			iterateControl = cs == null ? null : new IterateControlMediator(cs, callback);
			progressHelper.start(count);
		}

		public void finish(Object token) {
			progressHelper.done();
		}
	}
}
