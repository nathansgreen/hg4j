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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;

import org.tmatesoft.hg.repo.HgInvalidFileException;
import org.tmatesoft.hg.repo.ext.MqManager;
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
		private boolean trimLines = true;
		private boolean skipEmpty = true;
		private String ignoreThatStarts = null;

		public LineReader(File f, LogFacility logFacility) {
			file = f;
			log = logFacility;
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

		public <T> void read(LineConsumer<T> consumer, T paramObj) throws HgInvalidFileException {
			BufferedReader statusFileReader = null;
			try {
//				consumer.begin(file, paramObj);
				statusFileReader = new BufferedReader(new FileReader(file));
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
			} catch (IOException ex) {
				throw new HgInvalidFileException(ex.getMessage(), ex, file);
			} finally {
				if (statusFileReader != null) {
					try {
						statusFileReader.close();
					} catch (IOException ex) {
						log.dump(MqManager.class, Warn, ex, null);
					}
				}
//				try {
//					consumer.end(file, paramObj);
//				} catch (IOException ex) {
//					log.warn(MqManager.class, ex, null);
//				}
			}
		}
	}