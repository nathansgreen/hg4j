/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.DataInput;

/**
 * ? Single RevlogStream per file per repository with accessor to record access session (e.g. with back/forward operations), 
 * or numerous RevlogStream with separate representation of the underlaying data (cached, lazy ChunkStream)?
 * @author artem
 * @see http://mercurial.selenic.com/wiki/Revlog
 * @see http://mercurial.selenic.com/wiki/RevlogNG
 */
public class RevlogStream {
	
	private void detectVersion() {
		
	}

	/*package*/ DataInput getIndexStream() {
		// TODO Auto-generated method stub
		return null;
	}

	/*package*/ DataInput getDataStream() {
		// TODO Auto-generated method stub
		return null;
	}
	
}
