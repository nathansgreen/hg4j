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
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgRemoteRepository.Range;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.LogFacility.Severity;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.StreamGobbler;

/**
 * Remote repository via SSH
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class SshConnector implements Connector {
	private SessionContext sessionCtx;
	private URI uri;
	private Connection conn;
	private Session session;
	private int sessionUse;

	private StreamGobbler remoteErr, remoteOut;
	private OutputStream remoteIn;
	
	public void init(URI uri, SessionContext sessionContext, Object globalConfig) throws HgRuntimeException {
		sessionCtx = sessionContext;
		this.uri = uri;
	}
	
	public void connect() throws HgRemoteConnectionException, HgRuntimeException {
		try {
			conn = new Connection(uri.getHost(), uri.getPort() == -1 ? 22 : uri.getPort());
			conn.connect();
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to establish connection");
		}
		try {
			conn.authenticateWithPublicKey(System.getProperty("user.name"), new File(System.getProperty("user.home"), ".ssh/id_rsa"), null);
			ConnectionInfo ci = conn.getConnectionInfo();
			System.out.printf("%s %s %s %d %s %s %s\n", ci.clientToServerCryptoAlgorithm, ci.clientToServerMACAlgorithm, ci.keyExchangeAlgorithm, ci.keyExchangeCounter, ci.serverHostKeyAlgorithm, ci.serverToClientCryptoAlgorithm, ci.serverToClientMACAlgorithm);
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to authenticate", ex).setServerInfo(getServerLocation());
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
			final String path = uri.getPath();
			session.execCommand(String.format("hg -R %s serve --stdio", path.charAt(0) == '/' ? path.substring(1) : path));
			remoteErr = new StreamGobbler(session.getStderr());
			remoteOut = new StreamGobbler(session.getStdout());
			remoteIn = session.getStdin();
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

	public String getServerLocation() {
		return uri.toString(); // FIXME
	}
	
	public String getCapabilities() throws HgRemoteConnectionException {
		try {
			consume(remoteOut);
			consume(remoteErr);
			remoteIn.write(CMD_HELLO.getBytes());
			remoteIn.write('\n');
			remoteIn.write(CMD_CAPABILITIES.getBytes()); // see http connector for details
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
						return line.substring(CMD_CAPABILITIES.length() + 1);
					}
				}
			}
			r.close();
			consume(remoteOut);
			checkError();
			return new String();
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Failed to initiate dialog with server", ex).setRemoteCommand(CMD_HELLO).setServerInfo(getServerLocation());
		}
	}

	public InputStream heads() throws HgRemoteConnectionException {
		return executeCommand("heads", Collections.<Parameter>emptyList(), true);
	}
	
	public InputStream between(Collection<Range> ranges) throws HgRemoteConnectionException {
		StringBuilder sb = new StringBuilder(ranges.size() * 82);
		for (Range r : ranges) {
			r.append(sb).append(' ');
		}
		if (!ranges.isEmpty()) {
			sb.setLength(sb.length() - 1);
		}
		return executeCommand("between", Collections.singletonList(new Parameter("pairs", sb.toString())), true);
	}
	
	public InputStream branches(List<Nodeid> nodes) throws HgRemoteConnectionException {
		String l = join(nodes, ' ');
		return executeCommand("branches", Collections.singletonList(new Parameter("nodes", l)), true);
	}
	
	public InputStream changegroup(List<Nodeid> roots) throws HgRemoteConnectionException, HgRuntimeException {
		String l = join(roots, ' ');
		InputStream cg = executeCommand("changegroup", Collections.singletonList(new Parameter("roots", l)), false);
		InputStream prefix = new ByteArrayInputStream("HG10UN".getBytes());
		return new SequenceInputStream(prefix, cg);
	}

	public OutputStream unbundle(long outputLen, List<Nodeid> remoteHeads) throws HgRemoteConnectionException, HgRuntimeException {
		String l = join(remoteHeads, ' ');
		try {
			consume(remoteOut);
			consume(remoteErr);
			remoteIn.write(CMD_UNBUNDLE.getBytes());
			remoteIn.write('\n');
			writeParameters(Collections.singletonList(new Parameter("heads", l)));
			checkError();
			return new FilterOutputStream(remoteIn) {
				@Override
				public void close() throws IOException {
					out.flush();
					@SuppressWarnings("unused")
					int responseLen = readResponseLength();
					checkError();
					// XXX perhaps, need to return responseLen to caller? 
				}
			};
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(CMD_UNBUNDLE).setServerInfo(getServerLocation());
		}
	}

	public InputStream pushkey(String opName, String namespace, String key, String oldValue, String newValue) throws HgRemoteConnectionException, HgRuntimeException {
		ArrayList<Parameter> p = new ArrayList<Parameter>();
		p.add(new Parameter("namespace", namespace));
		p.add(new Parameter("key", key));
		p.add(new Parameter("old", oldValue));
		p.add(new Parameter("new", newValue));
		return executeCommand("pushkey", p, true);
	}
	
	public InputStream listkeys(String namespace, String actionName) throws HgRemoteConnectionException, HgRuntimeException {
		return executeCommand("listkeys", Collections.singletonList(new Parameter("namespace", namespace)), true);
	}
	
	private InputStream executeCommand(String cmd, List<Parameter> parameters, boolean expectResponseLength) throws HgRemoteConnectionException {
		try {
			consume(remoteOut);
			consume(remoteErr);
			remoteIn.write(cmd.getBytes());
			remoteIn.write('\n');
			writeParameters(parameters);
			checkError();
			if (expectResponseLength) {
				int responseLen = readResponseLength();
				checkError();
				return new FilterStream(remoteOut, responseLen);
			} else {
				return new FilterStream(remoteOut, Integer.MAX_VALUE);
			}
		} catch (IOException ex) {
			throw new HgRemoteConnectionException("Communication failure", ex).setRemoteCommand(cmd).setServerInfo(getServerLocation());
		}
	}
	
	private void writeParameters(List<Parameter> parameters) throws IOException {
		for (Parameter p : parameters) {
			remoteIn.write(p.name().getBytes());
			remoteIn.write(' ');
			remoteIn.write(String.valueOf(p.size()).getBytes());
			remoteIn.write('\n');
			remoteIn.write(p.data());
			remoteIn.write('\n');
		}
	}

	private void consume(InputStream is) throws IOException {
		while (is.available() > 0) {
			is.read();
		}
	}

	private void checkError() throws IOException {
		if (remoteErr.available() > 0) {
			StringBuilder sb = new StringBuilder();
			int c;
			while ((c = remoteErr.read()) != -1) {
				sb.append((char)c);
			}
			throw new IOException(sb.toString());
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
			length = 0;
			// INTENTIONALLY DOES NOT CLOSE THE STREAM
		}
	}
}
