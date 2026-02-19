package net.krows_team.data_mod;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class StreamSender extends Thread {
	private final String host;
	private final int port;

	private final BlockingQueue<byte[]> q = new LinkedBlockingQueue<>(); // чтобы не копить лаг
	private volatile boolean running = true;

	public StreamSender(String host, int port) {
		super("MyMod-StreamSender");
		setDaemon(true);
		this.host = host;
		this.port = port;
	}

	public void enqueue(byte[] packet) {
		// если очередь заполнена — выкидываем самый старый, чтобы “держаться рядом с
		// realtime”
		try {
			q.put(packet); // блокируется если поток не успевает
		} catch (InterruptedException ignored) {
		}
	}

	public void shutdown() {
		running = false;
		interrupt();
	}

	@Override
	public void run() {
		Socket sock = null;
		DataOutputStream out = null;

		while (running) try {
			if (sock == null || sock.isClosed() || !sock.isConnected()) {
				closeQuiet(sock);
				sock = new Socket();
				sock.setTcpNoDelay(true);
				sock.connect(new InetSocketAddress(host, port), 2000);
				out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 1 << 16));
			}

			byte[] packet = q.take();
			out.writeInt(packet.length);
			out.write(packet);
			out.flush();
		} catch (InterruptedException ie) {
			// выходим/переподключаемся
		} catch (Exception e) {
			// ошибка сети — переподключимся
			closeQuiet(sock);
			sock = null;
			out = null;

			try {
				Thread.sleep(500);
			} catch (InterruptedException ignored) {
			}
		}

		closeQuiet(sock);
	}

	private static void closeQuiet(Socket s) {
		try {
			if (s != null) s.close();
		} catch (Exception ignored) {
		}
	}
}
