package com.blubb.gyingpan.actions;

import com.blubb.gyingpan.GDrive;
import com.google.api.services.drive.model.File;

public class RenameAction implements Action {
	private static final long serialVersionUID = -2563252795391137367L;
	String nodeID;
	String newName;

	public RenameAction(String nodeID, String newName) {
		this.nodeID = nodeID;
		this.newName = newName;
	}

	@Override
	public boolean run(GDrive drive) {
		try {
			File f = drive.service.files().get(nodeID).execute();
			f.setTitle(newName);
			drive.service.files().patch(nodeID, f).execute();
		} catch (Throwable e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}
}
