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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.internal.remote.Connector.*;
import static org.tmatesoft.hg.util.Outcome.Kind.Failure;
import static org.tmatesoft.hg.util.Outcome.Kind.Success;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StreamTokenizer;
import java.net.ContentHandler;
import java.net.ContentHandlerFactory;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tmatesoft.hg.core.HgBadArgumentException;
import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.HgRepositoryNotFoundException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.BundleSerializer;
import org.tmatesoft.hg.internal.DataSerializer;
import org.tmatesoft.hg.internal.DataSerializer.OutputStreamSerializer;
import org.tmatesoft.hg.internal.EncodingHelper;
import org.tmatesoft.hg.internal.FileUtils;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PropertyMarshal;
import org.tmatesoft.hg.internal.remote.Connector;
import org.tmatesoft.hg.internal.remote.HttpConnector;
import org.tmatesoft.hg.internal.remote.SshConnector;
import org.tmatesoft.hg.util.LogFacility.Severity;
import org.tmatesoft.hg.util.Outcome;
import org.tmatesoft.hg.util.Pair;

/**
 * WORK IN PROGRESS, DO NOT USE
 * 
 * @see http://mercurial.selenic.com/wiki/WireProtocol
 * @see http://mercurial.selenic.com/wiki/HttpCommandProtocol
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgRemoteRepository implements SessionContext.Source {
	
	private final boolean debug;
	private HgLookup lookupHelper;
	private final SessionContext sessionContext;
	private Set<String> remoteCapabilities;
	private Connector remote;
	
	static {
		URLConnection.setContentHandlerFactory(new ContentHandlerFactory() {
			
			public ContentHandler createContentHandler(String mimetype) {
				if ("application/mercurial-0.1".equals(mimetype)) {
					return new ContentHandler() {
						
						@Override
						public Object getContent(URLConnection urlc) throws IOException {
							if (urlc.getContentLength() > 0) {
								ByteArrayOutputStream bos = new ByteArrayOutputStream();
								InputStream is = urlc.getInputStream();
								int r;
								while ((r = is.read()) != -1) {
									bos.write(r);
								}
								return new String(bos.toByteArray());
							}
							return "<empty>";
						}
					};
				}
				return null;
			}
		});
	}
	
	HgRemoteRepository(SessionContext ctx, URL url) throws HgBadArgumentException {
		if (url == null || ctx == null) {
			throw new IllegalArgumentException();
		}
		sessionContext = ctx;
		debug = new PropertyMarshal(ctx).getBoolean("hg4j.remote.debug", false);
		remote = "ssh".equals(url.getProtocol()) ? new SshConnector() : new HttpConnector();
		remote.init(url, ctx, null);
	}
	
	public boolean isInvalid() throws HgRemoteConnectionException {
		initCapabilities();
		return remoteCapabilities.isEmpty();
	}

	/**
	 * @return human-readable address of the server, without user credentials or any other security information
	 */
	public String getLocation() {
		return remote.getServerLocation();
	}
	
	public SessionContext getSessionContext() {
		return sessionContext;
	}

	public List<Nodeid> heads() throws HgRemoteConnectionException {
		if (isInvalid()) {
			return Collections.emptyList();
		}
		try {
			remote.sessionBegin();
			InputStreamReader is = new InputStreamReader(remote.heads(), "US-ASCII");
			StreamTokenizer st = new StreamTokenizer(is);
			st.ordinaryChars('0', '9'); // wordChars performs |, hence need to 0 first
			st.wordChars('0', '9');
			st.eolIsSignificant(false);
			LinkedList<Nodeid> parseResult = new LinkedList<Nodeid>();
			while (st.nextToken() != StreamTokenizer.TT_EOF) {
				parseResult.add(Nodeid.fromAscii(st.sval));
			}
			return parseResult;
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_HEADS).setServerInfo(getLocation());
		} finally {
			remote.sessionEnd();
		}
	}
	
	public List<Nodeid> between(Nodeid tip, Nodeid base) throws HgRemoteConnectionException {
		Range r = new Range(base, tip);
		// XXX shall handle errors like no range key in the returned map, not sure how.
		return between(Collections.singletonList(r)).get(r);
	}

	/**
	 * @param ranges
	 * @return map, where keys are input instances, values are corresponding server reply
	 * @throws HgRemoteConnectionException 
	 */
	public Map<Range, List<Nodeid>> between(Collection<Range> ranges) throws HgRemoteConnectionException {
		if (ranges.isEmpty() || isInvalid()) {
			return Collections.emptyMap();
		}
		LinkedHashMap<Range, List<Nodeid>> rv = new LinkedHashMap<HgRemoteRepository.Range, List<Nodeid>>(ranges.size() * 4 / 3);
		try {
			remote.sessionBegin();
			InputStreamReader is = new InputStreamReader(remote.between(ranges), "US-ASCII");
			StreamTokenizer st = new StreamTokenizer(is);
			st.ordinaryChars('0', '9');
			st.wordChars('0', '9');
			st.eolIsSignificant(true);
			Iterator<Range> rangeItr = ranges.iterator();
			LinkedList<Nodeid> currRangeList = null;
			Range currRange = null;
			boolean possiblyEmptyNextLine = true;
			while (st.nextToken() != StreamTokenizer.TT_EOF) {
				if (st.ttype == StreamTokenizer.TT_EOL) {
					if (possiblyEmptyNextLine) {
						// newline follows newline;
						assert currRange == null;
						assert currRangeList == null;
						if (!rangeItr.hasNext()) {
							throw new HgInvalidStateException("Internal error"); // TODO revisit-1.1
						}
						rv.put(rangeItr.next(), Collections.<Nodeid>emptyList());
					} else {
						if (currRange == null || currRangeList == null) {
							throw new HgInvalidStateException("Internal error"); // TODO revisit-1.1
						}
						// indicate next range value is needed
						currRange = null;
						currRangeList = null;
						possiblyEmptyNextLine = true;
					}
				} else {
					possiblyEmptyNextLine = false;
					if (currRange == null) {
						if (!rangeItr.hasNext()) {
							throw new HgInvalidStateException("Internal error"); // TODO revisit-1.1
						}
						currRange = rangeItr.next();
						currRangeList = new LinkedList<Nodeid>();
						rv.put(currRange, currRangeList);
					}
					Nodeid nid = Nodeid.fromAscii(st.sval);
					currRangeList.addLast(nid);
				}
			}
			is.close();
			return rv;
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_BETWEEN).setServerInfo(getLocation());
		} finally {
			remote.sessionEnd();
		}
	}

	public List<RemoteBranch> branches(List<Nodeid> nodes) throws HgRemoteConnectionException {
		if (isInvalid()) {
			return Collections.emptyList();
		}
		try {
			remote.sessionBegin();
			InputStreamReader is = new InputStreamReader(remote.branches(nodes), "US-ASCII");
			StreamTokenizer st = new StreamTokenizer(is);
			st.ordinaryChars('0', '9');
			st.wordChars('0', '9');
			st.eolIsSignificant(false);
			ArrayList<Nodeid> parseResult = new ArrayList<Nodeid>(nodes.size() * 4);
			while (st.nextToken() != StreamTokenizer.TT_EOF) {
				parseResult.add(Nodeid.fromAscii(st.sval));
			}
			if (parseResult.size() != nodes.size() * 4) {
				throw new HgRemoteConnectionException(String.format("Bad number of nodeids in result (shall be factor 4), expected %d, got %d", nodes.size()*4, parseResult.size()));
			}
			ArrayList<RemoteBranch> rv = new ArrayList<RemoteBranch>(nodes.size());
			for (int i = 0; i < nodes.size(); i++) {
				RemoteBranch rb = new RemoteBranch(parseResult.get(i*4), parseResult.get(i*4 + 1), parseResult.get(i*4 + 2), parseResult.get(i*4 + 3));
				rv.add(rb);
			}
			return rv;
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_BRANCHES).setServerInfo(getLocation());
		} finally {
			remote.sessionEnd();
		}
	}

	/*
	 * XXX need to describe behavior when roots arg is empty; our RepositoryComparator code currently returns empty lists when
	 * no common elements found, which in turn means we need to query changes starting with NULL nodeid.
	 * 
	 * WireProtocol wiki: roots = a list of the latest nodes on every service side changeset branch that both the client and server know about.
	 * 
	 * Perhaps, shall be named 'changegroup'

	 * Changegroup: 
	 * http://mercurial.selenic.com/wiki/Merge 
	 * http://mercurial.selenic.com/wiki/WireProtocol 
	 * 
	 * according to latter, bundleformat data is sent through zlib
	 * (there's no header like HG10?? with the server output, though, 
	 * as one may expect according to http://mercurial.selenic.com/wiki/BundleFormat)
	 */
	public HgBundle getChanges(List<Nodeid> roots) throws HgRemoteConnectionException, HgRuntimeException {
		if (isInvalid()) {
			return null; // XXX valid retval???
		}
		List<Nodeid> _roots = roots.isEmpty() ? Collections.singletonList(Nodeid.NULL) : roots;
		try {
			remote.sessionBegin();
			File tf = writeBundle(remote.changegroup(_roots));
			if (debug) {
				System.out.printf("Wrote bundle %s for roots %s\n", tf, roots);
			}
			return getLookupHelper().loadBundle(tf);
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_CHANGEGROUP).setServerInfo(getLocation());
		} catch (HgRepositoryNotFoundException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_CHANGEGROUP).setServerInfo(getLocation());
		} finally {
			remote.sessionEnd();
		}
	}
	
	public void unbundle(HgBundle bundle, List<Nodeid> remoteHeads) throws HgRemoteConnectionException, HgRuntimeException {
		if (remoteHeads == null) {
			// TODO collect heads from bundle:
			// bundle.inspectChangelog(new HeadCollector(for each c : if collected has c.p1 or c.p2, remove them. Add c))
			// or get from remote server???
			throw Internals.notImplemented();
		}
		if (isInvalid()) {
			return;
		}
		DataSerializer.DataSource bundleData = BundleSerializer.newInstance(sessionContext, bundle);
		OutputStream os = null;
		try {
			remote.sessionBegin();
			os = remote.unbundle(bundleData.serializeLength(), remoteHeads);
			bundleData.serialize(new OutputStreamSerializer(os));
			os.flush();
			os.close();
			os = null;
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand("unbundle").setServerInfo(getLocation());
		} catch (HgIOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand("unbundle").setServerInfo(getLocation());
		} finally {
			new FileUtils(sessionContext.getLog(), this).closeQuietly(os);
			remote.sessionEnd();
		}
	}

	public Bookmarks getBookmarks() throws HgRemoteConnectionException, HgRuntimeException {
		initCapabilities();
		if (!remoteCapabilities.contains(CMD_PUSHKEY)) { // (sic!) listkeys is available when pushkey in caps
			return new Bookmarks(Collections.<Pair<String, Nodeid>>emptyList());
		}
		final String actionName = "Get remote bookmarks";
		final List<Pair<String, String>> values = listkeys("bookmarks", actionName);
		ArrayList<Pair<String, Nodeid>> rv = new ArrayList<Pair<String, Nodeid>>();
		for (Pair<String, String> l : values) {
			if (l.second().length() != Nodeid.SIZE_ASCII) {
				sessionContext.getLog().dump(getClass(), Severity.Warn, "%s: bad nodeid '%s', ignored", actionName, l.second());
				continue;
			}
			Nodeid n = Nodeid.fromAscii(l.second());
			String bm = new String(l.first());
			rv.add(new Pair<String, Nodeid>(bm, n));
		}
		return new Bookmarks(rv);
	}

	public Outcome updateBookmark(String name, Nodeid oldRev, Nodeid newRev) throws HgRemoteConnectionException, HgRuntimeException {
		initCapabilities();
		if (!remoteCapabilities.contains(CMD_PUSHKEY)) {
			return new Outcome(Failure, "Server doesn't support pushkey protocol");
		}
		if (pushkey("Update remote bookmark", NS_BOOKMARKS, name, oldRev.toString(), newRev.toString())) {
			return new Outcome(Success, String.format("Bookmark %s updated to %s", name, newRev.shortNotation()));
		}
		return new Outcome(Failure, String.format("Bookmark update (%s: %s -> %s) failed", name, oldRev.shortNotation(), newRev.shortNotation()));
	}
	
	public Phases getPhases() throws HgRemoteConnectionException, HgRuntimeException {
		initCapabilities();
		if (!remoteCapabilities.contains(CMD_PUSHKEY)) {
			// old server defaults to publishing
			return new Phases(true, Collections.<Nodeid>emptyList());
		}
		final List<Pair<String, String>> values = listkeys(NS_PHASES, "Get remote phases");
		boolean publishing = false;
		ArrayList<Nodeid> draftRoots = new ArrayList<Nodeid>();
		for (Pair<String, String> l : values) {
			if ("publishing".equalsIgnoreCase(l.first())) {
				publishing = Boolean.parseBoolean(l.second());
				continue;
			}
			Nodeid root = Nodeid.fromAscii(l.first());
			int ph = Integer.parseInt(l.second());
			if (ph == HgPhase.Draft.mercurialOrdinal()) {
				draftRoots.add(root);
			} else {
				assert false;
				sessionContext.getLog().dump(getClass(), Severity.Error, "Unexpected phase value %d for revision %s", ph, root);
			}
		}
		return new Phases(publishing, draftRoots);
	}
	
	public Outcome updatePhase(HgPhase from, HgPhase to, Nodeid n) throws HgRemoteConnectionException, HgRuntimeException {
		initCapabilities();
		if (!remoteCapabilities.contains(CMD_PUSHKEY)) {
			return new Outcome(Failure, "Server doesn't support pushkey protocol");
		}
		if (pushkey("Update remote phases", NS_PHASES, n.toString(), String.valueOf(from.mercurialOrdinal()), String.valueOf(to.mercurialOrdinal()))) {
			return new Outcome(Success, String.format("Phase of %s updated to %s", n.shortNotation(), to.name()));
		}
		return new Outcome(Failure, String.format("Phase update (%s: %s -> %s) failed", n.shortNotation(), from.name(), to.name()));
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + '[' + getLocation() + ']';
	}
	
	
	private void initCapabilities() throws HgRemoteConnectionException {
		if (remoteCapabilities != null) {
			return;
		}
		remote.connect();
		try {
			remote.sessionBegin();
			String capsLine = remote.getCapabilities();
			String[] caps = capsLine.split("\\s");
			remoteCapabilities = new HashSet<String>(Arrays.asList(caps));
		} finally {
			remote.sessionEnd();
		}
	}

	private HgLookup getLookupHelper() {
		if (lookupHelper == null) {
			lookupHelper = new HgLookup(sessionContext);
		}
		return lookupHelper;
	}

	private List<Pair<String,String>> listkeys(String namespace, String actionName) throws HgRemoteConnectionException, HgRuntimeException {
		try {
			remote.sessionBegin();
			ArrayList<Pair<String, String>> rv = new ArrayList<Pair<String, String>>();
			InputStream response = remote.listkeys(namespace, actionName);
			// output of listkeys is encoded with UTF-8
			BufferedReader r = new BufferedReader(new InputStreamReader(response, EncodingHelper.getUTF8()));
			String l;
			while ((l = r.readLine()) != null) {
				int sep = l.indexOf('\t');
				if (sep == -1) {
					sessionContext.getLog().dump(getClass(), Severity.Warn, "%s: bad line '%s', ignored", actionName, l);
					continue;
				}
				rv.add(new Pair<String,String>(l.substring(0, sep), l.substring(sep+1)));
			}
			r.close();
			return rv;
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_LISTKEYS).setServerInfo(getLocation());
		} finally {
			remote.sessionEnd();
		}
	}
	
	private boolean pushkey(String opName, String namespace, String key, String oldValue, String newValue) throws HgRemoteConnectionException, HgRuntimeException {
		try {
			remote.sessionBegin();
			final InputStream is = remote.pushkey(opName, namespace, key, oldValue, newValue);
			int rv = is.read();
			is.close();
			return rv == '1';
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_PUSHKEY).setServerInfo(getLocation());
		} finally {
			remote.sessionEnd();
		}
	}
	
	private File writeBundle(InputStream is) throws IOException {
		File tf = File.createTempFile("hg4j-bundle-", null);
		new FileUtils(sessionContext.getLog(), this).write(is, tf);
		is.close();
		return tf;
	}


	public static final class Range {
		/**
		 * Root of the range, earlier revision
		 */
		public final Nodeid start;
		/**
		 * Head of the range, later revision.
		 */
		public final Nodeid end;
		
		/**
		 * @param from - root/base revision
		 * @param to - head/tip revision
		 */
		public Range(Nodeid from, Nodeid to) {
			start = from;
			end = to;
		}
		
		/**
		 * Append this range as pair of values 'end-start' to the supplied buffer and return the buffer.
		 */
		public StringBuilder append(StringBuilder sb) {
			sb.append(end.toString());
			sb.append('-');
			sb.append(start.toString());
			return sb;
		}
	}

	public static final class RemoteBranch {
		public final Nodeid head, root, p1, p2;
		
		public RemoteBranch(Nodeid h, Nodeid r, Nodeid parent1, Nodeid parent2) {
			head = h;
			root = r;
			p1 = parent1;
			p2 = parent2;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (false == obj instanceof RemoteBranch) {
				return false;
			}
			RemoteBranch o = (RemoteBranch) obj;
			// in fact, p1 and p2 are not supposed to be null, ever (at least for RemoteBranch created from server output)
			return head.equals(o.head) && root.equals(o.root) && (p1 == null && o.p1 == null || p1.equals(o.p1)) && (p2 == null && o.p2 == null || p2.equals(o.p2));
		}
	}

	public static final class Bookmarks implements Iterable<Pair<String, Nodeid>> {
		private final List<Pair<String, Nodeid>> bm;

		private Bookmarks(List<Pair<String, Nodeid>> bookmarks) {
			bm = bookmarks;
		}

		public Iterator<Pair<String, Nodeid>> iterator() {
			return bm.iterator();
		}
	}
	
	public static final class Phases {
		private final boolean pub;
		private final List<Nodeid> droots;
		
		private Phases(boolean publishing, List<Nodeid> draftRoots) {
			pub = publishing;
			droots = draftRoots;
		}
		
		/**
		 * Non-publishing servers may (shall?) respond with a list of draft roots.
		 * This method doesn't make sense when {@link #isPublishingServer()} is <code>true</code>
		 * 
		 * @return list of draft roots on remote server
		 */
		public List<Nodeid> draftRoots() {
			return droots;
		}

		/**
		 * @return <code>true</code> if revisions on remote server shall be deemed published (either 
		 * old server w/o explicit setting, or a new one with <code>phases.publish == true</code>)
		 */
		public boolean isPublishingServer() {
			return pub;
		}
	}
}
