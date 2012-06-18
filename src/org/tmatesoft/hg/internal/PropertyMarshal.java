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

import org.tmatesoft.hg.core.SessionContext;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class PropertyMarshal {
	
	private final SessionContext sessionContext;

	public PropertyMarshal(SessionContext ctx) {
		sessionContext = ctx;
	}

	public boolean getBoolean(String propertyName, boolean defaultValue) {
		// can't use <T> and unchecked cast because got no confidence passed properties are strictly of the kind of my default values,
		// i.e. if boolean from outside comes as "true", while I pass default as Boolean or vice versa.  
		Object p = sessionContext.getConfigurationProperty(propertyName, defaultValue);
		return p instanceof Boolean ? ((Boolean) p).booleanValue() : Boolean.parseBoolean(String.valueOf(p));
	}
	
	public int getInt(String propertyName, int defaultValue) {
		Object v = sessionContext.getConfigurationProperty(propertyName, defaultValue);
		if (false == v instanceof Number) {
			v = Integer.parseInt(String.valueOf(v));
		}
		return ((Number) v).intValue();
	}
}
