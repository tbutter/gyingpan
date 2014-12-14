package com.blubb.gyingpan.actions;

import java.io.Serializable;

import com.blubb.gyingpan.GDrive;

public interface Action extends Serializable {

	public boolean run(GDrive drive);
}
