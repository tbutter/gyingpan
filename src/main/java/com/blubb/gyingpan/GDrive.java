package com.blubb.gyingpan;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
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
	private final static String CLIENT_ID = "986587539645-1keh36kli6put6n2a3k1vfl8k985iopi.apps.googleusercontent.com";
	private final static String CLIENT_SECRET = "kdIeEEs5oKjMyaJgf3IFAScz";
	private final static String REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";
	Node root = null;
	private final java.io.File jdrivedir;
	final java.io.File cachedir;
	Drive service;
	long nextChangeId = 0;
	boolean persistRequested = false;
	ScheduledExecutorService executor = Executors
			.newSingleThreadScheduledExecutor();

	GDrive(String username) throws IOException, InterruptedException {
		jdrivedir = new java.io.File(new java.io.File(new java.io.File(
				System.getProperty("user.home")), ".gyingpan"), username);
		jdrivedir.mkdirs();
		cachedir = new java.io.File(jdrivedir, "cache");
		cachedir.mkdirs();
		for (int i = 0; i <= 255; i++) {
			new java.io.File(cachedir, Integer.toHexString(i)).mkdirs();
		}
		HttpTransport httpTransport = new NetHttpTransport();
		JsonFactory jsonFactory = new JacksonFactory();

		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				httpTransport, jsonFactory, CLIENT_ID, CLIENT_SECRET,
				Arrays.asList(DriveScopes.DRIVE))
				.setAccessType("offline")
				.setApprovalPrompt("auto")
				.setDataStoreFactory(
						new FileDataStoreFactory(new java.io.File(jdrivedir, "driveauth")))
				.build();
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

		// Create a new authorized API client
		service = new Drive.Builder(httpTransport, jsonFactory, credential)
				.build();
		try {
			ObjectInputStream fis = new ObjectInputStream(new FileInputStream(
					new java.io.File(jdrivedir, "filecache.db")));
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
		System.out.println("rootFolder ID " + rootFolder);
		long time = System.currentTimeMillis();
		List<File> files = retrieveAllFiles(service);
		System.out.println("time " + (System.currentTimeMillis() - time));
		// finding parents
		int folderCount = 0;
		int fileCount = 0;
		ListMultimap<String, File> parentMap = ArrayListMultimap.create();
		root = new Node("", rootFolder, "", Node.folderType, 0, 0,
				"rootFolderEtag", null, null, this);
		for (File f : files) {
			if (f.getMimeType().equals("application/vnd.google-apps.folder")) {
				folderCount++;
			} else {
				fileCount++;
			}
			for (ParentReference pr : f.getParents()) {
				parentMap.put(pr.getId(), f);
			}
		}
		System.out.println();
		initChildren(root, parentMap);
		System.out.println("time " + (System.currentTimeMillis() - time));
		System.out.println("folders: " + folderCount);
		System.out.println("files: " + fileCount);
		// showFolder("", root);
		requestPersist();
	}

	void doPersist() {
		synchronized (this) {
			ObjectOutputStream fos;
			try {
				fos = new ObjectOutputStream(new FileOutputStream(
						new java.io.File(jdrivedir, "filecache.db")));
				fos.writeLong(nextChangeId);
				fos.writeObject(root);
				fos.close();
				System.out.println("saved");
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
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
		System.out.println("updating");
		boolean changed = false;
		try {
			Changes.List req = service.changes().list()
					.setStartChangeId(nextChangeId).setIncludeDeleted(true)
					.setIncludeSubscribed(false).setMaxResults(100);
			ChangeList cl = req.execute();
			for (Change c : cl.getItems()) {
				changed = true;
				System.out.println("Change for " + c.getFileId() + " @ "
						+ c.getModificationDate());
				if (c.getDeleted()) {
					System.out.println("deleted");
					// delete the file
					Node n = findNode(c.getFileId());
					if (n == null) {
						System.out.println("ERROR UNKNOWN FILEID " + c);
					} else {
						System.out.println(n.getPath());
						synchronized (n) {
							java.io.File cachefile = n.cacheFile();
							if (cachefile.exists())
								cachefile.delete();
							Node parent = n.parent;
							if (parent != null)
								parent.children.remove(n);
							n.parent = null;
						}
					}
				} else {
					// update
					Node n = findNode(c.getFileId());
					if (n == null) {
						System.out.println("new file");
						File f = c.getFile();
						for (ParentReference pr : f.getParents()) {
							Node parent = findNode(pr.getId());
							if (parent != null) {
								Node newn = new Node(f.getTitle(), f.getId(),
										f.getMd5Checksum(), f.getMimeType(),
										f.getModifiedDate() == null ? 0 : f
												.getModifiedDate().getValue(),
										f.getFileSize() == null ? 0 : f
												.getFileSize().longValue(),
										f.getEtag(), f.getAlternateLink(),
										parent, this);
								parent.children.add(newn);
								System.out.println(newn.getPath());
							}
						}
					} else {
						System.out.println("updated");
						System.out.println(n.getPath());
						synchronized (n) {
							File f = c.getFile();
							if (n.lastModified != f.getModifiedDate()
									.getValue()) {
								// TODO check md5
								System.out.println("changed " + n.lastModified
										+ " " + f.getModifiedDate().getValue());
								java.io.File cachefile = n.cacheFile();
								if (cachefile.exists())
									cachefile.delete();
							}
							Node oldparent = n.parent;
							if (oldparent != null)
								oldparent.children.remove(n);
							n.parent = null;
							// create the new node
							for (ParentReference pr : f.getParents()) {
								Node parent = findNode(pr.getId());
								Node newn = new Node(f.getTitle(), f.getId(),
										f.getMd5Checksum(), f.getMimeType(),
										f.getModifiedDate() == null ? 0 : f
												.getModifiedDate().getValue(),
										f.getFileSize() == null ? 0 : f
												.getFileSize().longValue(),
										f.getEtag(), f.getAlternateLink(),
										parent, this);
								newn.children = n.children;
								parent.children.add(newn);
							}
						}
					}
				}
				nextChangeId = c.getId() + 1;
			}
			if (changed)
				requestPersist();
		} catch (IOException e) {
			e.printStackTrace();
		}
		if(persistRequested) {
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

	public void removeDirectory(Node n) {
		try {
			synchronized (n) {
				service.files().trash(n.id).execute();
				synchronized (n.parent) {
					synchronized (this) {
						n.parent.children.remove(n);
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void removeFile(Node n) {
		try {
			synchronized (n) {
				service.files().trash(n.id).execute();
				synchronized (n.parent) {
					synchronized (this) {
						n.parent.children.remove(n);
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Node findPath(String s) {
		if (s.equals("/") || s.isEmpty())
			return root;
		return findPath(root, s);
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

	private void initChildren(Node n, ListMultimap<String, File> parentMap) {
		for (File f : parentMap.get(n.id)) {
			try {
				Node c = new Node(f.getTitle(), f.getId(), f.getMd5Checksum(),
						f.getMimeType(), f.getModifiedDate() == null ? 0 : f
								.getModifiedDate().getValue(),
						f.getFileSize() == null ? 0 : f.getFileSize()
								.longValue(), f.getEtag(),
						f.getAlternateLink(), n, this);
				n.children.add(c);
				if (n.mimetype == Node.folderType)
					initChildren(c, parentMap);
			} catch (Throwable t) {
				System.out.println(f);
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
				System.out.println(result.size() + " files\n");
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
		System.out.println();
		return result;
	}

	ExecutorService cacheExecutorService = Executors.newSingleThreadExecutor();

	public void startCaching(Node n) {
		cacheExecutorService.submit(() -> n.cache());
	}

	public void rename(Node n, String toName) {
		System.out.println("rename " + n.getPath() + " " + toName);
		try {
			synchronized (n) {
				if (n.cached != CacheStatus.Dirty) {
					File f = service.files().get(n.id).execute();
					f.setTitle(toName);
					service.files().patch(n.id, f).execute();
				}
				n.setName(toName);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void move(Node n, Node toParent) {
		System.out.println("move " + n.getPath() + " " + toParent.getPath());
		try {
			synchronized (n) {
				Node oldParent = n.parent;
				if (n.cached != CacheStatus.Dirty) {
					File f = service.files().get(n.id).execute();
					service.files().patch(n.id, f)
							.setRemoveParents(oldParent.id)
							.setAddParents(toParent.id).execute();
				}
				synchronized (this) {
					oldParent.children.remove(n);
					toParent.children.add(n);
					n.parent = toParent;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void createDir(Node parent, String name) {
		synchronized (parent) {
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
				parent.children.add(new Node(name, newid, "", Node.folderType,
						System.currentTimeMillis(), 0, "", null, parent, this));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void createFile(Node parent, String name) {
		Node n = new Node(name, "tobefilled-" + UUID.randomUUID().toString(),
				"", "", System.currentTimeMillis(), 0, "", null, parent, this);
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
}
