package com.blubb.gyingpan.actions;

import com.blubb.gyingpan.GDrive;
import com.google.api.services.drive.Drive.Files.Patch;
import com.google.api.services.drive.model.File;

public class ChangeParentsAction implements Action {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2041407857767591926L;
	String nodeID;
	String removeParentID;
	String addParentID;

	public ChangeParentsAction(String nodeID, String removeParentID,
			String addParentID) {
		this.nodeID = nodeID;
		this.removeParentID = removeParentID;
		this.addParentID = addParentID;
	}

	@Override
	public boolean run(GDrive drive) {
		try {
			File f = drive.service.files().get(nodeID).execute();
			Patch p = drive.service.files().patch(nodeID, f);
			if(removeParentID != null) p.setRemoveParents(removeParentID);
			if(addParentID != null) p.setAddParents(addParentID);
			p.execute();
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}
}
