package com.blubb.gyingpan;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.fusejna.StructFuseFileInfo.FileInfoWrapper;

import com.blubb.gyingpan.actions.Action;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.About;
import com.google.api.services.drive.Drive.Changes;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

public class GDrive {
	public final static String CLIENT_ID = "986587539645-1keh36kli6put6n2a3k1vfl8k985iopi.apps.googleusercontent.com";
	public final static String CLIENT_SECRET = "kdIeEEs5oKjMyaJgf3IFAScz";
	private final static String REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";
	// AES_GCM is too slow in Java8
	public static final HttpTransport TRANSPORT = new NetHttpTransport.Builder()
			.setSslSocketFactory(new NoGCMSslSocketFactory()).build();
	Node root = null;
	private final java.io.File jdrivedir;
	final java.io.File cachedir;
	public Drive service;
	long nextChangeId = 0;
	boolean persistRequested = false;
	ScheduledExecutorService executor = Executors
			.newSingleThreadScheduledExecutor();
	public final String accountName;

	GDrive(String username) throws IOException, InterruptedException {
		accountName = username;
		jdrivedir = new java.io.File(new java.io.File(new java.io.File(
				System.getProperty("user.home")), ".gyingpan"), username);
		jdrivedir.mkdirs();
		cachedir = new java.io.File(jdrivedir, "cache");
		cachedir.mkdirs();
		for (int i = 0; i <= 255; i++) {
			new java.io.File(cachedir, Integer.toHexString(i)).mkdirs();
		}
		JsonFactory jsonFactory = new JacksonFactory();

		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				TRANSPORT, jsonFactory, CLIENT_ID, CLIENT_SECRET,
				Arrays.asList(DriveScopes.DRIVE))
				.setAccessType("offline")
				.setApprovalPrompt("auto")
				.setDataStoreFactory(
						new FileDataStoreFactory(new java.io.File(jdrivedir,
								"driveauth"))).build();
		Credential credential = flow.loadCredential(username);
		if (credential == null) {
			String url = flow.newAuthorizationUrl()
					.setRedirectUri(REDIRECT_URI).build();
			System.out
					.println("Please open the following URL in your browser then type the authorization code:");
			System.out.println("  " + url);
			BufferedReader br = new BufferedReader(new InputStreamReader(
					System.in));
			String code = br.readLine();

			GoogleTokenResponse response = flow.newTokenRequest(code)
					.setRedirectUri(REDIRECT_URI).execute();
			credential = flow.createAndStoreCredential(response, username);
		}
		final Credential fcredential = credential;
		// Create a new authorized API client
		service = new Drive.Builder(TRANSPORT, jsonFactory, credential)
				.setHttpRequestInitializer(new HttpRequestInitializer() {
					@Override
					public void initialize(HttpRequest request)
							throws IOException {
						fcredential.initialize(request);
						request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(
								new ExponentialBackOff()));
						request.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(
								new ExponentialBackOff()));
					}
				}).build();
		try {
			ObjectInputStream fis = new ObjectInputStream(
					new BufferedInputStream(new FileInputStream(
							new java.io.File(jdrivedir, "filecache.db"))));
			nextChangeId = fis.readLong();
			root = (Node) fis.readObject();
			fis.close();
			markDirtyNodes(root);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		if (root == null) {
			initFileList();
		}
		executor.scheduleWithFixedDelay(() -> update(), 60, 60,
				TimeUnit.SECONDS);
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

			@Override
			public void run() {
				doPersist();
			}
		}));
	}

	private void initFileList() throws IOException, InterruptedException {
		// about
		About.Get aboutreq = service.about().get();
		com.google.api.services.drive.model.About about = aboutreq.execute();

		nextChangeId = about.getLargestChangeId() + 1;
		String rootFolder = about.getRootFolderId();
		GYMain.setStatus("rootFolder ID " + rootFolder);
		long time = System.currentTimeMillis();
		List<File> files = retrieveAllFiles(service);
		GYMain.setStatus("time " + (System.currentTimeMillis() - time));
		// finding parents
		ListMultimap<String, Node> parentMap = ArrayListMultimap.create();
		root = new Node("", rootFolder, Node.folderType, 0, 0,
				"rootFolderEtag", null, this);
		for (File f : files) {
			Node n = new Node(f.getTitle(), f.getId(), 
					f.getMimeType(), f.getModifiedDate() == null ? 0 : f
							.getModifiedDate().getValue(),
					f.getFileSize() == null ? 0 : f.getFileSize().longValue(),
					f.getEtag(), f.getAlternateLink(), this);
			for (ParentReference pr : f.getParents()) {
				parentMap.put(pr.getId(), n);
			}
		}
		initChildren(root, parentMap);
		// showFolder("", root);
		requestPersist();
	}

	void doPersist() {
		GYMain.setStatus("persist start");
		synchronized (this) {
			ObjectOutputStream fos;
			try {
				java.io.File newFile = new java.io.File(jdrivedir,
						"filecache.db.new");
				java.io.File oldFile = new java.io.File(jdrivedir,
						"filecache.db");
				fos = new ObjectOutputStream(new BufferedOutputStream(
						new FileOutputStream(newFile), 128 * 1024));
				fos.writeLong(nextChangeId);
				fos.writeObject(root);
				fos.flush();
				fos.close();
				oldFile.delete();
				newFile.renameTo(oldFile);
				GYMain.setStatus("saved");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		GYMain.setStatus("persist done");
	}

	private void flush() {
		long now = System.currentTimeMillis();
		synchronized (dirtyNodes) {
			ArrayList<Node> toRemove = new ArrayList<Node>();
			for (Node n : dirtyNodes) {
				if (now - n.lastModified > 60_000) {
					executor.execute(() -> {
						n.flush();
					});
					toRemove.add(n);
				}
			}
			for (Node n : toRemove)
				dirtyNodes.remove(n);
		}
	}

	private void update() {
		flush();
		System.out.println("updating "+accountName);
		boolean changed = false;
		try {
			Changes.List req = service.changes().list()
					.setStartChangeId(nextChangeId).setIncludeDeleted(true)
					.setIncludeSubscribed(false).setMaxResults(100);
			ChangeList cl = req.execute();
			for (Change c : cl.getItems()) {
				changed = true;
				GYMain.setStatus("Change for " + c.getFileId() + " @ "
						+ c.getModificationDate());
				if (c.getDeleted()) {
					System.out.println("deleted");
					// delete the file
					Node n = findNode(c.getFileId());
					if (n == null) {
						GYMain.setStatus("ERROR UNKNOWN FILEID " + c);
					} else {
						GYMain.setStatus(n.getPath());
						synchronized (n) {
							java.io.File cachefile = n.cacheFile();
							if (cachefile.exists())
								cachefile.delete();
							for (Node parent : n.parents) {
								parent.children.remove(n);
							}
						}
					}
				} else {
					// update
					Node n = findNode(c.getFileId());
					if (n == null) {
						GYMain.setStatus("new file");
						File f = c.getFile();
						Node newn = new Node(f.getTitle(), f.getId(),
								f.getMimeType(),
								f.getModifiedDate() == null ? 0 : f
										.getModifiedDate().getValue(),
								f.getFileSize() == null ? 0 : f.getFileSize()
										.longValue(), f.getEtag(),
								f.getAlternateLink(), this);
						for (ParentReference pr : f.getParents()) {
							Node parent = findNode(pr.getId());
							if (parent != null) {
								parent.children.add(newn);
								newn.parents.add(parent);
								GYMain.setStatus(newn.getPath());
							}
						}
					} else {
						GYMain.setStatus("updated");
						GYMain.setStatus(n.getPath());
						synchronized (n) {
							File f = c.getFile();
							if (n.lastModified != f.getModifiedDate()
									.getValue()) {
								// TODO check md5
								GYMain.setStatus("changed " + n.lastModified
										+ " " + f.getModifiedDate().getValue());
								java.io.File cachefile = n.cacheFile();
								if (cachefile.exists())
									cachefile.delete();
							}

							ArrayList<Node> parents = new ArrayList<Node>();
							for (ParentReference pr : f.getParents()) {
								Node p = findNode(pr.getId());
								if (p == null) {
									GYMain.setStatus("ERROR parent not found "
											+ pr.getId());
								} else {
									parents.add(p);

								}
							}

							ArrayList<Node> removedParents = new ArrayList<Node>(
									n.parents);
							removedParents.removeAll(parents);
							for (Node r : removedParents) {
								n.parents.remove(r);
								r.children.remove(n);
							}
							for (Node p : parents) {
								if (!n.parents.contains(p)) {
									p.children.add(n);
									n.parents.add(p);
								}
							}

							n.name = f.getTitle();
							if (n.cached != CacheStatus.Dirty) {
								n.lastModified = f.getModifiedDate().getValue();
								n.size = (f.getFileSize() != null) ? f
										.getFileSize().longValue() : 0;
								n.etag = f.getEtag();
							}
						}
					}
				}
				nextChangeId = c.getId() + 1;
			}
			if (changed)
				requestPersist();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		System.out.println("updating done "+accountName);
		if (persistRequested) {
			persistRequested = false;
			doPersist();
		}
	}

	private Node findNode(String fileid) {
		return findNode(root, fileid);
	}

	private Node findNode(Node n, String fileid) {
		if (n.id.equals(fileid))
			return n;
		if (n.children != null) {
			for (Node c : n.children) {
				Node r = findNode(c, fileid);
				if (r != null)
					return r;
			}
		}
		return null;
	}

	HashMap<Long, WeakReference<Node>> filehandles = new HashMap<Long, WeakReference<Node>>();

	int lastFileHandle = 1;

	public synchronized Node findPath(String s, FileInfoWrapper fiw) {
		if (fiw != null && fiw.fh() > 0) {
			WeakReference<Node> wr = filehandles.get(fiw.fh());
			if (wr != null) {
				Node n = wr.get();
				if (n == null) {
					filehandles.remove(wr);
					fiw.fh(0);
				} else {
					return n;
				}
			}
		}
		Node n = null;
		if (s.equals("/") || s.isEmpty())
			n = root;
		if (n == null)
			n = findPath(root, s);
		if (n != null) {
			long fh = lastFileHandle++;
			filehandles.put(fh, new WeakReference<Node>(n));
			if (fiw != null)
				fiw.fh(fh);
		}
		return n;
	}

	private synchronized Node findPath(Node n, String s) {
		if (s.startsWith("/"))
			s = s.substring(1);
		String parts[] = s.split("/", 2);
		if (parts.length == 1) {
			for (Node c : n.children) {
				if (c.getFileName().equals(s))
					return c;
			}
		} else {
			for (Node c : n.children) {
				if (c.getFileName().equals(parts[0]))
					return findPath(c, parts[1]);
			}
		}
		return null;
	}

	private void markDirtyNodes(Node n) {
		n.gd = this;
		if (n.cached == CacheStatus.Dirty)
			addToDirtyNodes(n);
		if (n.children != null) {
			for (Node c : n.children) {
				markDirtyNodes(c);
			}
		}
	}

	private void initChildren(Node n, ListMultimap<String, Node> parentMap) {
		for (Node c : parentMap.get(n.id)) {
			try {
				n.children.add(c);
				c.parents.add(n);
				if (n.mimetype == Node.folderType)
					initChildren(c, parentMap);
			} catch (Throwable t) {
				System.err.println(c.id);
				t.printStackTrace();
			}
		}
	}

	private static List<File> retrieveAllFiles(Drive service)
			throws IOException, InterruptedException {
		List<File> result = new ArrayList<File>();
		Files.List request = service
				.files()
				.list()
				.setQ("trashed=false")
				.setFields(
						"items(etag,fileSize,id,md5Checksum,mimeType,modifiedDate,alternateLink,parents/id,title),"
								+ "nextPageToken").setMaxResults(1000);
		int retry = 0;
		do {
			try {
				FileList files = request.execute();

				result.addAll(files.getItems());
				GYMain.setStatus("" + result.size() + " files");
				request.setPageToken(files.getNextPageToken());
				retry = 0;
			} catch (IOException e) {
				System.out.println("An error occurred: " + e);
				if (retry > 3)
					request.setPageToken(null);
				else {
					retry++;
					Thread.sleep(5000 * retry);
				}
			}
		} while (request.getPageToken() != null
				&& request.getPageToken().length() > 0);
		GYMain.setStatus("done");
		return result;
	}

	ExecutorService cacheExecutorService = Executors.newSingleThreadExecutor();

	public void startCaching(Node n) {
		cacheExecutorService.submit(() -> n.cache());
	}

	public void createDir(Node parent, String name) {
		synchronized (this) {
			try {
				String newid = service
						.files()
						.insert(new File()
								.setMimeType(Node.folderType)
								.setTitle(name)
								.setParents(
										Collections
												.singletonList(new ParentReference()
														.setId(parent.id))))
						.execute().getId();
				Node n = new Node(name, newid, Node.folderType,
						System.currentTimeMillis(), 0, "", null, this);
				parent.children.add(n);
				n.parents.add(parent);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public synchronized void createFile(Node parent, String name) {
		Node n = new Node(name, "tobefilled-" + UUID.randomUUID().toString(),
				"", System.currentTimeMillis(), 0, "", null, this);
		n.parents.add(parent);
		java.io.File f = n.cacheFile();
		try {
			new RandomAccessFile(f, "rw").close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		n.markDirty();
		synchronized (parent) {
			parent.children.add(n);
		}
	}

	void requestPersist() {
		persistRequested = true;
	}

	HashSet<Node> dirtyNodes = new HashSet<Node>();

	void addToDirtyNodes(Node n) {
		synchronized (dirtyNodes) {
			dirtyNodes.add(n);
			requestPersist();
		}
	}

	public void addAction(Action action) {
		action.run(this);
	}
}
