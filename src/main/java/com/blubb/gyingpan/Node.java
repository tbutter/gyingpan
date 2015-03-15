package com.blubb.gyingpan;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.blubb.gyingpan.actions.ChangeParentsAction;
import com.blubb.gyingpan.actions.RenameAction;
import com.blubb.gyingpan.actions.TrashAction;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive.Files.Insert;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.ParentReference;

public class Node implements Serializable {

	private static final long serialVersionUID = 2L;
	static final String folderType = "application/vnd.google-apps.folder"
			.intern();

	String name;
	String id;
	String mimetype;
	long lastModified;
	long size;
	String etag;
	String openurl;
	List<Node> children = null;
	CacheStatus cached = CacheStatus.NotInCache;
	transient GDrive gd;
	ArrayList<Node> parents = new ArrayList<Node>();

	Node(String name, String id, String mimetype,
			long lastModified, long size, String etag, String openurl,
			GDrive drive) {
		this.gd = drive;
		this.name = name;
		this.id = id;
		this.mimetype = mimetype.intern();
		if (this.mimetype == folderType)
			children = new LinkedList<Node>();
		this.lastModified = lastModified;
		this.size = size;
		this.etag = etag;
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
//		if (parents.isEmpty())
			return "";
//		return parents.get(0).getPath() + "/" + getFileName();
	}

	public synchronized java.io.File cache() throws IOException {
		synchronized (this) {
			//System.out.println("cache " + id + " " + isDirectory());
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
			if (cachefile.exists()) {
				if (cachefile.length() == getSize()
						&& Math.abs(cachefile.lastModified() - lastModified) < 1500) {
					cached = CacheStatus.InCache;
					return cachefile;
				}
			}

			String url = gd.service.files().get(id).execute().getDownloadUrl();
			GYMain.setStatus(url);
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
		GYMain.setStatus("flushing " + id + " " + getPath());
		if (cached != CacheStatus.Dirty)
			return;
		if (id.startsWith("tobefilled-")) {
			File newFile = new File();
			String mimeType = "application/octet-stream";
			newFile.setMimeType(mimeType);
			newFile.setModifiedDate(new DateTime(lastModified));
			newFile.setTitle(name);
			ArrayList<ParentReference> prefs = new ArrayList<ParentReference>();
			for (Node p : parents) {
				prefs.add(new ParentReference().setId(p.id));
			}
			newFile.setParents(prefs);
			FileContent mediaContent = new FileContent(mimeType, cacheFile());
			try {
				Insert insert = gd.service.files().insert(newFile, mediaContent);
				long startTime = System.currentTimeMillis();
				if (insert.getMediaHttpUploader() != null)
					insert.getMediaHttpUploader().setProgressListener(
							new MediaHttpUploaderProgressListener() {

								@Override
								public void progressChanged(
										MediaHttpUploader uploader)
										throws IOException {
									if(uploader.getProgress() > 0.0001) {
										long now = System.currentTimeMillis();
										double eta = (now-startTime)/uploader.getProgress()-(now-startTime);
										GYMain.setStatus(String.format("%s progress %.2f%% (eta %ds)\n", name, uploader.getProgress()*100, (int)eta/1000));
									}
								}
							});
				File f = insert.execute();
				
				java.io.File oldCacheFile = cacheFile();
				id = f.getId();
				GYMain.setStatus("rename " + oldCacheFile + " to "
						+ cacheFile());
				GYMain.setStatus("success: "
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

	public void rename(String toName) {
		GYMain.setStatus("rename " + getPath() + " " + toName);
		synchronized (this) {
			if (cached != CacheStatus.Dirty) {
				gd.addAction(new RenameAction(id, toName));
			}
			setName(toName);
		}
	}

	public void move(Node oldParent, Node toParent) {
		GYMain.setStatus("move " + getPath() + " " + toParent.getPath());
		synchronized (this) {
			if (!id.startsWith("tobefilled-")) {
				gd.addAction(new ChangeParentsAction(id, oldParent.id,
						toParent.id));
			}
			synchronized (gd) {
				oldParent.children.remove(this);
				toParent.children.add(this);
				parents.remove(oldParent);
				parents.add(toParent);
			}
		}
	}
	
	public synchronized void delete(Node parent) {
		parent.children.remove(this);
		parents.remove(parent);
		if (!id.startsWith("tobefilled-")) {
			gd.addAction(new ChangeParentsAction(id, parent.id, null));
			if(parents.isEmpty()) gd.addAction(new TrashAction(id));
		}
	}
}
