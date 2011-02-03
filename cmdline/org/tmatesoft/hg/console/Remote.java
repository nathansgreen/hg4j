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
package org.tmatesoft.hg.console;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.Internals;

/**
 * WORK IN PROGRESS, DO NOT USE
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Remote {

	/*
	 * @see http://mercurial.selenic.com/wiki/WireProtocol
	 cmd=branches gives 4 nodeids (head, root, first parent, second parent) per line (few lines possible, per branch, perhaps?)
	 cmd=capabilities gives lookup ...subset and 3 compress methods
	 // lookup changegroupsubset unbundle=HG10GZ,HG10BZ,HG10UN
	 cmd=heads gives space-separated list of nodeids (or just one)
	 nodeids are in hex (printable) format, need to convert fromAscii()
	 cmd=branchmap
	 */
	public static void main(String[] args) throws Exception {
		String nid = "d6d2a630f4a6d670c90a5ca909150f2b426ec88f";
		ConfigFile cfg = new Internals().newConfigFile();
		cfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
		String svnkitServer = cfg.getSection("paths").get("svnkit");
		URL url = new URL(svnkitServer + "?cmd=changegroup&roots=a78c980749e3ccebb47138b547e9b644a22797a9");
	
		SSLContext sslContext = SSLContext.getInstance("SSL");
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
		}
		//
		Preferences tempNode = Preferences.userRoot().node("xxx");
		tempNode.putByteArray("xxx", url.getUserInfo().getBytes());
		String authInfo = tempNode.get("xxx", null);
		tempNode.removeNode();
		//
		sslContext.init(null, new TrustManager[] { new TrustEveryone() }, null);
		HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
		urlConnection.addRequestProperty("User-Agent", "jhg/0.1.0");
		urlConnection.addRequestProperty("Accept", "application/mercurial-0.1");
		urlConnection.addRequestProperty("Authorization", "Basic " + authInfo);
		urlConnection.setSSLSocketFactory(sslContext.getSocketFactory());
		urlConnection.connect();
		System.out.println("Response headers:");
		final Map<String, List<String>> headerFields = urlConnection.getHeaderFields();
		for (String s : headerFields.keySet()) {
			System.out.printf("%s: %s\n", s, urlConnection.getHeaderField(s));
		}
		System.out.printf("Content type is %s and its length is %d\n", urlConnection.getContentType(), urlConnection.getContentLength());
		InputStream is = urlConnection.getInputStream();
//		int b;
//		while ((b =is.read()) != -1) {
//			System.out.print((char) b);
//		}
//		System.out.println();
		InflaterInputStream zipStream = new InflaterInputStream(is);
		File tf = File.createTempFile("hg-bundle-", null);
		FileOutputStream fos = new FileOutputStream(tf);
		int r;
		byte[] buf = new byte[8*1024];
		while ((r = zipStream.read(buf)) != -1) {
			fos.write(buf, 0, r);
		}
		fos.close();
		zipStream.close();
		System.out.println(tf);
		
		urlConnection.disconnect();
		//
	}
}
