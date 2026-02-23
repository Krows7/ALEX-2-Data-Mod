package net.krows_team.data_mod;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for managing a local SSH port-forwarding process.
 */
public class SshTunnelManager {
	private static Process process;
	private static int localPort = -1;

	private SshTunnelManager() {}

	/**
	 * Opens SSH local forward and returns allocated local port.
	 *
	 * @param sshHost SSH host
	 * @param sshPort SSH port
	 * @param sshUser SSH username
	 * @param keyPath path to private key
	 * @param remoteHost remote target host reachable from SSH server
	 * @param remotePort remote target port reachable from SSH server
	 * @return local forwarded port on {@code 127.0.0.1}
	 * @throws Exception if process launch or tunnel establishment fails
	 */
	public static synchronized int openTunnel(String sshHost, int sshPort, String sshUser, String keyPath, String remoteHost, int remotePort) throws Exception {
		closeTunnel();

		String identity = expandHome(keyPath);
		int chosenLocalPort = pickFreeLocalPort();
		List<String> cmd = new ArrayList<>();
		cmd.add("ssh");
		cmd.add("-o");
		cmd.add("BatchMode=yes");
		cmd.add("-o");
		cmd.add("ExitOnForwardFailure=yes");
		cmd.add("-o");
		cmd.add("StrictHostKeyChecking=no");
		cmd.add("-o");
		cmd.add("IdentitiesOnly=yes");
		cmd.add("-i");
		cmd.add(identity);
		cmd.add("-p");
		cmd.add(String.valueOf(sshPort));
		cmd.add("-N");
		cmd.add("-L");
		cmd.add(chosenLocalPort + ":" + remoteHost + ":" + remotePort);
		cmd.add(sshUser + "@" + sshHost);

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		Process p = pb.start();

		String waitErr = waitUntilForwardReady(p, chosenLocalPort, 5000);
		if (waitErr != null) {
			try {
				p.destroyForcibly();
			} catch (Exception ignored) {
			}
			throw new Exception(waitErr);
		}

		process = p;
		localPort = chosenLocalPort;
		return chosenLocalPort;
	}

	/**
	 * Closes current SSH tunnel process if it exists.
	 */
	public static synchronized void closeTunnel() {
		if (process != null) {
			try {
				process.destroy();
				if (process.isAlive()) process.destroyForcibly();
			} catch (Exception ignored) {
			}
		}
		process = null;
		localPort = -1;
	}

	/**
	 * @return {@code true} when tunnel process exists and is alive
	 */
	public static synchronized boolean isActive() {
		return process != null && process.isAlive();
	}

	/**
	 * @return local forwarded port or {@code -1} when tunnel is inactive
	 */
	public static synchronized int getLocalPort() {
		return localPort;
	}

	private static String waitUntilForwardReady(Process p, int localPort, int timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (!p.isAlive()) return readProcessOutput(p, "SSH process exited");
			if (isPortOpen(localPort)) return null;
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
			}
		}
		if (!p.isAlive()) return readProcessOutput(p, "SSH process exited");
		return "SSH tunnel timeout: local forward did not start";
	}

	private static boolean isPortOpen(int port) {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", port), 150);
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}

	private static String readProcessOutput(Process p, String prefix) {
		try (InputStream in = p.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			byte[] buf = new byte[1024];
			int n;
			while ((n = in.read(buf)) != -1) {
				baos.write(buf, 0, n);
			}
			byte[] data = baos.toByteArray();
			String out = new String(data, StandardCharsets.UTF_8).trim();
			if (out.isEmpty()) return prefix;
			return prefix + ": " + out;
		} catch (Exception ignored) {
			return prefix;
		}
	}

	private static int pickFreeLocalPort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}

	private static String expandHome(String path) {
		if (path == null) return "";
		String p = path.trim();
		if (p.startsWith("~/")) {
			return new File(System.getProperty("user.home"), p.substring(2)).getPath();
		}
		return p;
	}
}
