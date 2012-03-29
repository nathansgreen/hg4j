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

import java.util.Collections;
import java.util.Map;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.util.LogFacility;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BasicSessionContext implements SessionContext {

	private LogFacility logFacility;
	private final Map<String, Object> properties;
	
	public BasicSessionContext(LogFacility log) {
		this(null, log);
	}
	
	@SuppressWarnings("unchecked")
	public BasicSessionContext(Map<String,?> propertyOverrides, LogFacility log) {
		logFacility = log;
		properties = propertyOverrides == null ? Collections.<String,Object>emptyMap() : (Map<String, Object>) propertyOverrides;
	}

	public LogFacility getLog() {
		// e.g. for exceptions that we can't handle but log (e.g. FileNotFoundException when we've checked beforehand file.canRead()
		if (logFacility == null) {
			boolean needDebug = _getBooleanProperty("hg.consolelog.debug", false);
			boolean needInfo = needDebug || _getBooleanProperty("hg.consolelog.info", false);
			logFacility = new StreamLogFacility(needDebug, needInfo, true, System.out);
		}
		return logFacility;
	}
	
	private boolean _getBooleanProperty(String name, boolean defaultValue) {
		// can't use <T> and unchecked cast because got no confidence passed properties are strictly of the kind of my default values,
		// i.e. if boolean from outside comes as "true", while I pass default as Boolean or vice versa.  
		Object p = getProperty(name, defaultValue);
		return p instanceof Boolean ? ((Boolean) p).booleanValue() : Boolean.parseBoolean(String.valueOf(p));
	}

	// TODO specific helpers for boolean and int values
	public Object getProperty(String name, Object defaultValue) {
		// NOTE, this method is invoked from getLog(), hence do not call getLog from here unless changed appropriately
		Object value = properties.get(name);
		if (value != null) {
			return value;
		}
		value = System.getProperty(name);
		return value == null ? defaultValue : value;
	}
}
