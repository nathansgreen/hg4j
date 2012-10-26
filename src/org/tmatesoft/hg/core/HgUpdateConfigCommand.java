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
package org.tmatesoft.hg.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.ConfigFileParser;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * Command to alter Mercurial configuration settings at various levels (system-wide, user-wide, repository-wide).
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgUpdateConfigCommand extends HgAbstractCommand<HgUpdateConfigCommand> {
	
	private final SessionContext sessionCtx;
	private final File configFile;
	
	private final List<Operation> changes = new LinkedList<Operation>();
	private boolean ignoreMissingKeys = false;

	private HgUpdateConfigCommand(SessionContext sessionContext, File configurationFile) {
		sessionCtx = sessionContext;
		configFile = configurationFile;
	}
	
	public static HgUpdateConfigCommand forRepository(HgRepository hgRepo) {
		return new HgUpdateConfigCommand(hgRepo.getSessionContext(), HgInternals.getImplementationRepo(hgRepo).getFileFromRepoDir("hgrc"));
	}
	
	public static HgUpdateConfigCommand forUser(SessionContext ctx) {
		return new HgUpdateConfigCommand(ctx, Internals.getUserConfigurationFileToWrite(ctx));
	}
	
	public static HgUpdateConfigCommand forInstallation(SessionContext ctx) {
		return new HgUpdateConfigCommand(ctx, Internals.getInstallationConfigurationFileToWrite(ctx));
	}

	/**
	 * Remove an entry altogether. If no entry with the key found, {@link #execute()} fails.
	 * 
	 * @param section identifies section to alter, not <code>null</code> or otherwise ill-formed name
	 * @param key identifies entry within section, not <code>null</code> or otherwise ill-formed name 
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException if arguments are <code>null</code> or empty
	 */
	public HgUpdateConfigCommand remove(String section, String key) throws IllegalArgumentException {
		checkSection(section);
		checkKey(key);
		changes.add(Operation.deleteEntry(section, key));
		return this;
	}

	/**
	 * Delete single attribute in a multi-valued property. If specified value not found among values of
	 * the identified entry, {@link #execute()} fails. 
	 * 
	 * @param section identifies section to alter, not <code>null</code> or otherwise ill-formed name
	 * @param key identifies entry within section, not <code>null</code> or otherwise ill-formed name 
	 * @param value one of the values to remove, not <code>null</code> or an empty value
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException if arguments are <code>null</code> or empty
	 */
	public HgUpdateConfigCommand remove(String section, String key, String value) throws IllegalArgumentException {
		checkSection(section);
		checkKey(key);
		changes.add(Operation.deleteValue(section, key, value));
		return this;
	}
	
	/**
	 * Set single-valued property or update multi-valued with a single value
	 * 
	 * @param section identifies section to alter, not <code>null</code> or otherwise ill-formed name
	 * @param key identifies entry within section, not <code>null</code> or otherwise ill-formed name 
	 * @param value new value, may be <code>null</code>
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException if arguments are <code>null</code> or empty
	 */
	public HgUpdateConfigCommand put(String section, String key, String value) throws IllegalArgumentException {
		checkSection(section);
		checkKey(key);
		changes.add(Operation.setValue(section, key, value));
		return this;
	}
	
	/**
	 * Add value to a multi-valued entry. If specified entry not found, {@link #execute()} fails.
	 * 
	 * @param section identifies section to alter, not <code>null</code> or otherwise ill-formed name
	 * @param key identifies entry within section, not <code>null</code> or otherwise ill-formed name 
	 * @param value new value to add, not <code>null</code> or an empty value
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException if arguments are <code>null</code> or empty
	 */
	public HgUpdateConfigCommand add(String section, String key, String value) throws IllegalArgumentException {
		checkSection(section);
		checkKey(key);
		changes.add(Operation.addValue(section, key, value));
		return this;
	}
	
	/**
	 * Tells whether {@link #execute()} shall fail with exception if keys selected for modification were not found. 
	 * If <code>true</code>, missing keys would be silently ignored. 
	 * When <code>false</code>(<em>default</em>), exception would be raised.
	 *  
	 * @param ignoreMissing pass <code>true</code> to ignore any incorrect keys
	 * @return <code>this</code> for convenience
	 */
	public HgUpdateConfigCommand ignoreMissing(boolean ignoreMissing) {
		ignoreMissingKeys = ignoreMissing;
		return this;
	}
	
	/**
	 * Perform configuration file update.
	 * 
	 * @throws HgMissingConfigElementException if attempt to alter an entry failed to find one, and missing keys are not ignored
	 * @throws HgIOException when configuration file read/write attemt has failed
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 */
	public void execute() throws HgMissingConfigElementException, HgIOException, HgException {
		try {
			ConfigFile cfgRead = new ConfigFile(sessionCtx);
			cfgRead.addLocation(configFile);
			ConfigFileParser cfgWrite = new ConfigFileParser();
			FileInputStream fis = new FileInputStream(configFile);
			cfgWrite.parse(fis);
			fis.close();
			for (Operation op : changes) {
				if (!ignoreMissingKeys && !cfgRead.hasSection(op.section)) {
					throw new HgMissingConfigElementException("Bad section name", op.section, op.key);
				}
				Map<String, String> sect = cfgRead.getSection(op.section);
				if (!ignoreMissingKeys && !sect.containsKey(op.key)) {
					throw new HgMissingConfigElementException("Bad key name", op.section, op.key);
				}
				String oldValue = sect.get(op.key);
				if (oldValue == null) {
					oldValue = "";
				}
				switch (op.kind) {
				case AddValue: {
					String separator = ", "; // XXX shall parse and find out separator kind in use
					String newValue = oldValue + separator + op.value;
					if (sect.containsKey(op.key)) {
						cfgWrite.change(op.section, op.key, newValue);
					} else {
						cfgWrite.add(op.section, op.key, newValue);
					}
					break;
				}
				case DelValue: {
					if (!ignoreMissingKeys && (oldValue.length() == 0 || !oldValue.contains(op.value))) {
						throw new HgMissingConfigElementException(String.format("Bad value '%s' to delete from '%s'", op.value, oldValue), op.section, op.key);
					}
					int start = oldValue.indexOf(op.value);
					if (start == -1) {
						// nothing to change
						break;
					}
					int commaPos = -1;
					for (int i = start-1; i >=0; i--) {
						if (oldValue.charAt(i) == ',') {
							commaPos = i;
							break;
						}
					}
					for (int i = start + op.value.length(); commaPos == -1 && i < oldValue.length(); i++) {
						if (oldValue.charAt(i) == ',') {
							commaPos = i;
							break;
						}
					}
					String newValue;
					if (commaPos >= 0) {
						if (commaPos < start) {
							// from preceding comma up to end of value
							newValue = oldValue.substring(0, commaPos) + oldValue.substring(start + op.value.length());
						} else {
							// from value start up to and including subsequent comma
							newValue = oldValue.substring(0, start) + oldValue.substring(commaPos+1);
						}
					} else {
						// found no separator, just remove the value
						// extra whitespaces (if space, not a comma is a separator) won't hurt
						newValue = oldValue.substring(0, start) + oldValue.substring(start + op.value.length());
					}
					cfgWrite.change(op.section, op.key, newValue);
					break;
				}
				case SetValue: {
					if (sect.containsKey(op.key)) {
						cfgWrite.change(op.section, op.key, op.value);
					} else {
						cfgWrite.add(op.section, op.key, op.value);
					}
					break;
				}
				case DelEntry: {
					cfgWrite.delete(op.section, op.key);
					break;
				}
				default: throw new HgInvalidStateException(String.format("Unknown change %s", op.kind));
				}
			}
			FileOutputStream fos = new FileOutputStream(configFile);
			cfgWrite.update(fos);
			fos.close();
		} catch (IOException ex) {
			String m = String.format("Failed to update configuration file %s", configFile);
			throw new HgBadArgumentException(m, ex); // TODO [post-1.0] better exception, it's not bad argument case
		}
	}
	
	private static void checkSection(String section) throws IllegalArgumentException {
		if (section == null || section.trim().length() == 0) {
			throw new IllegalArgumentException(String.format("Section name can't be empty: %s", section));
		}
	}
	
	private static void checkKey(String key) throws IllegalArgumentException {
		if (key == null || key.trim().length() == 0) {
			throw new IllegalArgumentException(String.format("Entry key can't be empty: %s", key));
		}
	}


	private static class Operation {
		private enum OpKind { AddValue, SetValue, DelValue, DelEntry };

		public final OpKind kind;
		public final String section;
		public final String key;
		public final String value;
		
		private Operation(OpKind t, String s, String k, String v) {
			kind = t;
			section = s;
			key = k;
			value = v;
		}
		
		public static Operation deleteEntry(String section, String key) throws IllegalArgumentException {
			return new Operation(OpKind.DelEntry, section, key, null);
		}
		public static Operation deleteValue(String section, String key, String value) throws IllegalArgumentException {
			if (value == null || value.trim().length() == 0) {
				throw new IllegalArgumentException(String.format("Can't remove empty value '%s'", value));
			}
			return new Operation(OpKind.DelValue, section, key, value);
		}
		public static Operation addValue(String section, String key, String value) throws IllegalArgumentException {
			if (value == null || value.trim().length() == 0) {
				throw new IllegalArgumentException(String.format("Can't add empty value '%s'", value));
			}
			return new Operation(OpKind.AddValue, section, key, value);
		}
		public static Operation setValue(String section, String key, String value) throws IllegalArgumentException {
			return new Operation(OpKind.SetValue, section, key, value);
		}
	}
}
