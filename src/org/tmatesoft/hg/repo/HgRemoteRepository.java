/*
 * Copyright (c) 2011 TMate Software Ltd
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamTokenizer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;

/**
 * WORK IN PROGRESS, DO NOT USE
 * 
 * @see http://mercurial.selenic.com/wiki/WireProtocol
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgRemoteRepository {
	
	private final URL url;
	private final SSLContext sslContext;
	private final String authInfo;

	HgRemoteRepository(URL url) throws HgException {
		if (url == null) {
			throw new IllegalArgumentException();
		}
		this.url = url;
		if ("https".equals(url.getProtocol())) {
			try {
				sslContext = SSLContext.getInstance("SSL");
				class TrustEveryone implements X509TrustManager {
					public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
						System.out.println("checkClientTrusted " + authType);
					}
					public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
						System.out.println("checkServerTrusted" + authType);
					}
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}
				};
				sslContext.init(null, new TrustManager[] { new TrustEveryone() }, null);
			} catch (Exception ex) {
				throw new HgException(ex);
			}
		} else {
			sslContext = null;
		}
		if (url.getUserInfo() != null) {
			String ai = null;
			try {
				// Hack to get Base64-encoded credentials
				Preferences tempNode = Preferences.userRoot().node("xxx");
				tempNode.putByteArray("xxx", url.getUserInfo().getBytes());
				ai = tempNode.get("xxx", null);
				tempNode.removeNode();
			} catch (BackingStoreException ex) {
				ex.printStackTrace();
				// IGNORE
			}
			authInfo = ai;
		} else {
			authInfo = null;
		}
	}
	
	public List<Nodeid> heads() {
		return Collections.singletonList(Nodeid.fromAscii("71ddbf8603e8e09d54ac9c5fe4bb5ae824589f1d"));
//		return Collections.emptyList();
	}
	
	public List<Nodeid> between(Nodeid tip, Nodeid base) throws HgException {
		try {
			LinkedList<Nodeid> rv = new LinkedList<Nodeid>();
			URL u = new URL(url, url.getPath() + "?cmd=between&pairs=" + tip.toString() + '-' + base.toString());
			URLConnection c = setupConnection(u.openConnection());
			c.connect();
			System.out.println("Query:" + u.getQuery());
			System.out.println("Response headers:");
			final Map<String, List<String>> headerFields = c.getHeaderFields();
			for (String s : headerFields.keySet()) {
				System.out.printf("%s: %s\n", s, c.getHeaderField(s));
			}
			InputStream is = c.getInputStream();
			StreamTokenizer st = new StreamTokenizer(is);
			st.ordinaryChars('0', '9');
			st.wordChars('0', '9');
			while (st.nextToken() != StreamTokenizer.TT_EOF) {
				System.out.println(st.sval);
				Nodeid nid = Nodeid.fromAscii(st.sval);
				rv.addLast(nid);
			}
			is.close();
			return rv;
		} catch (MalformedURLException ex) {
			throw new HgException(ex);
		} catch (IOException ex) {
			throw new HgException(ex);
		}
	}

	/**
	 * @param ranges
	 * @return map, where keys are input instances, values are corresponding server reply
	 * @throws HgException 
	 */
	public Map<Range, List<Nodeid>> between(Collection<Range> ranges) throws HgException {
		// if fact, shall do other way round, this method shall send 
		LinkedHashMap<Range, List<Nodeid>> rv = new LinkedHashMap<HgRemoteRepository.Range, List<Nodeid>>(ranges.size() * 4 / 3);
		for (Range r : ranges) {
			List<Nodeid> between = between(r.end, r.start);
			rv.put(r, between);
		}
		return rv;
	}

	public List<RemoteBranch> branches(List<Nodeid> nodes) {
		return Collections.emptyList();
	}

	// WireProtocol wiki: roots = a list of the latest nodes on every service side changeset branch that both the client and server know about. 
	public HgBundle getChanges(List<Nodeid> roots) throws HgException {
		return new HgLookup().loadBundle(new File("/temp/hg/hg-bundle-000000000000-gz.tmp"));
	}
	
	private URLConnection setupConnection(URLConnection urlConnection) {
		urlConnection.addRequestProperty("User-Agent", "hg4j/0.5.0");
		urlConnection.addRequestProperty("Accept", "application/mercurial-0.1");
		if (authInfo != null) {
			urlConnection.addRequestProperty("Authorization", "Basic " + authInfo);
		}
		if (sslContext != null) {
			((HttpsURLConnection) urlConnection).setSSLSocketFactory(sslContext.getSocketFactory());
		}
		return urlConnection;
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
			return head.equals(o.head) && root.equals(o.root) && (p1 == null && o.p1 == null || p1.equals(o.p1)) && (p2 == null && o.p2 == null || p2.equals(o.p2));
		}
	}
}
