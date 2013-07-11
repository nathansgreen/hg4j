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
package org.tmatesoft.hg.core;

import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.Path;

/**
 * Access to objects that might need to be shared between various distinct operations ran during the same working session 
 * (i.e. caches, log, etc.). It's unspecified whether session context is per repository or can span multiple repositories
 * 
 * <p>Note, API is likely to be extended in future versions, adding more object to share. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public abstract class SessionContext {
	// abstract class to facilitate adding more functionality without API break
	
	/**
	 * Access wrapper for a system log facility.
	 * @return facility to direct dumps to, never <code>null</code>
	 */
	public abstract LogFacility getLog();
	
	/**
	 * Access configuration parameters of the session.
	 * @param name name of the session configuration parameter
	 * @param defaultValue value to return if parameter is not configured
	 * @return value of the session parameter, defaultValue if none found
	 */
	public abstract Object getConfigurationProperty(String name, Object defaultValue);
	// perhaps, later may add Configuration object, with PropertyMarshal's helpers
	// e.g. when there's standalone Caches and WritableSessionProperties objects

	/**
	 * Provide a factory to create {@link Path} objects.
	 * 
	 * Occasionally, there's a need to construct a {@link Path} object from a string/byte data 
	 * kept in mercurial control files. Generally, default implementation (with {@link Path#create(CharSequence)} 
	 * is enough, however, if there's a need to control number of string objects in memory (i.e. prevent duplicates),
	 * default implementation might need to be replaced with more sophisticated (e.g. using weak references or
	 * just a huge hash set).
	 * 
	 * @return factory to construct Path objects, never <code>null</code>
	 */
	public Path.Source getPathFactory() {
		return new Path.Source() {
			public Path path(CharSequence p) {
				return Path.create(p);
			}
		};
	}

	/**
	 * Providers of the context may implement
	 */
	public interface Source {
		SessionContext getSessionContext();
	}
	
	public static final class SourcePrim implements Source {
		private final SessionContext ctx;

		public SourcePrim(SessionContext sessionContext) {
			assert sessionContext != null;
			ctx = sessionContext;
		}
		public SessionContext getSessionContext() {
			return ctx;
		}
	}
}
