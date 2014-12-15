package com.blubb.gyingpan;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import net.fusejna.DirectoryFiller;
import net.fusejna.ErrorCodes;
import net.fusejna.FlockCommand;
import net.fusejna.FuseFilesystem;
import net.fusejna.StructFlock.FlockWrapper;
import net.fusejna.StructFuseFileInfo.FileInfoWrapper;
import net.fusejna.StructStat.StatWrapper;
import net.fusejna.StructStatvfs.StatvfsWrapper;
import net.fusejna.StructTimeBuffer.TimeBufferWrapper;
import net.fusejna.XattrFiller;
import net.fusejna.XattrListFiller;
import net.fusejna.types.TypeMode.ModeWrapper;
import net.fusejna.types.TypeMode.NodeType;

import com.sun.jna.Platform;

public class FuseFS extends FuseFilesystem {
	GDrive gdrive;

	public FuseFS(GDrive g) {
		this.gdrive = g;
		//log(true);
	}

	@Override
	public int access(String path, int access) {
		return 0;
	}

	@Override
	public void afterUnmount(File mountPoint) {
		gdrive.doPersist();
	}

	@Override
	public void beforeMount(File mountPoint) {
		// empty
	}

	@Override
	public int bmap(String path, FileInfoWrapper info) {
		return -ErrorCodes.firstNonNull(ErrorCodes.ENOTSUP(), ErrorCodes.ENOSYS());
	}

	@Override
	public int chmod(String path, ModeWrapper mode) {
		return 0;
	}

	@Override
	public int chown(String path, long uid, long gid) {
		return 0;
	}

	@Override
	public int create(String path, ModeWrapper mode, FileInfoWrapper info) {
		if(gdrive.findPath(path, null) != null) return -ErrorCodes.EEXIST();
		String parentname = path.substring(0, path.lastIndexOf('/'));
		String name = path.substring(path.lastIndexOf('/') + 1);
		Node parent = gdrive.findPath(parentname, null);
		if (parent == null)
			return -ErrorCodes.ENOENT();
		gdrive.createFile(parent, name);
		return 0;
	}

	@Override
	public void destroy() {

	}

	@Override
	public int fgetattr(String path, StatWrapper stat, FileInfoWrapper info) {
		return getattr(path, stat);
	}

	@Override
	public int flush(String path, FileInfoWrapper info) {
		return 0;
	}

	@Override
	public int fsync(String path, int datasync, FileInfoWrapper info) {
		return 0;
	}

	@Override
	public int fsyncdir(String path, int datasync, FileInfoWrapper info) {
		return 0;
	}

	@Override
	public int ftruncate(String path, long offset, FileInfoWrapper info) {
		return truncate(path, offset);
	}

	@Override
	public int getattr(String path, StatWrapper stat) {
		Node n = gdrive.findPath(path, null);
		if (n == null)
			return -ErrorCodes.ENOENT();
		stat.setAllTimesMillis(n.lastModified);
		stat.size(n.getSize());
		if (n.isDirectory())
			stat.setMode(NodeType.DIRECTORY);
		else {
			if (n.isGoogleFile())
				stat.setMode(NodeType.FILE, true, false, false);
			else
				stat.setMode(NodeType.FILE, true, true, false);
		}
		return 0;
	}

	@Override
	protected String getName() {
		return "jdrive";
	}

	@Override
	protected String[] getOptions() {
		if(Platform.isMac()) {
			String opts[] = { "-o", "noappledouble,noapplexattr,iosize=4096" };
			return opts;
		}
		String opts[] = {};
		return opts;
	}

	@Override
	public int getxattr(String path, String xattr, XattrFiller filler,
			long size, long position) {
		return -ErrorCodes.firstNonNull(ErrorCodes.ENOSYS(), ErrorCodes.ENOTSUP());
	}

	@Override
	public void init() {

	}

	@Override
	public int link(String path, String target) {
		return -ErrorCodes.firstNonNull(ErrorCodes.ENOSYS(), ErrorCodes.ENOTSUP());
	}

	@Override
	public int listxattr(String path, XattrListFiller filler) {
		return -ErrorCodes.firstNonNull(ErrorCodes.ENOSYS(), ErrorCodes.ENOTSUP());
	}

	@Override
	public int lock(String path, FileInfoWrapper info, FlockCommand command,
			FlockWrapper flock) {
		return 0;
	}

	@Override
	public int mkdir(String path, ModeWrapper mode) {
		System.out.println("mkdir " + path);
		if(gdrive.findPath(path, null) != null) return -ErrorCodes.EEXIST();
		String parentname = path.substring(0, path.lastIndexOf('/'));
		String name = path.substring(path.lastIndexOf('/') + 1);
		Node parent = gdrive.findPath(parentname, null);
		if (parent == null)
			return -ErrorCodes.ENOENT();
		gdrive.createDir(parent, name);
		return 0;
	}

	@Override
	public int mknod(String path, ModeWrapper mode, long dev) {
		System.out.println("mknod: should not be called");
		return -ErrorCodes.ENOSYS();
	}

	@Override
	public int open(String path, FileInfoWrapper info) {
		GYMain.setStatus("open " + path);
		Node n = gdrive.findPath(path, info);
		if (n != null) {
			if (n.isDirectory())
				return 0;
			// start to cache
			gdrive.startCaching(n);
		} else {
			return -ErrorCodes.ENOENT();
		}
		return 0;
	}

	@Override
	public int opendir(String path, FileInfoWrapper info) {
		return 0;
	}

	@Override
	public int read(String path, ByteBuffer buffer, long size, long offset,
			FileInfoWrapper info) {
		//System.out.println("read " + path + " " + offset + " " + size);
		Node n = gdrive.findPath(path, info);
		if (n.isDirectory())
			return -ErrorCodes.EISDIR();
		try {
			synchronized (n) {
				File f = n.cache();
				RandomAccessFile aFile = new RandomAccessFile(f, "r");
				FileChannel inChannel = aFile.getChannel();
				buffer.limit((int) size);
				int len = inChannel.read(buffer, offset);
				aFile.close();
				if(len < 0) len = 0;
				return len;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return -ErrorCodes.EAGAIN();
		}
	}

	@Override
	public int readdir(String path, DirectoryFiller filler) {
		// System.out.println("readdir(" + path + ")");
		Node n = gdrive.findPath(path, null);
		// filler.add(".", "..");
		for (Node c : n.children) {
			filler.add(c.getFileName());
		}
		return 0;
	}

	@Override
	public int readlink(String path, ByteBuffer buffer, long size) {
		return 0;
	}

	@Override
	public int release(String path, FileInfoWrapper info) {
		return 0;
	}

	@Override
	public int releasedir(String path, FileInfoWrapper info) {
		return 0;
	}

	@Override
	public int removexattr(String path, String xattr) {
		return -ErrorCodes.firstNonNull(ErrorCodes.ENOSYS(), ErrorCodes.ENOTSUP());
	}

	@Override
	public int rename(String path, String newName) {
		System.out.println("rename " + path + " " + newName);
		Node n = gdrive.findPath(path, null);
		if (n == null)
			return -ErrorCodes.ENOENT();
		String fromDir = path.substring(0, path.lastIndexOf('/'));
		String fromName = path.substring(path.lastIndexOf('/') + 1);
		String toDir = newName.substring(0, newName.lastIndexOf('/'));
		String toName = newName.substring(newName.lastIndexOf('/') + 1);
		if (fromDir.equals(toDir)) {
			n.rename(toName);
		} else {
			if (!fromName.equals(toName)) {
				n.rename(toName);
			}
			Node toNode = gdrive.findPath(toDir, null);
			if (toNode == null)
				return -ErrorCodes.ENOENT();
			n.move(gdrive.findPath(fromDir, null), toNode);
		}
		return 0;
	}

	@Override
	public int rmdir(String path) {
		Node n = gdrive.findPath(path, null);
		if (n == null)
			return -ErrorCodes.ENOENT();
		if (n.children != null && n.children.size() > 0)
			return -ErrorCodes.ENOTEMPTY();
		if (!n.isDirectory())
			return -ErrorCodes.ENOTDIR();
		String parentname = path.substring(0, path.lastIndexOf('/'));
		n.delete(gdrive.findPath(parentname, null));
		return 0;
	}

	@Override
	public int setxattr(String path, String xattr, ByteBuffer value, long size,
			int flags, int position) {
		return -ErrorCodes.firstNonNull(ErrorCodes.ENOSYS(), ErrorCodes.ENOTSUP());
	}

	@Override
	public int statfs(String path, StatvfsWrapper wrapper) {
		wrapper.set(1024, 1024, 1024 * 1024 * 1024, 1024 * 1024 * 1024,
				1024 * 1024 * 1024, 100 * 1024, 100 * 1024, 100 * 1024);
		return 0;
	}

	@Override
	public int symlink(String path, String target) {
		return -ErrorCodes.ENOSYS();
	}

	@Override
	public int truncate(String path, long offset) {
		System.out.println("truncate " + path + " " + offset);

		Node n = gdrive.findPath(path, null);
		if (n == null)
			return -ErrorCodes.ENOENT();
		try {
			n.truncate(offset);
		} catch (IOException e) {
			e.printStackTrace();
			return -ErrorCodes.EAGAIN();
		}
		return 0;
	}

	@Override
	public int unlink(String path) {
		Node n = gdrive.findPath(path, null);
		if (n == null)
			return -ErrorCodes.ENOENT();
		if (n.children != null && n.children.size() > 0)
			return -ErrorCodes.ENOTEMPTY();
		if (n.isDirectory())
			return -ErrorCodes.EISDIR();
		String parentname = path.substring(0, path.lastIndexOf('/'));
		n.delete(gdrive.findPath(parentname, null));
		return 0;
	}

	@Override
	public int utimens(String path, TimeBufferWrapper wrapper) {
		Node n = gdrive.findPath(path, null);
		if (n == null)
			return -ErrorCodes.ENOENT();
		wrapper.ac_setMillis(n.lastModified);
		return 0;
	}

	@Override
	public int write(String path, ByteBuffer buf, long bufSize,
			long writeOffset, FileInfoWrapper info) {
		System.out.println("write " + path + " " + writeOffset + " " + bufSize);
		Node n = gdrive.findPath(path, info);
		if(n == null) return -ErrorCodes.ENOENT();
		if (n.isDirectory())
			return -ErrorCodes.EISDIR();
		try {
			synchronized (n) {
				File f = n.cache();
				RandomAccessFile aFile = new RandomAccessFile(f, "rw");
				FileChannel inChannel = aFile.getChannel();
				buf.limit((int)bufSize);
				int len = inChannel.write(buf, writeOffset);
				n.markDirty();
				n.size = aFile.length();
				aFile.close();
				return len;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return -ErrorCodes.EAGAIN();
		}
	}
}
