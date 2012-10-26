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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Simplistic parser to allow altering configuration files without touching user modifications/formatting/comments
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ConfigFileParser {
	private enum ParseState {Initial, Section, Entry};
	private ParseState state = ParseState.Initial; 
	private int lastNonEmptyLineEndOffset = -1;
	private String sectionName;
	private int sectionStart = -1;
	private String entryKey;
	private int entryStart = -1;
	private int valueStart = -1, valueEnd = -1;
	private ArrayList<Entry> entries;
	private ArrayList<Section> sections = new ArrayList<Section>();
	private byte[] contents;
	
	private List<String> deletions = new ArrayList<String>(5);
	private List<String> additions = new ArrayList<String>(5), changes = new ArrayList<String>(5);

	
	public boolean exists(String section, String key) {
		assert contents != null;
		for (Section s : sections) {
			if (s.name.equals(section)) {
				for (Entry e : s.entries) {
					if (e.name.equals(key)) {
						return true;
					}
				}
				return false;
			}
		}
		return false;
	}
	
	public void add(String section, String key, String newValue) {
		additions.add(section);
		additions.add(key);
		additions.add(newValue);
	}
	
	public void change(String section, String key, String newValue) {
		changes.add(section);
		changes.add(key);
		changes.add(newValue);
	}
	
	public void delete(String section, String key) {
		deletions.add(section);
		deletions.add(key);
	}

	public void parse(InputStream is) throws IOException {
		state = ParseState.Initial;
		sections.clear();
		contents = null;
		ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
		ByteArrayOutputStream line = new ByteArrayOutputStream(80);
		int offset = 0;
		int lineOffset = -1;
		int lineNumber = 1;
		boolean crDetected = false; // true when previous char was \r
		int b;
		while ( (b = is.read()) != -1) {
			bos.write(b);
			if (b == '\n' || b == '\r') {
				if (line.size() > 0) {
					processLine(lineNumber, lineOffset, line.toByteArray());
					line.reset();
					lineOffset = -1;
					lastNonEmptyLineEndOffset = bos.size() - 1; // offset points to EOL char
				} 
				// else: XXX does empty line closes entry???
				// when \n follows \r, increment line count only once
				if (!(b == '\n' && crDetected)) {
					lineNumber++;
				}
				crDetected = b == '\r';
			} else {
				crDetected = false;
				if (line.size() == 0) {
					lineOffset = offset;
				}
				line.write(b);
			}
			offset++;
		}
		// handle last line in case it's not EOL-terminated
		if (line.size() > 0) {
			processLine(lineNumber, lineOffset, line.toByteArray());
			// might need it for #closeSection() below
			lastNonEmptyLineEndOffset = bos.size();
		}
		if (state == ParseState.Entry) {
			closeEntry();
		}
		if (state == ParseState.Section) {
			closeSection();
		}
		contents = bos.toByteArray();
	}
	
	public void update(OutputStream out) throws IOException {
		if (contents == null) {
			throw new IOException("Shall parse first");
		}
		HashSet<String> processedSections = new HashSet<String>();
		int contentsOffset = 0;
		for (Section section : sections) {
			LinkedHashMap<String,String> additionsInSection = new LinkedHashMap<String,String>();
			LinkedHashMap<String,String> changesInSection = new LinkedHashMap<String,String>();
			LinkedHashSet<String> deletionsInSection = new LinkedHashSet<String>();
			if (!processedSections.contains(section.name)) {
				for (Iterator<String> it = additions.iterator(); it.hasNext();) {
					String s = it.next(), k = it.next(), v = it.next();
					if (section.name.equals(s)) {
						additionsInSection.put(k, v);
					}
				}
				for (Iterator<String> it = changes.iterator(); it.hasNext();) {
					String s = it.next(), k = it.next(), v = it.next();
					if (section.name.equals(s)) {
						changesInSection.put(k, v);
					}
				}
				for (Iterator<String> it = deletions.iterator(); it.hasNext();) {
					String s = it.next(), k = it.next();
					if (section.name.equals(s)) {
						deletionsInSection.add(k);
					}
				}
			}
			for (Entry e : section.entries) {
				if (deletionsInSection.contains(e.name)) {
					// write up to key start only
					out.write(contents, contentsOffset, e.start - contentsOffset);
					contentsOffset = e.valueEnd + 1;
				} else if (changesInSection.containsKey(e.name)) {
					if (e.valueStart == -1) {
						// e.valueEnd determines insertion point
						out.write(contents, contentsOffset, e.valueEnd + 1 - contentsOffset);
					} else {
						// e.valueEnd points to last character of the value 
						out.write(contents, contentsOffset, e.valueStart - contentsOffset);
					}
					String value = changesInSection.get(e.name);
					out.write(value == null ? new byte[0] : value.getBytes());
					contentsOffset = e.valueEnd + 1;
				}
				// else: keep contentsOffset to point to first uncopied character
			}
			if (section.entries.length == 0) {
				// no entries, empty or only comments, perhaps.
				// use end of last meaningful line (whether [section] or comment string),
				// which points to newline character
				out.write(contents, contentsOffset, section.end - contentsOffset);
				contentsOffset = section.end;
				// since it's tricky to track \n or \r\n with lastNonEmptyLineEndOffset,
				// we copy up to the line delimiter and insert new lines, if any, with \n prepended,
				// so that original EOL will be moved to the very end of the section.
				// Indeed, would be better to insert *after* lastNonEmptyLineEndOffset,
				// but I don't want to complicate #parse (if line.size() > 0 part) method.
				// Hope, this won't make too much trouble (if any, at all - 
				// if String.format translates \n to system EOL, then nobody would notice)
			}
			if (!additionsInSection.isEmpty()) {
				// make sure additions are written once everything else is there
				out.write(contents, contentsOffset, section.end - contentsOffset);
				contentsOffset = section.end;
				for (String k : additionsInSection.keySet()) {
					String v = additionsInSection.get(k);
					out.write(String.format("\n%s = %s", k, v == null ? "" : v).getBytes());
				}
			}
			// if section comes more than once, update only first one.
			processedSections.add(section.name);
		}
		// push rest of the contents
		out.write(contents, contentsOffset, contents.length - contentsOffset);
		//
		// add entries in new sections
		LinkedHashSet<String> newSections = new LinkedHashSet<String>();
		for (Iterator<String> it = additions.iterator(); it.hasNext();) {
			String s = it.next(); it.next(); it.next();
			if (!processedSections.contains(s)) {
				newSections.add(s);
			}
		}
		for (String newSectionName : newSections) {
			out.write(String.format("\n[%s]", newSectionName).getBytes());
			for (Iterator<String> it = additions.iterator(); it.hasNext();) {
				String s = it.next(), k = it.next(), v = it.next();
				if (newSectionName.equals(s)) {
					out.write(String.format("\n%s = %s", k, v).getBytes());
				}
			}
			out.write("\n".getBytes());
		}
	}
	
	private void processLine(int lineNumber, int offset, byte[] line) throws IOException {
		int localOffset = 0, i = 0;
		while (i < line.length && Character.isWhitespace(line[i])) {
			i++;
		}
		if (i == line.length) {
			return;
		}
		localOffset = i;
		if (line[i] == '[') {
			if (state == ParseState.Entry) {
				closeEntry();
			}
			if (state == ParseState.Section) {
				closeSection();
			}
			
			while (i < line.length && line[i] != ']') {
				i++;
			}
			if (i == line.length) {
				throw new IOException(String.format("Can't find closing ']' for section name in line %d", lineNumber));
			}
			sectionName = new String(line, localOffset+1, i-localOffset-1);
			sectionStart = offset + localOffset;
			state = ParseState.Section;
		} else if (line[i] == '#' || line[i] == ';') {
			// comment line, nothing to process
			return;
		} else {
			// entry
			if (state == ParseState.Initial) {
				throw new IOException(String.format("Line %d doesn't belong to any section", lineNumber));
			}
			if (localOffset > 0) {
				if (state == ParseState.Section) {
					throw new IOException(String.format("Non-indented key is expected in line %d", lineNumber));
				}
				assert state == ParseState.Entry;
				// whitespace-indented continuation of the previous entry  
				if (valueStart == -1) {
					// value didn't start at the same line the key was found at
					valueStart = offset + localOffset;
				}
				// value ends with eol (assumption is trailing comments are not allowed)
				valueEnd = offset + line.length - 1;
			} else {
				if (state == ParseState.Entry) {
					closeEntry();
				}
				assert state == ParseState.Section;
				// it's a new entry
				state  = ParseState.Entry;
				// get name of the entry
				while (i < line.length && !Character.isWhitespace(line[i]) && line[i] != '=') {
					i++;
				}
				if (i == line.length) {
					throw new IOException(String.format("Can't process entry in line %d", lineNumber));
				}
				entryKey = new String(line, localOffset, i - localOffset);
				entryStart = offset + localOffset;
				// look for '=' after key name
				while (i < line.length && line[i] != '=') {
					i++;
				}
				if (i == line.length) {
					throw new IOException(String.format("Can't find '=' after key %s in line %d", entryKey, lineNumber));
				}
				// skip whitespaces after '='
				i++; // line[i] == '='
				while (i < line.length && Character.isWhitespace(line[i])) {
					i++;
				}
				// valueStart might be -1 in case no value is specified in the same line as key
				// but valueEnd is always initialized just in case there's no next, value continuation line
				if (i == line.length) {
					valueStart = -1;
				} else {
					valueStart = offset + i;
				}
				
				// if trailing comments are allowed, shall
				// look up comment char and set valueEnd to its position-1
				valueEnd = offset + line.length - 1;
			}
		}
	}
	
	private void closeSection() {
		assert state == ParseState.Section;
		assert sectionName != null;
		assert lastNonEmptyLineEndOffset != -1;
		Section s = new Section(sectionName, sectionStart, lastNonEmptyLineEndOffset, entries == null ? Collections.<Entry>emptyList() : entries);
		sections.add(s);
		sectionName = null;
		sectionStart = -1;
		state = ParseState.Initial;
		entries = null;
	}
	
	private void closeEntry() {
		assert state == ParseState.Entry;
		assert entryKey != null;
		state = ParseState.Section;
		Entry e = new Entry(entryKey, entryStart, valueStart, valueEnd);
		if (entries == null) {
			entries = new ArrayList<Entry>();
		}
		entries.add(e);
		entryKey = null;
		entryStart = valueStart = valueEnd -1;
	}

	
	private static class Block {
		public final int start;
		Block(int s) {
			start = s;
		}
	}
	
	private static class Entry extends Block {
		public final int valueStart, valueEnd;
		public final String name;
		
		Entry(String n, int s, int vs, int ve) {
			super(s);
			name = n;
			valueStart = vs;
			valueEnd = ve;
		}
	}
	
	private static class Section extends Block {
		public final String name;
		public final Entry[] entries;
		public final int end;

		Section(String n, int s, int endOffset, List<Entry> e) {
			super(s);
			name = n;
			end = endOffset;
			entries = new Entry[e.size()];
			e.toArray(entries);
		}
	}
}
