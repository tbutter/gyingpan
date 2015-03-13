package com.blubb.gyingpan.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;

import com.blubb.gyingpan.GDrive;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;

public class UploadDir {

	public static void main(String[] args) throws IOException {
		if (args.length != 3) {
			System.out.println("UploadDirs accountname folderid folder");
			return;
		}
		String folderid = args[1];
		String account = args[0];
		String folder = args[2];
		java.io.File jdrivedir = new java.io.File(
				new java.io.File(new java.io.File(
						System.getProperty("user.home")), ".gyingpan"), account);
		jdrivedir.mkdirs();
		HttpTransport httpTransport = new NetHttpTransport();
		JsonFactory jsonFactory = new JacksonFactory();

		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				httpTransport, jsonFactory, GDrive.CLIENT_ID,
				GDrive.CLIENT_SECRET, Arrays.asList(DriveScopes.DRIVE))
				.setAccessType("offline")
				.setApprovalPrompt("auto")
				.setDataStoreFactory(
						new FileDataStoreFactory(new java.io.File(jdrivedir,
								"driveauth"))).build();
		Credential credential = flow.loadCredential(args[0]);
		Drive service = new Drive.Builder(httpTransport, jsonFactory,
				credential).setHttpRequestInitializer(
				new HttpRequestInitializer() {
					@Override
					public void initialize(HttpRequest request)
							throws IOException {
						credential.initialize(request);
						request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(
								new ExponentialBackOff()));
						request.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(
								new ExponentialBackOff()));
					}
				}).build();
		HashMap<String, File> gfilelist = getGDriveFileList(service, folderid);
		uploadDir(service, gfilelist, folderid,
				new java.io.File(folder).toPath());
	}

	private static void uploadDir(Drive service,
			HashMap<String, File> gfilelist, String folderid, Path file) throws IOException {
		System.out.println("file.getNameCount() = "+file.getNameCount());
		java.nio.file.Files.walk(file).sorted(new Comparator<Path>() {

			@Override
			public int compare(Path o1, Path o2) {
				long size1 = 0;
				try {
					size1 = java.nio.file.Files.size(o1);
				} catch (IOException e) {
					e.printStackTrace();
				}
				long size2 = 0;
				try {
					size2 = java.nio.file.Files.size(o2);
				} catch (IOException e) {
					e.printStackTrace();
				}
				if(size1 == size2) return 0;
				if(size1 < size2) return -1;
				return 1;
			}
		}).forEach((f) -> {
			if(f.equals(file)) return; // ignore self
			Path subPath = f.subpath(file.getNameCount(), f.getNameCount());
			String currentPath = subPath.toString();
			if(java.nio.file.Files.isDirectory(f)) return;
			if (gfilelist.containsKey(currentPath)) {
				try {
					if (java.nio.file.Files.size(f) != gfilelist
							.get(currentPath).getFileSize()) {
						System.out.println(currentPath
								+ " has wrong size");
						gfilelist.remove(currentPath);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			if (gfilelist.containsKey(currentPath)) {
				System.out.println(currentPath
						+ " already exists");
			} else {
				System.out.println(subPath);
				insertGDriveFile(
						service,
						f.getFileName().toString(),
						null,
						subPath.getNameCount() == 1 ? folderid :
						getGDriveFolderID(folderid, subPath.subpath(0, subPath.getNameCount()-1).toString(),
								gfilelist, service), null,
						f.toFile());
			}
		});
	}

	private static File insertGDriveFile(Drive service, String title,
			String description, String parentId, String mimeType,
			java.io.File fileContent) {
		System.out.println("insertGDriveFile " + title + " parent:" + parentId);
		DecimalFormat sizeFormat = new DecimalFormat("###,###");
		if(fileContent != null) System.out.println("size : "+sizeFormat.format(fileContent.length()));
		File body = new File();
		body.setTitle(title);
		if (description != null)
			body.setDescription(description);
		if (mimeType != null)
			body.setMimeType(mimeType);

		// Set the parent folder.
		if (parentId != null && parentId.length() > 0) {
			body.setParents(Arrays.asList(new ParentReference().setId(parentId)));
		}

		// File's content.
		FileContent mediaContent = fileContent != null ? new FileContent(
				mimeType, fileContent) : null;
		try {
			File file = null;
			Drive.Files.Insert insert = mediaContent != null ? service.files()
					.insert(body, mediaContent) : service.files().insert(body);
					final long startTime = System.currentTimeMillis();
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
									System.out.printf("%s progress %.2f%% (eta %ds)\n", title, uploader.getProgress()*100, (int)eta/1000);
								}
							}
						});
			file = insert.execute();
			return file;
		} catch (GoogleJsonResponseException e) {
			// GoogleJsonError error = e.getDetails();
			e.printStackTrace();
			return null;
		} catch (HttpResponseException e) {
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static String getGDriveFolderID(String rootID, String path,
			HashMap<String, File> gdriveFileList, Drive drive) {
		if (path == null || path.isEmpty())
			return rootID;
		if (gdriveFileList.containsKey(path))
			return gdriveFileList.get(path).getId();
		String folderID = rootID;
		String[] folders = path.split("/");
		String subPath = "";
		int count = 0;
		do {
			subPath += (subPath.isEmpty() ? "" : "/") + folders[count];
			if (gdriveFileList.containsKey(subPath))
				folderID = gdriveFileList.get(subPath).getId();
			else {
				File newFolder = insertGDriveFile(drive, folders[count], null,
						folderID, "application/vnd.google-apps.folder", null);
				gdriveFileList.put(subPath, newFolder);
				folderID = newFolder.getId();
			}
			count++;
		} while (count < folders.length);

		return folderID;
	}

	private static HashMap<String, File> getGDriveFileList(Drive drive,
			String folderID) {
		ForkJoinPool pool = new ForkJoinPool(4);
		HashMap<String, File> ret = pool.invoke(getGDriveFileListTask(drive,
				folderID, ""));
		pool.shutdown();
		return ret;
	}

	@SuppressWarnings("serial")
	private static ForkJoinTask<HashMap<String, File>> getGDriveFileListTask(
			final Drive drive, final String fileId, final String path) {
		return new RecursiveTask<HashMap<String, File>>() {

			@Override
			protected HashMap<String, File> compute() {
				try {
					System.out.println("getGDriveFileList " + path);
					LinkedHashMap<String, File> fileList = new LinkedHashMap<String, File>();
					LinkedList<File> subFolders = new LinkedList<File>();
					Files.List request = drive
							.files()
							.list()
							.setQ("'" + fileId
									+ "' in parents and trashed=false")
							.setMaxResults(1000);
					int retry = 0;
					do {
						try {
							FileList files = request.execute();
							for (File file : files.getItems()) {
								String filePath = path + file.getTitle();
								if (file.getMimeType().equals(
										"application/vnd.google-apps.folder")) {
									subFolders.add(file);
									fileList.put(filePath, file);
								} else if (file.getMd5Checksum() != null) {
									fileList.put(filePath, file);
								}
							}
							request.setPageToken(files.getNextPageToken());
							retry = 0;
						} catch (IOException e) {
							e.printStackTrace();
							if (retry > 3) {
								throw e;
							}
							retry++;
							Thread.sleep(5000 * retry);
						}
					} while (request.getPageToken() != null
							&& request.getPageToken().length() > 0);

					// recurse into subdirs
					ArrayList<ForkJoinTask<HashMap<String, File>>> fileCallables = new ArrayList<>();
					for (File file : subFolders) {
						String subdirPath = path + file.getTitle() + "/";
						fileCallables.add(getGDriveFileListTask(drive,
								file.getId(), subdirPath));

					}
					invokeAll(fileCallables);
					for (ForkJoinTask<HashMap<String, File>> f : fileCallables) {
						HashMap<String, File> subdirFiles = f.get();
						if (subdirFiles != null)
							fileList.putAll(subdirFiles);
						else
							throw new IOException(
									"could not get gdrive filelist");
					}
					return fileList;
				} catch (Exception e) {
					e.printStackTrace();
					return null;
				}
			}
		};
	}
}
