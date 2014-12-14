package com.blubb.gyingpan.actions;

import com.blubb.gyingpan.GDrive;

public class TrashAction implements Action {
	/**
	 * 
	 */
	private static final long serialVersionUID = 2442587974678396997L;
	String nodeID;

	public TrashAction(String nodeID) {
		this.nodeID = nodeID;
	}

	@Override
	public boolean run(GDrive drive) {
		try {
			drive.service.files().trash(nodeID).execute();
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

}
