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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.tmatesoft.hg.auth.HgAuthFailedException;
import org.tmatesoft.hg.auth.HgAuthMethod;
import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInvalidStateException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HttpAuthMethod implements HgAuthMethod {
	
	private final SessionContext ctx;
	private final URL url;
	private String authInfo;
	private SSLContext sslContext;

	/**
	 * @param sessionContext
	 * @param url location fully ready to attempt connection to perform authentication check, e.g. hello command (anything with *small* output will do)
	 * @throws HgRemoteConnectionException
	 */
	HttpAuthMethod(SessionContext sessionContext, URL url) throws HgRemoteConnectionException {
		ctx = sessionContext;
		if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
			throw new HgInvalidStateException(String.format("http protocol expected: %s", url.toString()));
		}
		this.url = url;
		if ("https".equals(url.getProtocol())) {
			try {
				sslContext = SSLContext.getInstance("SSL");
				class TrustEveryone implements X509TrustManager {
					public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					}
					public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
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
	
	public void tryWithUserInfo(String uriUserInfo) throws HgAuthFailedException {
		int colon = uriUserInfo.indexOf(':');
		if (colon == -1) {
			withPassword(uriUserInfo, null);
		} else {
			withPassword(uriUserInfo.substring(0, colon), uriUserInfo.substring(colon+1));
		}
	}

	public void noCredentials() throws HgAuthFailedException {
		// TODO Auto-generated method stub
		checkConnection();
	}

	public boolean supportsPassword() {
		return true;
	}

	public void withPassword(String username, String password) throws HgAuthFailedException {
		authInfo = buildAuthValue(username, password == null ? "" : password);
		checkConnection();
	}

	public boolean supportsPublicKey() {
		return false;
	}

	public void withPublicKey(String username, InputStream privateKey, String passphrase) throws HgAuthFailedException {
	}

	public boolean supportsCertificate() {
		return "https".equals(url.getProtocol());
	}

	public void withCertificate(X509Certificate[] clientCert) throws HgAuthFailedException {
		// TODO Auto-generated method stub
		checkConnection();
	}

	private void checkConnection() throws HgAuthFailedException {
		// we've checked the protocol to be http(s)
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) url.openConnection();
			c = setupConnection(c);
			c.connect();
			InputStream is = c.getInputStream();
			while (is.read() != -1) {
			}
			is.close();
			final int HTTP_UNAUTHORIZED = 401;
			if (c.getResponseCode() == HTTP_UNAUTHORIZED) {
				throw new HgAuthFailedException(c.getResponseMessage(), null);
			}
		} catch (IOException ex) {
			throw new HgAuthFailedException("Communication failure while authenticating", ex);
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}

	HttpURLConnection setupConnection(HttpURLConnection urlConnection) {
		if (authInfo != null) {
			urlConnection.addRequestProperty("Authorization", "Basic " + authInfo);
		}
		if (sslContext != null) {
			((HttpsURLConnection) urlConnection).setSSLSocketFactory(sslContext.getSocketFactory());
		}
		return urlConnection;
	}

	private String buildAuthValue(String username, String password) {
		String ai = null;
		try {
			// Hack to get Base64-encoded credentials
			Preferences tempNode = Preferences.userRoot().node("xxx");
			tempNode.putByteArray("xxx", String.format("%s:%s", username, password).getBytes());
			ai = tempNode.get("xxx", null);
			tempNode.removeNode();
		} catch (BackingStoreException ex) {
			ctx.getLog().dump(getClass(), Info, ex, null);
			// IGNORE
		}
		return ai;
	}
}
