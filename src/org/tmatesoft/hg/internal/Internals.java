/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.internal.RequiresFile.*;
import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.StringTokenizer;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgRepoConfig.ExtensionsSection;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Fields/members that shall not be visible  
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Internals {
	
	/**
	 * Allows to specify Mercurial installation directory to detect installation-wide configurations.
	 * Without this property set, hg4j would attempt to deduce this value locating hg executable. 
	 */
	public static final String CFG_PROPERTY_HG_INSTALL_ROOT = "hg4j.hg.install_root";

	/**
	 * Tells repository not to cache files/revlogs
	 * XXX perhaps, need to respect this property not only for data files, but for manifest and changelog as well?
	 * (@see HgRepository#getChangelog and #getManifest())  
	 */
	public static final String CFG_PROPERTY_REVLOG_STREAM_CACHE = "hg4j.repo.disable_revlog_cache";
	
	/**
	 * Name of charset to use when translating Unicode filenames to Mercurial storage paths, string, 
	 * to resolve with {@link Charset#forName(String)}.
	 * E.g. <code>"cp1251"</code> or <code>"Latin-1"</code>.
	 * 
	 * <p>Mercurial uses system encoding when mangling storage paths. Default value
	 * based on 'file.encoding' Java system property is usually fine here, however
	 * in certain scenarios it may be desirable to force a different one, and this 
	 * property is exactly for this purpose.
	 * 
	 * <p>E.g. Eclipse defaults to project encoding (Launch config, Common page) when launching an application, 
	 * and if your project happen to use anything but filesystem default (say, UTF8 on cp1251 system),
	 * native storage paths won't match
	 */
	public static final String CFG_PROPERTY_FS_FILENAME_ENCODING = "hg.fs.filename.encoding";
	
	private int requiresFlags = 0;
	private List<Filter.Factory> filterFactories;
	private final SessionContext sessionContext;
	private final boolean isCaseSensitiveFileSystem;
	private final boolean shallCacheRevlogsInRepo;

	public Internals(SessionContext ctx) {
		sessionContext = ctx;
		isCaseSensitiveFileSystem = !runningOnWindows();
		shallCacheRevlogsInRepo = new PropertyMarshal(ctx).getBoolean(CFG_PROPERTY_REVLOG_STREAM_CACHE, true);
	}
	
	public void parseRequires(HgRepository hgRepo, File requiresFile) throws HgInvalidControlFileException {
		try {
			new RequiresFile().parse(this, requiresFile);
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Parse failed", ex, requiresFile);
		}
	}

	public/*for tests, otherwise pkg*/ void setStorageConfig(int version, int flags) {
		requiresFlags = flags;
	}
	
	public PathRewrite buildNormalizePathRewrite() {
		if (runningOnWindows()) {
			return new WinToNixPathRewrite();
		} else {
			return new PathRewrite.Empty(); // or strip leading slash, perhaps? 
		}
	}

	// XXX perhaps, should keep both fields right here, not in the HgRepository
	public PathRewrite buildDataFilesHelper() {
		// Note, tests in TestStorePath depend on the encoding not being cached
		Charset cs = getFileEncoding();
		// StoragePathHelper needs fine-grained control over char encoding, hence doesn't use EncodingHelper
		return new StoragePathHelper((requiresFlags & STORE) != 0, (requiresFlags & FNCACHE) != 0, (requiresFlags & DOTENCODE) != 0, cs);
	}

	public PathRewrite buildRepositoryFilesHelper() {
		if ((requiresFlags & STORE) != 0) {
			return new PathRewrite() {
				public CharSequence rewrite(CharSequence path) {
					return "store/" + path;
				}
			};
		} else {
			return new PathRewrite.Empty();
		}
	}

	public List<Filter.Factory> getFilters(HgRepository hgRepo) {
		if (filterFactories == null) {
			filterFactories = new ArrayList<Filter.Factory>();
			ExtensionsSection cfg = hgRepo.getConfiguration().getExtensions();
			if (cfg.isEnabled("eol")) {
				NewlineFilter.Factory ff = new NewlineFilter.Factory();
				ff.initialize(hgRepo);
				filterFactories.add(ff);
			}
			if (cfg.isEnabled("keyword")) {
				KeywordFilter.Factory ff = new KeywordFilter.Factory();
				ff.initialize(hgRepo);
				filterFactories.add(ff);
			}
		}
		return filterFactories;
	}
	
	public void initEmptyRepository(File hgDir) throws IOException {
		hgDir.mkdir();
		FileOutputStream requiresFile = new FileOutputStream(new File(hgDir, "requires"));
		StringBuilder sb = new StringBuilder(40);
		sb.append("revlogv1\n");
		if ((requiresFlags & STORE) != 0) {
			sb.append("store\n");
		}
		if ((requiresFlags & FNCACHE) != 0) {
			sb.append("fncache\n");
		}
		if ((requiresFlags & DOTENCODE) != 0) {
			sb.append("dotencode\n");
		}
		requiresFile.write(sb.toString().getBytes());
		requiresFile.close();
		new File(hgDir, "store").mkdir(); // with that, hg verify says ok.
	}
	
	public boolean isCaseSensitiveFileSystem() {
		return isCaseSensitiveFileSystem;
	}
	
	public EncodingHelper buildFileNameEncodingHelper() {
		return new EncodingHelper(getFileEncoding(), sessionContext);
	}
	
	private Charset getFileEncoding() {
		Object altEncoding = sessionContext.getConfigurationProperty(CFG_PROPERTY_FS_FILENAME_ENCODING, null);
		Charset cs;
		if (altEncoding == null) {
			cs = Charset.defaultCharset();
		} else {
			try {
				cs = Charset.forName(altEncoding.toString());
			} catch (IllegalArgumentException ex) {
				// both IllegalCharsetNameException and UnsupportedCharsetException are subclasses of IAE, too
				// not severe enough to throw an exception, imo. Just record the fact it's bad ad we ignore it 
				sessionContext.getLog().dump(Internals.class, Error, ex, String.format("Bad configuration value for filename encoding %s", altEncoding));
				cs = Charset.defaultCharset();
			}
		}
		return cs;
	}

	public static boolean runningOnWindows() {
		return System.getProperty("os.name").indexOf("Windows") != -1;
	}
	
	/**
	 * @param fsHint optional hint pointing to filesystem of interest (generally, it's possible to mount 
	 * filesystems with different capabilities and repository's capabilities would depend on which fs it resides) 
	 * @return <code>true</code> if executable files deserve tailored handling 
	 */
	public static boolean checkSupportsExecutables(File fsHint) {
		// *.exe are not executables for Mercurial
		return !runningOnWindows();
	}

	/**
	 * @param fsHint optional hint pointing to filesystem of interest (generally, it's possible to mount 
	 * filesystems with different capabilities and repository's capabilities would depend on which fs it resides) 
	 * @return <code>true</code> if filesystem knows what symbolic links are 
	 */
	public static boolean checkSupportsSymlinks(File fsHint) {
		// Windows supports soft symbolic links starting from Vista 
		// However, as of Mercurial 2.1.1, no support for this functionality
		// XXX perhaps, makes sense to override with a property a) to speed up when no links are in use b) investigate how this runs windows
		return !runningOnWindows();
	}

	
	/**
	 * For Unix, returns installation root, which is the parent directory of the hg executable (or symlink) being run.
	 * For Windows, it's Mercurial installation directory itself 
	 * @param ctx 
	 */
	private static File findHgInstallRoot(SessionContext ctx) {
		// let clients to override Hg install location 
		String p = (String) ctx.getConfigurationProperty(CFG_PROPERTY_HG_INSTALL_ROOT, null);
		if (p != null) {
			return new File(p);
		}
		StringTokenizer st = new StringTokenizer(System.getenv("PATH"), System.getProperty("path.separator"), false);
		final boolean runsOnWin = runningOnWindows();
		while (st.hasMoreTokens()) {
			String pe = st.nextToken();
			File execCandidate = new File(pe, runsOnWin ? "hg.exe" : "hg");
			if (execCandidate.exists() && execCandidate.isFile()) {
				File execDir = execCandidate.getParentFile();
				// e.g. on Unix runs "/shared/tools/bin/hg", directory of interest is "/shared/tools/" 
				return runsOnWin ? execDir : execDir.getParentFile();
			}
		}
		return null;
	}
	
	/**
	 * @see http://www.selenic.com/mercurial/hgrc.5.html
	 */
	public ConfigFile readConfiguration(HgRepository hgRepo, File repoRoot) throws IOException {
		// XXX Internals now have sessionContext field, is there real need to extract one from the repo?
		SessionContext sessionCtx = HgInternals.getContext(hgRepo);
		ConfigFile configFile = new ConfigFile(sessionCtx);
		File hgInstallRoot = findHgInstallRoot(sessionCtx); // may be null
		//
		if (runningOnWindows()) {
			if (hgInstallRoot != null) {
				for (File f : getWindowsConfigFilesPerInstall(hgInstallRoot)) {
					configFile.addLocation(f);
				}
			}
			LinkedHashSet<String> locations = new LinkedHashSet<String>();
			locations.add(System.getenv("USERPROFILE"));
			locations.add(System.getenv("HOME"));
			locations.remove(null);
			for (String loc : locations) {
				File location = new File(loc);
				configFile.addLocation(new File(location, "Mercurial.ini"));
				configFile.addLocation(new File(location, ".hgrc"));
			}
		} else {
			if (hgInstallRoot != null) {
				File d = new File(hgInstallRoot, "etc/mercurial/hgrc.d/");
				if (d.isDirectory() && d.canRead()) {
					for (File f : listConfigFiles(d)) {
						configFile.addLocation(f);
					}
				}
				configFile.addLocation(new File(hgInstallRoot, "etc/mercurial/hgrc"));
			}
			// same, but with absolute paths
			File d = new File("/etc/mercurial/hgrc.d/");
			if (d.isDirectory() && d.canRead()) {
				for (File f : listConfigFiles(d)) {
					configFile.addLocation(f);
				}
			}
			configFile.addLocation(new File("/etc/mercurial/hgrc"));
			configFile.addLocation(new File(System.getenv("HOME"), ".hgrc"));
		}
		// last one, overrides anything else
		// <repo>/.hg/hgrc
		configFile.addLocation(new File(repoRoot, "hgrc"));
		return configFile;
	}
	
	private static List<File> getWindowsConfigFilesPerInstall(File hgInstallDir) {
		File f = new File(hgInstallDir, "Mercurial.ini");
		if (f.exists()) {
			return Collections.singletonList(f);
		}
		f = new File(hgInstallDir, "hgrc.d/");
		if (f.canRead() && f.isDirectory()) {
			return listConfigFiles(f);
		}
		// TODO post-1.0 query registry, e.g. with
		// Runtime.exec("reg query HKLM\Software\Mercurial")
		//
		f = new File("C:\\Mercurial\\Mercurial.ini");
		if (f.exists()) {
			return Collections.singletonList(f);
		}
		return Collections.emptyList();
	}
	
	private static List<File> listConfigFiles(File dir) {
		assert dir.canRead();
		assert dir.isDirectory();
		final File[] allFiles = dir.listFiles();
		// File is Comparable, lexicographically by default
		Arrays.sort(allFiles);
		ArrayList<File> rv = new ArrayList<File>(allFiles.length);
		for (File f : allFiles) {
			if (f.getName().endsWith(".rc")) {
				rv.add(f);
			}
		}
		return rv;
	}
	
	public static File getInstallationConfigurationFileToWrite(SessionContext ctx) {
		File hgInstallRoot = findHgInstallRoot(ctx); // may be null
		// choice of which hgrc to pick here is according to my own pure discretion
		if (hgInstallRoot != null) {
			// use this location only if it's writable
			File cfg = new File(hgInstallRoot, runningOnWindows() ? "Mercurial.ini" : "etc/mercurial/hgrc");
			if (cfg.canWrite() || cfg.getParentFile().canWrite()) {
				return cfg;
			}
		}
		// fallback
		if (runningOnWindows()) {
			if (hgInstallRoot == null) {
				return new File("C:\\Mercurial\\Mercurial.ini");
			} else {
				// yes, we tried this file already (above) and found it non-writable
				// let caller fail with can't write
				return new File(hgInstallRoot, "Mercurial.ini");
			}
		} else {
			return new File("/etc/mercurial/hgrc");
		}
	}

	public static File getUserConfigurationFileToWrite(SessionContext ctx) {
		LinkedHashSet<String> locations = new LinkedHashSet<String>();
		final boolean runsOnWindows = runningOnWindows();
		if (runsOnWindows) {
			locations.add(System.getenv("USERPROFILE"));
		}
		locations.add(System.getenv("HOME"));
		locations.remove(null);
		for (String loc : locations) {
			File location = new File(loc);
			File rv = new File(location, ".hgrc");
			if (rv.exists() && rv.canWrite()) {
				return rv;
			}
			if (runsOnWindows) {
				rv = new File(location, "Mercurial.ini");
				if (rv.exists() && rv.canWrite()) {
					return rv;
				}
			}
		}
		// fallback to default, let calling code fail with Exception if can't write
		return new File(System.getProperty("user.home"), ".hgrc");
	}

	public boolean shallCacheRevlogs() {
		return shallCacheRevlogsInRepo;
	}
	
	public static <T> CharSequence join(Iterable<T> col, CharSequence separator) {
		if (col == null) {
			return String.valueOf(col);
		}
		Iterator<T> it = col.iterator();
		if (!it.hasNext()) {
			return "[]";
		}
		String v = String.valueOf(it.next());
		StringBuilder sb = new StringBuilder(v);
		while (it.hasNext()) {
			sb.append(separator);
			v = String.valueOf(it.next());
			sb.append(v);
		}
		return sb;
	}
	
	/**
	 * keep an eye on all long to int downcasts to get a chance notice the lost of data
	 * Use if there's even subtle chance there might be loss
	 * (ok not to use if there's no way for l to be greater than int) 
	 */
	public static int ltoi(long l) {
		int i = (int) l;
		assert ((long) i) == l : "Loss of data!";
		return i;
	}
}
