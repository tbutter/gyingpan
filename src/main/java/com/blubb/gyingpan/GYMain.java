package com.blubb.gyingpan;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.swing.JOptionPane;

import net.fusejna.FuseException;

public class GYMain {
	static FileLock fileLock = null;
	static MenuItem statusLabel = null;
	static PrintStream logstream = null;
	
	static void setStatus(String s) {
		System.out.println(s);
		if(statusLabel != null) statusLabel.setLabel(s);
		if(logstream != null) {
			logstream.println(s);
			logstream.flush();
		}
	}
	
	public static void main(String[] args) throws IOException,
			InterruptedException, FuseException {
		File configdir = new java.io.File(new java.io.File(
				System.getProperty("user.home")), ".gyingpan");
		configdir.mkdirs();
		File configfile = new java.io.File(configdir, "config.json");
		if (!configfile.exists()) {
			System.out.println("create config file");
			return;
		}
		logstream = new PrintStream(new BufferedOutputStream(new FileOutputStream(new java.io.File(configdir, "status.log"))));
		// lock
		
		final File lockfile = new File(configdir, "lock");
        final RandomAccessFile randomAccessLockFile = new RandomAccessFile(lockfile, "rw");
        fileLock = randomAccessLockFile.getChannel().tryLock();
        if (fileLock != null) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try {
                        fileLock.release();
                        randomAccessLockFile.close();
                        lockfile.delete();
                    } catch (Exception e) {
                    	e.printStackTrace();
                    }
                }
            });
        } else {
        	JOptionPane.showMessageDialog(null, "Already running");
        	return;
        }
		
		if (SystemTray.isSupported()) {
			Dimension dim = SystemTray.getSystemTray().getTrayIconSize();
			BufferedImage image = new BufferedImage(dim.width, dim.height, BufferedImage.TYPE_INT_ARGB);
			int step = 256 / dim.width;
			if(step < 1) step = 1;
			System.out.println("step "+step+" width "+dim.width);
			for(int x = 0; x < dim.width; x++) {
				int val = x * step;
				if(val > 255) val = 255;
				for(int y = 0; y < dim.height; y++) {
					image.setRGB(x, y, new Color(val, val, val).getRGB());
				}
			}
			final PopupMenu popup = new PopupMenu();
			final TrayIcon trayIcon = new TrayIcon(image);
			final SystemTray tray = SystemTray.getSystemTray();
			MenuItem exitItem = new MenuItem("Exit");
			statusLabel = new MenuItem("ready");
			popup.add(statusLabel);
			popup.add(exitItem);
			
			trayIcon.setPopupMenu(popup);
			exitItem.addActionListener(new ActionListener() {
				
				@Override
				public void actionPerformed(ActionEvent e) {
					System.exit(0);
				}
			});
			try {
				tray.add(trayIcon);
			} catch (AWTException e) {
				System.out.println("TrayIcon could not be added.");
			}
		}
		JsonObject config = Json.createReader(new FileInputStream(configfile))
				.readObject();
		JsonArray accounts = config.getJsonArray("accounts");
		for (JsonObject account : accounts.getValuesAs(JsonObject.class)) {
			GDrive g = new GDrive(account.getString("name"));
			new FuseFS(g).mount(new File(account.getString("path")));
		}
	}

}
