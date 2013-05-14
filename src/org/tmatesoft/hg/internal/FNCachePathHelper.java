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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.internal.StoragePathHelper.STR_DATA;

import org.tmatesoft.hg.util.PathRewrite;

/**
 * Prepare filelog names to be written into fncache. 
 * 
 * @see http://mercurial.selenic.com/wiki/fncacheRepoFormat#The_fncache_file
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
final class FNCachePathHelper implements PathRewrite {

	private final EncodeDirPathHelper dirPathRewrite;

	
	public FNCachePathHelper() {
		dirPathRewrite = new EncodeDirPathHelper();
	}

	/**
	 * Input: repository-relative path of a filelog, i.e. without 'data/' or 'dh/' prefix, and/or '.i'/'.d' suffix.
	 * Output: path ready to be written into fncache file, alaways with '.i' suffix (caller is free to alter the suffix to '.d' as appropriate
	 */
	public CharSequence rewrite(CharSequence path) {
		CharSequence p = dirPathRewrite.rewrite(path);
		StringBuilder result = new StringBuilder(p.length() + STR_DATA.length() + ".i".length());
		result.append(STR_DATA);
		result.append(p);
		result.append(".i");
		return result;
	}

	/*
	 * There's always 'data/' prefix, even if actual file resides under 'dh/':
	 *  
	 * $ cat .hg/store/fncache
	 * data/very-long-directory-name-level-1/very-long-directory-name-level-2/very-long-directory-name-level-3/file-with-longest-name-i-am-not-lazy-to-type.txt.i
	 * $ ls .hg/store/dh/very-lon/very-lon/very-lon/
	 * file-with-longest-name-i-am-not-lazy-to-type.txtbbd4d3327f6364027211b0cd8ca499d3d6308849.i
	 */
}
