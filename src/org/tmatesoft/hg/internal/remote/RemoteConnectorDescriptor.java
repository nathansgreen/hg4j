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
package org.tmatesoft.hg.internal.remote;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.tmatesoft.hg.core.HgBadArgumentException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.util.Pair;

/**
 * Connector-aware descriptor of remote repository, i.e. descriptor that uses 
 * {@link Connector Connectors} to connect to a remote repository.
 * 
 * <p>Candidate to become public API, with createConnector() method, so that {@link HgRemoteRepository} 
 * may accept instances of that interfact directly
 * 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RemoteConnectorDescriptor implements HgRemoteRepository.RemoteDescriptor {
	
	private Map<String, Pair<ClassLoader, String>> connFactory;
	private final URI uri;

	public RemoteConnectorDescriptor(Map<String,Pair<ClassLoader, String>> scheme2connectorMap, URI uriRemote) {
		this(uriRemote);
		connFactory = scheme2connectorMap;
	}
	
	protected RemoteConnectorDescriptor(URI uriRemote) {
		uri = uriRemote;
	}

	public URI getURI() {
		return uri;
	}

	public Connector createConnector() throws HgBadArgumentException {
		Pair<ClassLoader, String> connectorToBe = connFactory.get(uri.getScheme());
		if (connectorToBe == null || connectorToBe.second() == null) {
			throw new HgBadArgumentException(String.format("Can't instantiate connector for scheme '%s'", uri.getScheme()), null);
		}
		try {
			Class<?> connClass = connectorToBe.first().loadClass(connectorToBe.second());
			if (!Connector.class.isAssignableFrom(connClass)) {
				throw new HgBadArgumentException(String.format("Connector %s for scheme '%s' is not a subclass of %s", connectorToBe.second(), uri.getScheme(), Connector.class.getName()), null);
			}
			final Object connector = connClass.newInstance();
			return Connector.class.cast(connector);
		} catch (ClassNotFoundException ex) {
			throw new HgBadArgumentException(String.format("Can't instantiate connector %s for scheme '%s'", connectorToBe.second(), uri.getScheme()), ex);
		} catch (InstantiationException ex) {
			throw new HgBadArgumentException(String.format("Can't instantiate connector %s for scheme '%s'", connectorToBe.second(), uri.getScheme()), ex);
		} catch (IllegalAccessException ex) {
			throw new HgBadArgumentException(String.format("Can't instantiate connector %s for scheme '%s'", connectorToBe.second(), uri.getScheme()), ex);
		}
	}

	// I don't see a reason to expose provider of RemoteDescriptors yet
	// although it might not be the best idea for session context to serve as provider intermediate
	public static class Provider {
		private final Map<String, Pair<ClassLoader, String>> knownConnectors = new HashMap<String, Pair<ClassLoader, String>>(5);
		
		{
			final ClassLoader cl = Provider.class.getClassLoader();
			knownConnectors.put("http", new Pair<ClassLoader, String>(cl, HttpConnector.class.getName()));
			knownConnectors.put("https", new Pair<ClassLoader, String>(cl, HttpConnector.class.getName()));
			knownConnectors.put("ssh", new Pair<ClassLoader, String>(cl, "org.tmatesoft.hg.internal.remote.SshConnector"));
		}

		public HgRemoteRepository.RemoteDescriptor get(SessionContext ctx, URI uri) {
			if (knownConnectors.containsKey(uri.getScheme())) {
				return new RemoteConnectorDescriptor(knownConnectors, uri);
			}
			return null;
		}
	}
}
