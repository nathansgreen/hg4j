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
package org.tmatesoft.hg.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ConfigFile {

	private List<String> sections;
	private List<Map<String,String>> content;

	ConfigFile() {
	}

	public void addLocation(File path) {
		read(path);
	}
	
	public boolean hasSection(String sectionName) {
		return sections == null ? false : sections.indexOf(sectionName) == -1;
	}
	
	// XXX perhaps, should be moved to subclass HgRepoConfig, as it is not common operation for any config file
	public boolean hasEnabledExtension(String extensionName) {
		int x = sections != null ? sections.indexOf("extensions") : -1;
		if (x == -1) {
			return false;
		}
		String value = content.get(x).get(extensionName);
		return value != null && !"!".equals(value);
	}
	
	public List<String> getSectionNames() {
		return sections == null ? Collections.<String>emptyList() : Collections.unmodifiableList(sections);
	}

	public Map<String,String> getSection(String sectionName) {
		if (sections ==  null) {
			return Collections.emptyMap();
		}
		int x = sections.indexOf(sectionName);
		if (x == -1) {
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(content.get(x));
	}

	public boolean getBoolean(String sectionName, String key, boolean defaultValue) {
		String value = getSection(sectionName).get(key);
		if (value == null) {
			return defaultValue;
		}
		for (String s : new String[] { "true", "yes", "on", "1" }) {
			if (s.equalsIgnoreCase(value)) {
				return true;
			}
		}
		return false;
	}
	
	public String getString(String sectionName, String key, String defaultValue) {
		String value = getSection(sectionName).get(key);
		return value == null ? defaultValue : value;
	}

	// TODO handle %include and %unset directives
	// TODO "" and lists
	private void read(File f) {
		if (f == null || !f.canRead()) {
			return;
		}
		if (sections == null) {
			sections = new ArrayList<String>();
			content = new ArrayList<Map<String,String>>();
		}
		try {
			BufferedReader br = new BufferedReader(new FileReader(f));
			String line;
			String sectionName = "";
			Map<String,String> section = new LinkedHashMap<String, String>();
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.length() <= 2) { // a=b or [a] are at least of length 3
					continue;
				}
				int x;
				if (line.charAt(0) == '[' && line.charAt(line.length() - 1) == ']') {
					sectionName = line.substring(1, line.length() - 1);
					if (sections.indexOf(sectionName) == -1) {
						sections.add(sectionName);
						content.add(section = new LinkedHashMap<String, String>());
					} else {
						section = null; // drop cached value
					}
				} else if ((x = line.indexOf('=')) != -1) {
					String key = line.substring(0, x).trim();
					String value = line.substring(x+1).trim();
					if (section == null) {
						int i = sections.indexOf(sectionName);
						assert i >= 0;
						section = content.get(i);
					}
					if (sectionName.length() == 0) {
						// add fake section only if there are any values 
						sections.add(sectionName);
						content.add(section);
					}
					section.put(key, value);
				}
			}
			br.close();
		} catch (IOException ex) {
			ex.printStackTrace(); // XXX shall outer world care?
		}
		((ArrayList<?>) sections).trimToSize();
		((ArrayList<?>) content).trimToSize();
		assert sections.size() == content.size();
	}
}
