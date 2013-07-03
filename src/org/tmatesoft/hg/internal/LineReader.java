/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Collection;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.util.LogFacility;

/**
 * Handy class to read line-based configuration files
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class LineReader {
		
		public interface LineConsumer<T> {
	//		boolean begin(File f, T paramObj) throws IOException;
			boolean consume(String line, T paramObj) throws IOException;
	//		boolean end(File f, T paramObj) throws IOException;
		}

		public static class SimpleLineCollector implements LineReader.LineConsumer<Collection<String>> {
		
			public boolean consume(String line, Collection<String> result) throws IOException {
				result.add(line);
				return true;
			}
		}

		private final File file;
		private final LogFacility log;
		private final Charset encoding;
		private boolean trimLines = true;
		private boolean skipEmpty = true;
		private String ignoreThatStarts = null;

		public LineReader(File f, LogFacility logFacility) {
			this(f, logFacility, null);
		}

		public LineReader(File f, LogFacility logFacility, Charset lineEncoding) {
			file = f;
			log = logFacility;
			encoding = lineEncoding;
		}
		
		/**
		 * default: <code>true</code>
		 * <code>false</code> to return line as is
		 */
		public LineReader trimLines(boolean trim) {
			trimLines = trim;
			return this;
		}
		
		/**
		 * default: <code>true</code>
		 * <code>false</code> to pass empty lines to consumer
		 */
		public LineReader skipEmpty(boolean skip) {
			skipEmpty = skip;
			return this;
		}
		
		/**
		 * default: doesn't skip any line.
		 * set e.g. to "#" or "//" to skip lines that start with such prefix
		 */
		public LineReader ignoreLineComments(String lineStart) {
			ignoreThatStarts = lineStart;
			return this;
		}

		/**
		 * 
		 * @param consumer where to pipe read lines to
		 * @param paramObj parameterizes consumer
		 * @return paramObj value for convenience
		 * @throws HgIOException if there's {@link IOException} while reading file
		 */
		public <T> T read(LineConsumer<T> consumer, T paramObj) throws HgIOException {
			BufferedReader statusFileReader = null;
			try {
//				consumer.begin(file, paramObj);
				Reader fileReader;
				if (encoding == null) {
					fileReader = new FileReader(file);
				} else {
					fileReader = new InputStreamReader(new FileInputStream(file), encoding);
				}
				statusFileReader = new BufferedReader(fileReader);
				String line;
				boolean ok = true;
				while (ok && (line = statusFileReader.readLine()) != null) {
					if (trimLines) {
						line = line.trim();
					}
					if (ignoreThatStarts != null && line.startsWith(ignoreThatStarts)) {
						continue;
					}
					if (!skipEmpty || line.length() > 0) {
						ok = consumer.consume(line, paramObj);
					}
				}
				return paramObj;
			} catch (IOException ex) {
				throw new HgIOException(ex.getMessage(), ex, file);
			} finally {
				new FileUtils(log, this).closeQuietly(statusFileReader);
//				try {
//					consumer.end(file, paramObj);
//				} catch (IOException ex) {
//					log.warn(getClass(), ex, null);
//				}
			}
		}
	}