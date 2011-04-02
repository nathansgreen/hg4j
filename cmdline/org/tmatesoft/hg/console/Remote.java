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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
	 cmd=between needs argument pairs, with first element in the pair to be head(!), second to be root of the branch (
	 	i.e. (newer-older), not (older-newer) as one might expect. Returned list of nodes comes in reversed order (from newer
	 	to older) as well

	cmd=branches&nodes=d6d2a630f4a6d670c90a5ca909150f2b426ec88f+
	head, root, first parent, second parent
	received: d6d2a630f4a6d670c90a5ca909150f2b426ec88f dbd663faec1f0175619cf7668bddc6350548b8d6 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000
	
	Sequence, for actual state with merged/closed branch, where 157:d5268ca7715b8d96204fc62abc632e8f55761547 is merge revision of 156 and 53 
	>branches, 170:71ddbf8603e8e09d54ac9c5fe4bb5ae824589f1d
	 71ddbf8603e8e09d54ac9c5fe4bb5ae824589f1d d5268ca7715b8d96204fc62abc632e8f55761547 643ddec3be36246fc052cf22ece503fa60cafe22 a6f39e595b2b54f56304470269a936ead77f5725

	>branches, 156:643ddec3be36246fc052cf22ece503fa60cafe22
	 643ddec3be36246fc052cf22ece503fa60cafe22 ade65afe0906febafbf8a2e41002052e0e446471 08754fce5778a3409476ecdb3cec6b5172c34367 40d04c4f771ebbd599eb229145252732a596740a
	>branches, 53:a6f39e595b2b54f56304470269a936ead77f5725
	 a6f39e595b2b54f56304470269a936ead77f5725 a6f39e595b2b54f56304470269a936ead77f5725 9429c7bd1920fab164a9d2b621d38d57bcb49ae0 30bd389788464287cee22ccff54c330a4b715de5

	>branches, 84:08754fce5778a3409476ecdb3cec6b5172c34367  (p1:82) 
	 08754fce5778a3409476ecdb3cec6b5172c34367 dbd663faec1f0175619cf7668bddc6350548b8d6 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000
	>branches, 83:40d04c4f771ebbd599eb229145252732a596740a (p1:80)
	 40d04c4f771ebbd599eb229145252732a596740a dbd663faec1f0175619cf7668bddc6350548b8d6 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000

	>branches, 51:9429c7bd1920fab164a9d2b621d38d57bcb49ae0 (wrap-data-access branch)
	 9429c7bd1920fab164a9d2b621d38d57bcb49ae0 dbd663faec1f0175619cf7668bddc6350548b8d6 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000
	>branches, 52:30bd389788464287cee22ccff54c330a4b715de5 (p1:50)
	 30bd389788464287cee22ccff54c330a4b715de5 dbd663faec1f0175619cf7668bddc6350548b8d6 0000000000000000000000000000000000000000 0000000000000000000000000000000000000000


	cmd=between&pairs=71ddbf8603e8e09d54ac9c5fe4bb5ae824589f1d-d5268ca7715b8d96204fc62abc632e8f55761547+40d04c4f771ebbd599eb229145252732a596740a-dbd663faec1f0175619cf7668bddc6350548b8d6
	 8c8e3f372fa1fbfcf92b004b6f2ada2dbaf60028 dd525ca65de8e78cb133919de57ea0a6e6454664 1d0654be1466d522994f8bead510e360fbeb8d79 c17a08095e4420202ac1b2d939ef6d5f8bebb569
	 4222b04f34ee885bc1ad547c7ef330e18a51afc1 5f9635c016819b322ae05a91b3378621b538c933 c677e159391925a50b9a23f557426b2246bc9c5d 0d279bcc44427cb5ae2f3407c02f21187ccc8aea e21df6259f8374ac136767321e837c0c6dd21907 b01500fe2604c2c7eadf44349cce9f438484474b 865bf07f381ff7d1b742453568def92576af80b6

	Between two subsequent revisions (i.e. direct child in remote of a local root) 
	cmd=between&pairs=71ddbf8603e8e09d54ac9c5fe4bb5ae824589f1d-8c8e3f372fa1fbfcf92b004b6f2ada2dbaf60028
	 empty result
	 */
	public static void main(String[] args) throws Exception {
		ConfigFile cfg = new Internals().newConfigFile();
		cfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
		String svnkitServer = cfg.getSection("paths").get("svnkit");
//		URL url = new URL(svnkitServer + "?cmd=branches&nodes=30bd389788464287cee22ccff54c330a4b715de5");
		URL url = new URL(svnkitServer + "?cmd=between"); 
//		URL url = new URL(svnkitServer + "?cmd=changegroup&roots=" + Nodeid.NULL.toString());
//		URL url = new URL("http://localhost:8000/" + "?cmd=between");
//		URL url = new URL(svnkitServer + "?cmd=stream_out");
	
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
		// Hack to get Base64-encoded credentials
		Preferences tempNode = Preferences.userRoot().node("xxx");
		tempNode.putByteArray("xxx", url.getUserInfo().getBytes());
		String authInfo = tempNode.get("xxx", null);
		tempNode.removeNode();
		//
		sslContext.init(null, new TrustManager[] { new TrustEveryone() }, null);
		HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
//		HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
		urlConnection.setRequestProperty("User-Agent", "jhg/0.1.0");
		urlConnection.setRequestProperty("Accept", "application/mercurial-0.1");
		urlConnection.setRequestProperty("Authorization", "Basic " + authInfo);
		urlConnection.setSSLSocketFactory(sslContext.getSocketFactory());
		byte[] body = "pairs=30bd389788464287cee22ccff54c330a4b715de5-dbd663faec1f0175619cf7668bddc6350548b8d6".getBytes();
		urlConnection.setRequestMethod("POST");
		urlConnection.setRequestProperty("Content-Length", String.valueOf(body.length));
		urlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		urlConnection.setDoOutput(true);
		urlConnection.setDoInput(true);
//		urlConnection.connect();
		OutputStream os = urlConnection.getOutputStream();
		os.write(body);
		os.flush();
		os.close();
		System.out.println("Query:" + url.getQuery());
		System.out.println("Response headers:");
		final Map<String, List<String>> headerFields = urlConnection.getHeaderFields();
		for (String s : headerFields.keySet()) {
			System.out.printf("%s: %s\n", s, urlConnection.getHeaderField(s));
		}
		System.out.printf("Content type is %s and its length is %d\n", urlConnection.getContentType(), urlConnection.getContentLength());
		InputStream is = urlConnection.getInputStream();
		//
		dump(is, -1); // simple dump, any cmd
//		writeBundle(is, false, "HG10GZ"); // cmd=changegroup
		//writeBundle(is, true, "" or "HG10UN");
		//
		urlConnection.disconnect();
		//
	}

	private static void dump(InputStream is, int limit) throws IOException {
		int b;
		while ((b =is.read()) != -1) {
			System.out.print((char) b);
			if (limit != -1) {
				if (--limit < 0) {
					break;
				}
			}
		}
		System.out.println();
	}
	
	private static void writeBundle(InputStream is, boolean decompress, String header) throws IOException {
		InputStream zipStream = decompress ? new InflaterInputStream(is) : is;
		File tf = File.createTempFile("hg-bundle-", null);
		FileOutputStream fos = new FileOutputStream(tf);
		fos.write(header.getBytes());
		int r;
		byte[] buf = new byte[8*1024];
		while ((r = zipStream.read(buf)) != -1) {
			fos.write(buf, 0, r);
		}
		fos.close();
		zipStream.close();
		System.out.println(tf);
	}
}
