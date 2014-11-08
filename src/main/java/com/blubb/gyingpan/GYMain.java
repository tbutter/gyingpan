package com.blubb.gyingpan;

import java.io.File;
import java.io.IOException;

import net.fusejna.FuseException;

public class GYMain {

	public static void main(String[] args) throws IOException, InterruptedException, FuseException {
		if(args.length != 2) {
			System.out.println("java -jar gyingpan.jar username mountpath");
			return;
		}
		GDrive g = new GDrive(args[0]);
		new FuseFS(g).mount(new File(args[1]));
	}

}
