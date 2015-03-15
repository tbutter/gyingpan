package com.blubb.gyingpan;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class NoGCMSslSocketFactory extends SSLSocketFactory {

	SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
	
	@Override
	public Socket createSocket(Socket arg0, String arg1, int arg2, boolean arg3)
			throws IOException {
		Socket s = factory.createSocket(arg0, arg1, arg2, arg3);
		removeGCM(s);
		return s;
	}

	private static void removeGCM(Socket sock) {
		SSLSocket socket = (SSLSocket)sock;
		String[] available = socket.getEnabledCipherSuites();
		ArrayList<String> allowed = new ArrayList<String>();
		for(String s : available) {
			if(s.contains("_GCM_")) { // ignore
			} else {
				allowed.add(s);
			}
		}
		socket.setEnabledCipherSuites(allowed.toArray(new String[0]));
	}

	@Override
	public String[] getDefaultCipherSuites() {
		return factory.getDefaultCipherSuites();
	}

	@Override
	public String[] getSupportedCipherSuites() {
		return factory.getSupportedCipherSuites();
	}

	@Override
	public Socket createSocket(String arg0, int arg1) throws IOException,
			UnknownHostException {
		Socket s = factory.createSocket(arg0, arg1);
		removeGCM(s);
		return s;
	}

	@Override
	public Socket createSocket(InetAddress arg0, int arg1) throws IOException {
		Socket s = factory.createSocket(arg0, arg1);
		removeGCM(s);
		return s;
	}

	@Override
	public Socket createSocket(String arg0, int arg1, InetAddress arg2, int arg3)
			throws IOException, UnknownHostException {
		Socket s = factory.createSocket(arg0, arg1, arg2, arg3);
		removeGCM(s);
		return s;
	}

	@Override
	public Socket createSocket(InetAddress arg0, int arg1, InetAddress arg2,
			int arg3) throws IOException {
		Socket s = factory.createSocket(arg0, arg1, arg2, arg3);
		removeGCM(s);
		return s;
	}

}
