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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgRemoteRepository.Range;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.LogFacility.Severity;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.ConnectionInfo;
import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.StreamGobbler;

/**
 * Remote repository via SSH
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class SshConnector {
	private SessionContext sessionCtx;
	private URL url;
	private Connection conn;
	private Session session;
	private int sessionUse;

	private StreamGobbler remoteErr, remoteOut;
	private OutputStream remoteIn;
	
	public void connect(URL url, SessionContext sessionContext, Object globalConfig) throws HgRemoteConnectionException {
		sessionCtx = sessionContext;
		this.url = url;
		try {
			conn = new Connection(url.getHost(), url.getPort() == -1 ? 22 : url.getPort());
			conn.connect();
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to establish connection");
		}
		try {
			conn.authenticateWithPublicKey(System.getProperty("user.name"), new File(System.getProperty("user.home"), ".ssh/id_rsa"), null);
			ConnectionInfo ci = conn.getConnectionInfo();
			System.out.printf("%s %s %s %d %s %s %s\n", ci.clientToServerCryptoAlgorithm, ci.clientToServerMACAlgorithm, ci.keyExchangeAlgorithm, ci.keyExchangeCounter, ci.serverHostKeyAlgorithm, ci.serverToClientCryptoAlgorithm, ci.serverToClientMACAlgorithm);
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to authenticate", ex).setServerInfo(getLocation());
		}
	}
	
	public void disconnect() throws HgRemoteConnectionException {
		if (session != null) {
			forceSessionClose();
		}
		if (conn != null) {
			conn.close();
			conn = null;
		}
	}
	
	public void sessionBegin() throws HgRemoteConnectionException {
		if (sessionUse > 0) {
			assert session != null;
			sessionUse++;
			return;
		}
		try {
			session = conn.openSession();
			session.execCommand(String.format("hg -R %s serve --stdio", url.getPath()));
			remoteErr = new StreamGobbler(session.getStderr());
			remoteOut = new StreamGobbler(session.getStdout());
			sessionUse = 1;
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to create ssh session", ex);
		}
	}
	
	public void sessionEnd() throws HgRemoteConnectionException {
		assert sessionUse > 0;
		assert session != null;
		if (sessionUse > 1) {
			sessionUse--;
			return;
		}
		forceSessionClose();
	}

	public String getLocation() {
		return "";
	}

	public InputStream heads() throws HgRemoteConnectionException {
		return executeCommand("heads", Collections.<Parameter>emptyList());
	}
	
	public InputStream between(Collection<Range> ranges) throws HgRemoteConnectionException {
		StringBuilder sb = new StringBuilder(ranges.size() * 82);
		for (Range r : ranges) {
			r.append(sb).append(' ');
		}
		if (!ranges.isEmpty()) {
			sb.setLength(sb.length() - 1);
		}
		return executeCommand("between", Collections.singletonList(new Parameter("pairs", sb.toString())));
	}
	
	public InputStream branches(List<Nodeid> nodes) throws HgRemoteConnectionException {
		String l = join(nodes, ' ');
		return executeCommand("branches", Collections.singletonList(new Parameter("nodes", l)));
	}
	
	public InputStream changegroup(List<Nodeid> roots) throws HgRemoteConnectionException, HgRuntimeException {
		String l = join(roots, ' ');
		return executeCommand("changegroup", Collections.singletonList(new Parameter("roots", l)));
	}

	public void unbundle(HgBundle bundle, List<Nodeid> remoteHeads) throws HgRemoteConnectionException, HgRuntimeException {
		String l = join(remoteHeads, ' ');
		Collections.singletonList(new Parameter("heads", l));
		throw Internals.notImplemented();
	}

	public InputStream pushkey(String opName, String namespace, String key, String oldValue, String newValue) throws HgRemoteConnectionException, HgRuntimeException {
		ArrayList<Parameter> p = new ArrayList<Parameter>();
		p.add(new Parameter("namespace", namespace));
		p.add(new Parameter("key", key));
		p.add(new Parameter("old", oldValue));
		p.add(new Parameter("new", newValue));
		return executeCommand("pushkey", p);
	}
	
	public InputStream listkeys(String namespace, String actionName) throws HgRemoteConnectionException, HgRuntimeException {
		return executeCommand("listkeys", Collections.singletonList(new Parameter("namespace", namespace)));
	}
	
	
	public Set<String> initCapabilities() throws HgRemoteConnectionException {
		try {
			final String CMD_CAPABILITIES = "capabilities";
			final String CMD_HEADS = "heads";
			final String CMD_HELLO = "hello";
			consume(remoteOut);
			consume(remoteErr);
			remoteIn.write(CMD_HELLO.getBytes());
			remoteIn.write('\n');
			remoteIn.write(CMD_CAPABILITIES.getBytes()); // see http connector for
			remoteIn.write('\n');
			remoteIn.write(CMD_HEADS.getBytes());
			remoteIn.write('\n');
			checkError();
			int responseLen = readResponseLength();
			checkError();
			FilterStream s = new FilterStream(remoteOut, responseLen);
			BufferedReader r = new BufferedReader(new InputStreamReader(s));
			String line;
			while ((line = r.readLine()) != null) {
				if (line.startsWith(CMD_CAPABILITIES) && line.length() > (CMD_CAPABILITIES.length()+1)) {
					line = line.substring(CMD_CAPABILITIES.length());
					if (line.charAt(0) == ':') {
						String[] caps = line.substring(CMD_CAPABILITIES.length() + 1).split("\\s");
						return new HashSet<String>(Arrays.asList(caps));
					}
				}
			}
			r.close();
			consume(remoteOut);
			checkError();
			return Collections.emptySet();
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to initiate dialog with server", ex).setRemoteCommand("hello").setServerInfo(getLocation());
		} catch (HgRemoteConnectionException ex) {
			ex.setRemoteCommand("hello").setServerInfo(getLocation());
			throw ex;
		}
	}
	
	private InputStream executeCommand(String cmd, List<Parameter> parameters) throws HgRemoteConnectionException {
		try {
			consume(remoteOut);
			consume(remoteErr);
			remoteIn.write(cmd.getBytes());
			remoteIn.write('\n');
			for (Parameter p : parameters) {
				remoteIn.write(p.name().getBytes());
				remoteIn.write(' ');
				remoteIn.write(String.valueOf(p.size()).getBytes());
				remoteIn.write('\n');
				remoteIn.write(p.data());
				remoteIn.write('\n');
			}
			checkError();
			int responseLen = readResponseLength();
			checkError();
			return new FilterStream(remoteOut, responseLen);
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(cmd).setServerInfo(getLocation());
		} catch (HgRemoteConnectionException ex) {
			ex.setRemoteCommand(cmd).setServerInfo(getLocation());
			throw ex;
		}
	}

	private void consume(InputStream is) throws IOException {
		while (is.available() > 0) {
			is.read();
		}
	}

	private void checkError() throws IOException, HgRemoteConnectionException {
		if (remoteErr.available() > 0) {
			StringBuilder sb = new StringBuilder();
			int c;
			while ((c = remoteErr.read()) != -1) {
				sb.append((char)c);
			}
			throw new HgRemoteConnectionException(sb.toString());
		}
	}
	
	private int readResponseLength() throws IOException {
		int c;
		StringBuilder sb = new StringBuilder();
		while ((c = remoteOut.read()) != -1) {
			if (c == '\n') {
				break;
			}
			sb.append((char) c);
		}
		if (c == -1) {
			throw new EOFException();
		}
		try {
			return Integer.parseInt(sb.toString());
		} catch (NumberFormatException ex) {
			throw new IOException(String.format("Expected response length instead of %s", sb));
		}
	}


	private void forceSessionClose() {
		if (session != null) {
			closeQuietly(remoteErr);
			closeQuietly(remoteOut);
			remoteErr = remoteOut = null;
			closeQuietly(remoteIn);
			remoteIn = null;
			session.close();
			session = null;
		}
		sessionUse = 0;
	}

	private void closeQuietly(Closeable c) {
		try {
			if (c != null) {
				c.close();
			}
		} catch (IOException ex) {
			sessionCtx.getLog().dump(getClass(), Severity.Warn, ex, null);
		}
	}

	private static String join(List<Nodeid> values, char sep) {
		StringBuilder sb = new StringBuilder(values.size() * 41);
		for (Nodeid n : values) {
			sb.append(n.toString());
			sb.append(sep);
		}
		if (!values.isEmpty()) {
			// strip last space 
			sb.setLength(sb.length() - 1);
		}
		return sb.toString();
	}

	private static final class Parameter {
		private final String name;
		private final byte[] data;

		public Parameter(String paramName, String paramValue) {
			assert paramName != null;
			assert paramValue != null;
			name = paramName;
			data = paramValue.getBytes();
		}

		public String name() {
			return name;
		}
		public int size() {
			return data.length;
		}
		public byte[] data() {
			return data;
		}
	}

	private static final class FilterStream extends FilterInputStream {
		private int length;

		public FilterStream(InputStream is, int initialLength) {
			super(is);
			length = initialLength;
		}
		
		@Override
		public int available() throws IOException {
			return Math.min(super.available(), length);
		}
		@Override
		public int read() throws IOException {
			if (length == 0) {
				return -1;
			}
			int r = super.read();
			if (r >= 0) {
				length--;
			}
			return r;
		}
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (length == 0) {
				return -1;
			}
			int r = super.read(b, off, Math.min(len, length));
			if (r >= 0) {
				assert r <= length;
				length -= r;
			}
			return r;
		}
		@Override
		public void close() throws IOException {
			// INTENTIONALLY DOES NOT CLOSE THE STREAM
		}
	}
}
