package com.blubb.gyingpan;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.ParentReference;

public class Node implements Serializable {

	private static final long serialVersionUID = 1L;
	static final String folderType = "application/vnd.google-apps.folder"
			.intern();

	private String name;
	String id;
	String md5;
	String mimetype;
	long lastModified;
	long size;
	String etag;
	String openurl;
	List<Node> children = null;
	Node parent;
	CacheStatus cached = CacheStatus.NotInCache;
	transient GDrive gd;

	Node(String name, String id, String md5, String mimetype,
			long lastModified, long size, String etag, String openurl,
			Node parent, GDrive drive) {
		this.gd = drive;
		this.name = name;
		this.id = id;
		this.md5 = md5;
		this.mimetype = mimetype.intern();
		if (this.mimetype == folderType)
			children = new LinkedList<Node>();
		this.lastModified = lastModified;
		this.size = size;
		this.etag = etag;
		this.parent = parent;
		if (isGoogleFile()) {
			System.out.println(openurl);
			this.openurl = openurl;
		}
	}

	public boolean isDirectory() {
		return mimetype == folderType;
	}

	public boolean isGoogleFile() {
		if (mimetype == folderType)
			return false;
		return mimetype.startsWith("application/vnd.google-apps");
	}

	public String getFileName() {
		if (isGoogleFile())
			return name + ".html";
		return name;
	}

	private void readObject(ObjectInputStream inputStream) throws IOException,
			ClassNotFoundException {
		inputStream.defaultReadObject();
		mimetype = mimetype.intern();
	}

	public long getSize() {
		if (isGoogleFile())
			return getRedirFile().getBytes(StandardCharsets.UTF_8).length;
		return size;
	}

	String getRedirFile() {
		String link = "<html>\n" + "<head>\n" + "<title>redirect</title>\n"
				+ "<script>\n" + "window.location.href=\"" + openurl + "\";\n"
				+ "</script>\n" + "</head>\n" + "<body>\n" + "</body>\n"
				+ "</html>";
		return link;
	}

	public void setName(String name) {
		this.name = name;
	}

	String getPath() {
		if (parent == null)
			return "";
		return parent.getPath() + "/" + getFileName();
	}

	public synchronized java.io.File cache() throws IOException {
		synchronized (this) {
			System.out.println("cache " + id + " " + isDirectory());
			if (isDirectory())
				return null;
			java.io.File cachefile = cacheFile();
			if (cached == CacheStatus.Dirty)
				return cachefile;
			if (cached == CacheStatus.InCache) {
				if (cachefile.exists()) {
					if (cachefile.length() == getSize()
							&& Math.abs(cachefile.lastModified() - lastModified) < 1500)
						return cachefile;
					else {
						System.out.println("deleting old cachefile " + id
								+ " cf.lm " + cachefile.lastModified()
								+ " n.lm " + lastModified);
						cachefile.delete();
						cached = CacheStatus.NotInCache;
					}
				}
			}
			if (isGoogleFile()) {
				FileOutputStream fos = new FileOutputStream(cachefile);
				fos.write(getRedirFile().getBytes(StandardCharsets.UTF_8));
				fos.close();
				cachefile.setLastModified(lastModified);
				cached = CacheStatus.InCache;
				return cachefile;
			}

			String url = gd.service.files().get(id).execute().getDownloadUrl();
			System.out.println(url);
			HttpResponse resp = gd.service.getRequestFactory()
					.buildGetRequest(new GenericUrl(url)).execute();
			FileOutputStream fos = new FileOutputStream(cachefile);
			resp.download(fos);
			fos.close();
			cachefile.setLastModified(lastModified);
			cached = CacheStatus.InCache;
			return cachefile;
		}
	}

	java.io.File cacheFile() {
		java.io.File parentDir = new java.io.File(gd.cachedir,
				Integer.toHexString(id.hashCode() & 0xFF));
		java.io.File f = new java.io.File(parentDir, id);
		return f;
	}

	public synchronized void markDirty() {
		lastModified = System.currentTimeMillis();
		if (cached != CacheStatus.Dirty) {
			cached = CacheStatus.Dirty;
			gd.addToDirtyNodes(this);
		}
	}

	public synchronized void truncate(long len) throws IOException {
		RandomAccessFile ra = new RandomAccessFile(cacheFile(), "rw");
		ra.setLength(len);
		ra.close();
		size = len;
	}

	public synchronized void flush() {
		System.out.println("flushing " + id + " " + getPath());
		if(cached != CacheStatus.Dirty) return;
		if (id.startsWith("tobefilled-")) {
			File newFile = new File();
			String mimeType = "application/octet-stream";
			newFile.setMimeType(mimeType);
			newFile.setModifiedDate(new DateTime(lastModified));
			newFile.setTitle(name);
			newFile.setParents(Collections.singletonList(new ParentReference()
					.setId(parent.id)));
			FileContent mediaContent = new FileContent(mimeType, cacheFile());
			try {
				File f = gd.service.files().insert(newFile, mediaContent)
						.execute();
				java.io.File oldCacheFile = cacheFile();
				id = f.getId();
				System.out.println("rename " + oldCacheFile + " to "
						+ cacheFile());
				System.out.println("success: "
						+ oldCacheFile.renameTo(cacheFile()));
				cached = CacheStatus.InCache;
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			try {
				File f = gd.service.files().get(id).execute();
				f.setModifiedDate(new DateTime(lastModified));
				FileContent mediaContent = new FileContent(mimetype,
						cacheFile());
				f = gd.service.files().update(id, f, mediaContent)
						.setSetModifiedDate(true).setNewRevision(true)
						.execute();
				cached = CacheStatus.InCache;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		gd.requestPersist();
	}
}
