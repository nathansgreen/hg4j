/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.internal.remote;

import static org.tmatesoft.hg.util.LogFacility.Severity.Info;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.PropertyMarshal;
import org.tmatesoft.hg.repo.HgRemoteRepository.Range;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HttpConnector implements Connector {
	private URI uri;
	private URL url;
	private SSLContext sslContext;
	private String authInfo;
	private boolean debug;
	private SessionContext sessionCtx;
	//
	private HttpURLConnection conn;

	public void init(URI uri, SessionContext sessionContext, Object globalConfig) throws HgRuntimeException {
		this.uri = uri;
		sessionCtx = sessionContext;
		debug = new PropertyMarshal(sessionCtx).getBoolean("hg4j.remote.debug", false);
		if (uri.getUserInfo() != null) {
			String ai = null;
			try {
				// Hack to get Base64-encoded credentials
				Preferences tempNode = Preferences.userRoot().node("xxx");
				tempNode.putByteArray("xxx", uri.getUserInfo().getBytes());
				ai = tempNode.get("xxx", null);
				tempNode.removeNode();
			} catch (BackingStoreException ex) {
				sessionContext.getLog().dump(getClass(), Info, ex, null);
				// IGNORE
			}
			authInfo = ai;
		} else {
			authInfo = null;
		}
	}
	
	public void connect() throws HgRemoteConnectionException, HgRuntimeException {
		try {
			url = uri.toURL();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex);
		}
		if ("https".equals(url.getProtocol())) {
			try {
				sslContext = SSLContext.getInstance("SSL");
				class TrustEveryone implements X509TrustManager {
					public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
						if (debug) {
							System.out.println("checkClientTrusted:" + authType);
						}
					}
					public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
						if (debug) {
							System.out.println("checkServerTrusted:" + authType);
						}
					}
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}
				};
				sslContext.init(null, new TrustManager[] { new TrustEveryone() }, null);
			} catch (Exception ex) {
				throw new HgRemoteConnectionException("Can't initialize secure connection", ex);
			}
		} else {
			sslContext = null;
		}
	}

	public void disconnect() throws HgRemoteConnectionException, HgRuntimeException {
		// TODO Auto-generated method stub

	}

	public void sessionBegin() throws HgRemoteConnectionException, HgRuntimeException {
		// TODO Auto-generated method stub

	}

	public void sessionEnd() throws HgRemoteConnectionException, HgRuntimeException {
		if (conn != null) {
			conn.disconnect();
			conn = null;
		}
	}
	
	public String getServerLocation() {
		if (uri.getUserInfo() == null) {
			return uri.toString();
		}
		if (uri.getPort() != -1) {
			return String.format("%s://%s:%d%s", uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
		} else {
			return String.format("%s://%s%s", uri.getScheme(), uri.getHost(), uri.getPath());
		}
	}

	public String getCapabilities() throws HgRemoteConnectionException {
		// say hello to server, check response
		try {
			URL u = new URL(url, url.getPath() + "?cmd=hello");
			HttpURLConnection c = setupConnection(u.openConnection());
			c.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "US-ASCII"));
			String line = r.readLine();
			c.disconnect();
			final String capsPrefix = CMD_CAPABILITIES + ':';
			if (line != null && line.startsWith(capsPrefix)) {
				return line.substring(capsPrefix.length()).trim();
			}
			// for whatever reason, some servers do not respond to hello command (e.g. svnkit)
			// but respond to 'capabilities' instead. Try it.
			// TODO [post-1.0] tests needed
			u = new URL(url, url.getPath() + "?cmd=capabilities");
			c = setupConnection(u.openConnection());
			c.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			r = new BufferedReader(new InputStreamReader(c.getInputStream(), "US-ASCII"));
			line = r.readLine();
			c.disconnect();
			if (line != null && line.startsWith(capsPrefix)) {
				return line.substring(capsPrefix.length()).trim();
			}
			return new String();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand(CMD_HELLO).setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_HELLO).setServerInfo(getServerLocation());
		}
	}

	public InputStream heads() throws HgRemoteConnectionException, HgRuntimeException {
		try {
			URL u = new URL(url, url.getPath() + "?cmd=heads");
			conn = setupConnection(u.openConnection());
			conn.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			return conn.getInputStream();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand(CMD_HEADS).setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_HEADS).setServerInfo(getServerLocation());
		}
	}

	public InputStream between(Collection<Range> ranges) throws HgRemoteConnectionException, HgRuntimeException {
		StringBuilder sb = new StringBuilder(20 + ranges.size() * 82);
		sb.append("pairs=");
		for (Range r : ranges) {
			r.append(sb);
			sb.append('+');
		}
		if (sb.charAt(sb.length() - 1) == '+') {
			// strip last space 
			sb.setLength(sb.length() - 1);
		}
		try {
			boolean usePOST = ranges.size() > 3;
			URL u = new URL(url, url.getPath() + "?cmd=between" + (usePOST ? "" : '&' + sb.toString()));
			conn = setupConnection(u.openConnection());
			if (usePOST) {
				conn.setRequestMethod("POST");
				conn.setRequestProperty("Content-Length", String.valueOf(sb.length()/*nodeids are ASCII, bytes == characters */));
				conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
				conn.setDoOutput(true);
				conn.connect();
				OutputStream os = conn.getOutputStream();
				os.write(sb.toString().getBytes());
				os.flush();
				os.close();
			} else {
				conn.connect();
			}
			if (debug) {
				System.out.printf("%d ranges, method:%s \n", ranges.size(), conn.getRequestMethod());
				dumpResponseHeader(u);
			}
			return conn.getInputStream();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand(CMD_BETWEEN).setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_BETWEEN).setServerInfo(getServerLocation());
		}
	}

	public InputStream branches(List<Nodeid> nodes) throws HgRemoteConnectionException, HgRuntimeException {
		StringBuilder sb = appendNodeidListArgument("nodes", nodes, null);
		try {
			URL u = new URL(url, url.getPath() + "?cmd=branches&" + sb.toString());
			conn = setupConnection(u.openConnection());
			conn.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			return conn.getInputStream();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand(CMD_BRANCHES).setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_BRANCHES).setServerInfo(getServerLocation());
		}
	}

	public InputStream changegroup(List<Nodeid> roots) throws HgRemoteConnectionException, HgRuntimeException {
		StringBuilder sb = appendNodeidListArgument("roots", roots, null);
		try {
			URL u = new URL(url, url.getPath() + "?cmd=changegroup&" + sb.toString());
			conn = setupConnection(u.openConnection());
			conn.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			InputStream cg = conn.getInputStream();
			InputStream prefix = new ByteArrayInputStream("HG10GZ".getBytes()); // didn't see any other that zip
			return new SequenceInputStream(prefix, cg);
		} catch (MalformedURLException ex) {
			// although there's little user can do about this issue (URLs are constructed by our code)
			// it's still better to throw it as checked exception than RT because url is likely malformed due to parameters
			// and this may help user to understand the cause (and e.g. change them)
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand("changegroup").setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand("changegroup").setServerInfo(getServerLocation());
		}
	}

	//
	// FIXME consider HttpURLConnection#setChunkedStreamingMode() as described at
	// http://stackoverflow.com/questions/2793150/how-to-use-java-net-urlconnection-to-fire-and-handle-http-requests
	public OutputStream unbundle(long outputLen, List<Nodeid> remoteHeads) throws HgRemoteConnectionException, HgRuntimeException {
		StringBuilder sb = appendNodeidListArgument(CMD_HEADS, remoteHeads, null);
		try {
			final URL u = new URL(url, url.getPath() + "?cmd=unbundle&" + sb.toString());
			conn = setupConnection(u.openConnection());
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "application/mercurial-0.1");
			conn.setRequestProperty("Content-Length", String.valueOf(outputLen));
			conn.connect();
			return new FilterOutputStream(conn.getOutputStream()) {
				public void close() throws IOException {
					super.close();
					if (debug) {
						dumpResponseHeader(u);
					}
					try {
						checkResponseOk("Push", CMD_UNBUNDLE);
					} catch (HgRemoteConnectionException ex) {
						IOException e = new IOException(ex.getMessage());
						// not e.initCause(ex); as HgRemoteConnectionException is just a message holder
						e.setStackTrace(ex.getStackTrace());
						throw e;
					}
				}
			};
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand(CMD_UNBUNDLE).setServerInfo(getServerLocation());
		} catch (IOException ex) {
			// FIXME consume c.getErrorStream as http://docs.oracle.com/javase/6/docs/technotes/guides/net/http-keepalive.html suggests
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_UNBUNDLE).setServerInfo(getServerLocation());
		}
	}

	public InputStream pushkey(String opName, String namespace, String key, String oldValue, String newValue) throws HgRemoteConnectionException, HgRuntimeException {
		try {
			final String p = String.format("%s?cmd=pushkey&namespace=%s&key=%s&old=%s&new=%s", url.getPath(), namespace, key, oldValue, newValue);
			URL u = new URL(url, p);
			conn = setupConnection(u.openConnection());
			conn.setRequestMethod("POST");
			conn.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			checkResponseOk(opName, "pushkey");
			return conn.getInputStream();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand("pushkey").setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand("pushkey").setServerInfo(getServerLocation());
		}
	}

	public InputStream listkeys(String namespace, String actionName) throws HgRemoteConnectionException, HgRuntimeException {
		try {
			URL u = new URL(url, url.getPath() + "?cmd=listkeys&namespace=" + namespace);
			conn = setupConnection(u.openConnection());
			conn.connect();
			if (debug) {
				dumpResponseHeader(u);
			}
			checkResponseOk(actionName, "listkeys");
			return conn.getInputStream();
		} catch (MalformedURLException ex) {
			throw new HgRemoteConnectionException("Bad URL", ex).setRemoteCommand(CMD_LISTKEYS).setServerInfo(getServerLocation());
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_LISTKEYS).setServerInfo(getServerLocation());
		}
	}
	
	private void checkResponseOk(String opName, String remoteCmd) throws HgRemoteConnectionException, IOException {
		if (conn.getResponseCode() != 200) {
			String m = conn.getResponseMessage() == null ? "unknown reason" : conn.getResponseMessage();
			String em = String.format("%s failed: %s (HTTP error:%d)", opName, m, conn.getResponseCode());
			throw new HgRemoteConnectionException(em).setRemoteCommand(remoteCmd).setServerInfo(getServerLocation());
		}
	}

	private HttpURLConnection setupConnection(URLConnection urlConnection) {
		urlConnection.setRequestProperty("User-Agent", "hg4j/1.0.0");
		urlConnection.addRequestProperty("Accept", "application/mercurial-0.1");
		if (authInfo != null) {
			urlConnection.addRequestProperty("Authorization", "Basic " + authInfo);
		}
		if (sslContext != null) {
			((HttpsURLConnection) urlConnection).setSSLSocketFactory(sslContext.getSocketFactory());
		}
		return (HttpURLConnection) urlConnection;
	}
	
	private StringBuilder appendNodeidListArgument(String key, List<Nodeid> values, StringBuilder sb) {
		if (sb == null) {
			sb = new StringBuilder(20 + values.size() * 41);
		}
		sb.append(key);
		sb.append('=');
		for (Nodeid n : values) {
			sb.append(n.toString());
			sb.append('+');
		}
		if (sb.charAt(sb.length() - 1) == '+') {
			// strip last space 
			sb.setLength(sb.length() - 1);
		}
		return sb;
	}

	private void dumpResponseHeader(URL u) {
		System.out.printf("Query (%d bytes):%s\n", u.getQuery().length(), u.getQuery());
		System.out.println("Response headers:");
		final Map<String, List<String>> headerFields = conn.getHeaderFields();
		for (String s : headerFields.keySet()) {
			System.out.printf("%s: %s\n", s, conn.getHeaderField(s));
		}
	}
}
