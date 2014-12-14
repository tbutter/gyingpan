package com.blubb.gyingpan;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;

import net.fusejna.FuseException;

public class GYMain {

	public static void main(String[] args) throws IOException, InterruptedException, FuseException {
		File configfile = new java.io.File(new java.io.File(new java.io.File(
				System.getProperty("user.home")), ".gyingpan"), "config.json");
		if(!configfile.exists()) {
			System.out.println("create config file");
			return;
		}
		JsonObject config = Json.createReader(new FileInputStream(configfile)).readObject();
		JsonArray accounts = config.getJsonArray("accounts");
		for(JsonObject account : accounts.getValuesAs(JsonObject.class)) {
			GDrive g = new GDrive(account.getString("name"));
			new FuseFS(g).mount(new File(account.getString("path")));
		}
	}

}
