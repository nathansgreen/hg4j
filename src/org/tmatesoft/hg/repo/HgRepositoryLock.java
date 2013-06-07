/*
 * Copyright (c) 2012 TMate Software Ltd
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.core.HgRepositoryLockException;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Internals;

/**
 * NOT SAFE FOR MULTITHREAD USE!
 * 
 * <p>Usage:
 * <pre>
 * HgRepositoryLock lock = hgRepo.getWorkingDirLock();
 * try {
 *     // Actually lock the repo
 *     lock.acquire();
 *     ///
 *     // do whatever modifies working directory
 *     ...
 * } finally {
 *     if (lock.isLocked()) {
 *         // this check is needed not to release() 
 *         // erroneously in case acquire() failed (e.g. due to timeout)
 *         lock.release();
 *     }
 * 
 * </pre>
 * 
 * Unlike original mechanism, we don't use symlinks, rather files, as it's easier to implement
 * 
 * @see http://code.google.com/p/hg4j/issues/detail?id=35
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public class HgRepositoryLock {
	/*
	 * Lock .hg/ except .hg/store/      .hg/wlock (new File(hgRepo.getRepoRoot(),"wlock"))
	 * Lock .hg/store/                  .hg/store/lock (HgRepository.repoPathHelper("lock"))
	 */

	private final File lockFile;
	private int use = 0;
	private final int timeoutSeconds;
	
	 HgRepositoryLock(File lock, int timeoutInSeconds) {
		lockFile = lock;
		timeoutSeconds = timeoutInSeconds;
	}

	/**
	 * Tries to read lock file and supplies hostname:pid (or just pid) information from there
	 * @return <code>null</code> if no lock file available at the moment
	 */
	public String readLockInfo() {
		if (lockFile.exists()) {
			try {
				byte[] bytes = read(lockFile);
				if (bytes != null && bytes.length > 0) {
					return new String(bytes);
				}
			} catch (Exception ex) {
				// deliberately ignored
			}
		}
		return null;
	}
	
	/**
	 * @return <code>true</code> if we hold the lock
	 */
	public boolean isLocked() {
		return use > 0;
	}

	/**
	 * Perform actual locking. Waits for timeout (if specified at construction time)
	 * before throwing {@link HgInvalidStateException} in case lock is not available 
	 * immediately.
	 * 
	 * <p>Multiple calls are possible, but corresponding number of {@link #release()} 
	 * calls shall be made.
	 * @throws HgRepositoryLockException if failed to grab a lock
	 */
	public void acquire() throws HgRepositoryLockException {
		if (use > 0) {
			use++;
			return;
		}
		StringBuilder lockDescription = new StringBuilder();
		lockDescription.append(getHostname());
		lockDescription.append(':');
		lockDescription.append(getPid());
		byte[] bytes = lockDescription.toString().getBytes();
		long stopTime = timeoutSeconds < 0 ? -1 : (System.currentTimeMillis() + timeoutSeconds*1000);
		do {
			synchronized(this) {
				try {
					if (lockFile.createNewFile()) {
						write(lockFile, bytes);
						use++;
						return;
					}
				} catch (IOException ex) {
					// deliberately ignored
				}
				try {
					wait(1000);
				} catch (InterruptedException ex) {
					// deliberately ignored
				}
			}
			
		} while (stopTime == -1/*no timeout*/ || System.currentTimeMillis() <= stopTime);
		String msg = String.format("Failed to aquire lock, waited for %d seconds, present owner: '%s'", timeoutSeconds, readLockInfo());
		throw new HgRepositoryLockException(msg);
	}
	
	/**
	 * Release lock we own
	 * @throws HgRepositoryLockException if there's no evidence we do own a lock
	 */
	public void release() throws HgRepositoryLockException {
		if (use == 0) {
			throw new HgRepositoryLockException("Lock is not held!");
		}
		use--;
		if (use > 0) {
			return;
		}
		// do release
		lockFile.delete();
	}

	protected String getHostname() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception ex) {
			return "localhost";
		}
	}

	protected int getPid() {
		try {
			// @see http://blog.igorminar.com/2007/03/how-java-application-can-discover-its.html
			if (!Internals.runningOnWindows()) {
				File f = new File("/proc/self");
				if (f.exists()) {
					// /proc/self is a symlink to /proc/pid/ directory
					return Integer.parseInt(f.getCanonicalFile().getName());
				}
			}
			String rtBean = ManagementFactory.getRuntimeMXBean().getName();
			int x;
			if ((x = rtBean.indexOf('@')) != -1) {
				return Integer.parseInt(rtBean.substring(0, x));
			}
			return -1;
		} catch (Exception ex) {
			return -1;
		}
	}

	private static void write(File f, byte[] content) throws IOException {
		FileOutputStream fos = new FileOutputStream(f);
		fos.write(content);
		fos.close();
	}

	private static byte[] read(File f) throws IOException {
		FileChannel fc = new FileInputStream(f).getChannel();
		ByteBuffer bb = ByteBuffer.allocate(Internals.ltoi(fc.size()));
		fc.read(bb);
		fc.close();
		return bb.array();
	}
}
