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
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BasicSessionContext extends SessionContext {

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

	@Override
	public LogFacility getLog() {
		// e.g. for exceptions that we can't handle but log (e.g. FileNotFoundException when we've checked beforehand file.canRead()
		if (logFacility == null) {
			PropertyMarshal pm = new PropertyMarshal(this);
			boolean needDebug = pm.getBoolean("hg4j.consolelog.debug", false);
			boolean needInfo = pm.getBoolean("hg4j.consolelog.info", false);
			boolean needTime = pm.getBoolean("hg4j.consolelog.tstamp", true);
			Severity l = needDebug ? Severity.Debug : (needInfo ? Severity.Info : Severity.Warn);
			logFacility = new StreamLogFacility(l, needTime, System.out);
		}
		return logFacility;
	}
	
	// specific helpers for boolean and int values are available from PropertyMarshal
	@Override
	public Object getConfigurationProperty(String name, Object defaultValue) {
		// NOTE, this method is invoked from getLog(), hence do not call getLog from here unless changed appropriately
		Object value = properties.get(name);
		if (value != null) {
			return value;
		}
		value = System.getProperty(name);
		return value == null ? defaultValue : value;
	}
}
