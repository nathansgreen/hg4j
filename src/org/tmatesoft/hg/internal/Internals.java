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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.StringTokenizer;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRepoConfig.ExtensionsSection;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryFiles;
import org.tmatesoft.hg.repo.HgRepositoryLock;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Fields/members that shall not be visible  
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Internals implements SessionContext.Source {
	
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
	
	/**
	 * Timeout, in seconds, to acquire filesystem {@link HgRepositoryLock lock}.
	 * 
	 * Mercurial provides 'ui.timeout' in hgrc (defaults to 600 seconds) to specify how long 
	 * it shall try to acquire a lock for storage or working directory prior to fail.
	 *  
	 * This configuration property allows to override timeout value from Mercurial's configuration
	 * file and use Hg4J-specific value instead. 
	 * 
	 * Integer value, use negative for attempts to acquire lock until success, and zero to try once and fail immediately. 
	 */
	public static final String CFG_PROPERTY_FS_LOCK_TIMEOUT = "hg4j.fs.lock.timeout";
	
	/**
	 * Alternative, more effective approach to build revision text from revlog patches - collect all the
	 * patches one by one, starting at revision next to base, and apply against each other to get 
	 * one final patch, which in turned is applied to base revision. 
	 * <p>
	 * Original approach is to apply each patch to a previous revision, so that with base revision 
	 * of 1M and three patches, each altering just a tiny fraction
	 * of the origin, with latter approach we consume 1M (original) + 1M (first patch applied) + 1M (second
	 * patch applied) + 1M (third patch applied).
	 * <p>
	 * Alternative approach, controlled with this option, first combines these there patches into one,
	 * and only then applies it to base revision, eliminating 2 intermediate elements.
	 * <p>
	 * Since 1.2, default value for this option is <em>TRUE</em>, (was <code>false</code> in <b>Hg4J 1.1</b>)
	 * 
	 * @since 1.1
	 */
	public static final String CFG_PROPERTY_PATCH_MERGE = "hg4j.repo.merge_revlog_patches";
	
	/**
	 * Phases were introduced in Mercurial 2.1. Unless there's <code>phaseroots</code> file in the 
	 * repository's storage area, <b>Hg4J</b> pretends phases are not enabled and doesn't update
	 * phase information on commit/push/pull. If, however, it's desired to keep phase information,
	 * this option may be set to <code>true</code>, and <code>phaseroots</code> file gets updated
	 * along with repository changes.
	 * 
	 * <p>Default value: <code>false</code>
	 * @since 1.2
	 */
	public static final String CFG_PROPERTY_CREATE_PHASEROOTS = "hg4j.repo.create_phaseroots";

	public static final int REVLOGV1_RECORD_SIZE = 64;

	private List<Filter.Factory> filterFactories;
	private final HgRepository repo;
	private final File repoDir;
	private final boolean isCaseSensitiveFileSystem;
	private final DataAccessProvider dataAccess;
	private final ImplAccess implAccess;
	
	private final int requiresFlags;

	private final PathRewrite dataPathHelper; // access to file storage area (usually under .hg/store/data/), with filenames mangled  
	private final PathRewrite repoPathHelper; // access to system files (under .hg/store if requires has 'store' flag)

	private final boolean shallMergePatches;
	private final boolean shallWritePhaseroots;
	private final RevlogStreamFactory streamProvider;

	public Internals(HgRepository hgRepo, File hgDir, ImplAccess implementationAccess) throws HgRuntimeException {
		repo = hgRepo;
		repoDir = hgDir;
		implAccess = implementationAccess;
		isCaseSensitiveFileSystem = !runningOnWindows();
		SessionContext ctx = repo.getSessionContext();
		dataAccess = new DataAccessProvider(ctx);
		RepoInitializer repoInit = new RepoInitializer().initRequiresFromFile(repoDir);
		requiresFlags = repoInit.getRequires();
		dataPathHelper = repoInit.buildDataFilesHelper(getSessionContext());
		repoPathHelper = repoInit.buildStoreFilesHelper();
		final PropertyMarshal pm = new PropertyMarshal(ctx);
		boolean shallCacheRevlogsInRepo = pm.getBoolean(CFG_PROPERTY_REVLOG_STREAM_CACHE, true);
		streamProvider = new RevlogStreamFactory(this, shallCacheRevlogsInRepo); 
		shallMergePatches = pm.getBoolean(Internals.CFG_PROPERTY_PATCH_MERGE, true);
		shallWritePhaseroots = pm.getBoolean(Internals.CFG_PROPERTY_CREATE_PHASEROOTS, false);
	}
	
	public boolean isInvalid() {
		return !repoDir.exists() || !repoDir.isDirectory();
	}
	
	public File getRepositoryFile(HgRepositoryFiles f) {
		switch (f.getHome()) {
			case Store : return getFileFromStoreDir(f.getName());
			case Repo : return getFileFromRepoDir(f.getName());
			default : return new File(repo.getWorkingDir(), f.getName());
		}
	}

	/**
	 * Access files under ".hg/".
	 * File not necessarily exists, this method is merely a factory for {@link File files} at specific, configuration-dependent location. 
	 * 
	 * @param name shall be normalized path
	 */
	public File getFileFromRepoDir(String name) {
		return new File(repoDir, name);
	}

	/**
	 * Access files under ".hg/store/" or ".hg/" depending on use of 'store' in requires.
	 * File not necessarily exists, this method is merely a factory for Files at specific, configuration-dependent location.
	 *  
	 * @param name shall be normalized path
	 */
	public File getFileFromStoreDir(String name) {
		CharSequence location = repoPathHelper.rewrite(name);
		return new File(repoDir, location.toString());
	}
	
	/**
	 * Access files under ".hg/store/data", ".hg/store/dh/" or ".hg/data" according to settings in requires file.
	 * File not necessarily exists, this method is merely a factory for Files at specific, configuration-dependent location.
	 * 
	 * @param name shall be normalized path, without .i or .d suffixes
	 */
	public File getFileFromDataDir(CharSequence path) {
		CharSequence storagePath = dataPathHelper.rewrite(path);
		return new File(repoDir, storagePath.toString());
	}
	
	public SessionContext getSessionContext() {
		return repo.getSessionContext();
	}
	
	public LogFacility getLog() {
		return getSessionContext().getLog();
	}
	
	public HgRepository getRepo() {
		return repo;
	}
	
	public DataAccessProvider getDataAccess() {
		return dataAccess;
	}

	public PathRewrite buildNormalizePathRewrite() {
		if (runningOnWindows()) {
			return new WinToNixPathRewrite();
		} else {
			return new PathRewrite.Empty(); // or strip leading slash, perhaps? 
		}
	}

	public List<Filter.Factory> getFilters() {
		if (filterFactories == null) {
			filterFactories = new ArrayList<Filter.Factory>();
			ExtensionsSection cfg = repo.getConfiguration().getExtensions();
			if (cfg.isEnabled("eol")) {
				NewlineFilter.Factory ff = new NewlineFilter.Factory();
				ff.initialize(repo);
				filterFactories.add(ff);
			}
			if (cfg.isEnabled("keyword")) {
				KeywordFilter.Factory ff = new KeywordFilter.Factory();
				ff.initialize(repo);
				filterFactories.add(ff);
			}
		}
		return filterFactories;
	}
	
	public boolean isCaseSensitiveFileSystem() {
		return isCaseSensitiveFileSystem;
	}

	public boolean fncacheInUse() {
		return (getRequiresFlags() & RequiresFile.FNCACHE) != 0;
	}

	public EncodingHelper buildFileNameEncodingHelper() {
		return new EncodingHelper(getFileEncoding(getSessionContext()), repo);
	}
	
	public static EncodingHelper buildFileNameEncodingHelper(SessionContext.Source ctx) {
		return new EncodingHelper(getFileEncoding(ctx.getSessionContext()), ctx);
	}
	/*package-local*/ static Charset getFileEncoding(SessionContext ctx) {
		Object altEncoding = ctx.getConfigurationProperty(CFG_PROPERTY_FS_FILENAME_ENCODING, null);
		Charset cs;
		if (altEncoding == null) {
			cs = Charset.defaultCharset();
		} else {
			try {
				cs = Charset.forName(altEncoding.toString());
			} catch (IllegalArgumentException ex) {
				// both IllegalCharsetNameException and UnsupportedCharsetException are subclasses of IAE, too
				// not severe enough to throw an exception, imo. Just record the fact it's bad ad we ignore it 
				ctx.getLog().dump(Internals.class, Error, ex, String.format("Bad configuration value for filename encoding %s", altEncoding));
				cs = Charset.defaultCharset();
			}
		}
		return cs;
	}
	
	/**
	 * Access to mangled name of a file in repository storage, may come handy for debug.
	 * @return mangled path of the repository file
	 */
	public CharSequence getStoragePath(HgDataFile df) {
		return dataPathHelper.rewrite(df.getPath().toString());
	}

	public int getRequiresFlags() {
		return requiresFlags;
	}
	
	boolean shallMergePatches() {
		return shallMergePatches;
	}
	
	boolean shallCreatePhaseroots() {
		return shallWritePhaseroots;
	}

	RevlogChangeMonitor getRevlogTracker(File f) {
		// TODO decide whether to use one monitor per multiple files or 
		// an instance per file; and let SessionContext pass alternative implementation)
		return new RevlogChangeMonitor(f);
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
	 * User-specific configuration, from system-wide and user home locations, without any repository-specific data.
	 * @see http://www.selenic.com/mercurial/hgrc.5.html
	 */
	public static ConfigFile readConfiguration(SessionContext sessionCtx) throws HgIOException {
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
		return configFile;
	}

	/**
	 * Repository-specific configuration
	 * @see http://www.selenic.com/mercurial/hgrc.5.html
	 */
	public ConfigFile readConfiguration() throws HgIOException {
		ConfigFile configFile = readConfiguration(repo.getSessionContext());
		// last one, overrides anything else
		// <repo>/.hg/hgrc
		configFile.addLocation(getRepositoryFile(HgRepositoryFiles.RepoConfig));
		return configFile;
	}

	/*package-local*/ImplAccess getImplAccess() {
		return implAccess;
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
		// TODO [post-1.1] query registry, e.g. with
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
	
	public RevlogStream createManifestStream() {
		File manifestFile = getFileFromStoreDir("00manifest.i");
		return streamProvider.create(manifestFile);
	}

	public RevlogStream createChangelogStream() {
		File chlogFile = getFileFromStoreDir("00changelog.i");
		return streamProvider.create(chlogFile);
	}

	public RevlogStream resolveStoreFile(Path path) {
		return streamProvider.getStoreFile(path, false);
	}
	
	public Transaction.Factory getTransactionFactory() {
		return new COWTransaction.Factory();
	}

	// marker method
	public static IllegalStateException notImplemented() {
		return new IllegalStateException("Not implemented");
	}

	public static Internals getInstance(HgRepository repo) {
		return HgInternals.getImplementationRepo(repo);
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

	// access implementation details (fields, methods) of oth.repo package
	public interface ImplAccess {
		public RevlogStream getStream(HgDataFile df);
		public RevlogStream getManifestStream();
		public RevlogStream getChangelogStream();
	}
}
