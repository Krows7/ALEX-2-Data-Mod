package net.krows_team.data_mod;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background TCP sender that streams encoded observation packets to the server.
 */
public class StreamSender extends Thread {
	private final String host;
	private final int port;
	private final int flushEveryN;

	private final BlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
	private volatile boolean running = true;
	private final AtomicLong enqueuedPackets = new AtomicLong(0);
	private final AtomicLong sentPackets = new AtomicLong(0);

	/**
	 * Creates sender thread for a target endpoint.
	 *
	 * @param host server host
	 * @param port server port
	 */
	public StreamSender(String host, int port) {
		super("MyMod-StreamSender");
		setDaemon(true);
		this.host = host;
		this.port = port;
		flushEveryN = readFlushEveryN();
	}

	/**
	 * Enqueues packet data for asynchronous sending.
	 *
	 * @param packet encoded packet bytes
	 */
	public void enqueue(byte[] packet) {
		try {
			q.put(packet);
			enqueuedPackets.incrementAndGet();
		} catch (InterruptedException ignored) {
		}
	}

	/**
	 * @return current number of buffered packets in queue
	 */
	public int getQueueSize() {
		return q.size();
	}

	/**
	 * @return total number of packets accepted into queue
	 */
	public long getEnqueuedPackets() {
		return enqueuedPackets.get();
	}

	/**
	 * @return total number of packets successfully written to socket
	 */
	public long getSentPackets() {
		return sentPackets.get();
	}

	/**
	 * Stops sender loop and interrupts blocking queue operations.
	 */
	public void shutdown() {
		running = false;
		interrupt();
	}

	/**
	 * Main reconnecting send loop.
	 */
	@Override
	public void run() {
		Socket sock = null;
		DataOutputStream out = null;
		int pendingFlush = 0;

		while (running) try {
			if (sock == null || sock.isClosed() || !sock.isConnected()) {
				closeQuiet(sock);
				sock = new Socket();
				sock.setTcpNoDelay(true);
				sock.connect(new InetSocketAddress(host, port), 2000);
				out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 1 << 16));
				pendingFlush = 0;
			}

			byte[] packet = q.take();
			out.writeInt(packet.length);
			out.write(packet);
			pendingFlush++;
			if (pendingFlush >= flushEveryN || q.isEmpty()) {
				out.flush();
				pendingFlush = 0;
			}
			sentPackets.incrementAndGet();
		} catch (InterruptedException ie) {
		} catch (Exception e) {
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

	private static int readFlushEveryN() {
		try {
			int v = ClientConfig.FLUSH_EVERY_N.get();
			if (v < 1) return 1;
			return Math.min(v, 1024);
		} catch (Exception ignored) {
			return 1;
		}
	}
}
