package net.krows_team.data_mod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.StringTextComponent;

public class MyModConfigScreen extends Screen {
	private final Screen parent;

	private TextFieldWidget addrField;
	private Button saveBtn;

	private String errorText = null;
	private String statusText = null;
	private volatile boolean handshakeInProgress = false;

	private static final int MAGIC_HSK1 = 0x48534B31; // "HSK1"
	private static final int MAGIC_OKAY = 0x4F4B4159; // "OKAY"

	public MyModConfigScreen(Screen parent) {
		super(new StringTextComponent("MyMod Config"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 4;

		addrField = new TextFieldWidget(font, cx - 100, y, 240, 20, new StringTextComponent("URL or host:port"));
		addrField.setMaxLength(256);
		addrField.setValue(ClientConfig.SERVER_ADDR.get());
		addrField.setResponder(this::onAddrChanged);
		children.add(addrField);
		setInitialFocus(addrField);

		onAddrChanged(addrField.getValue());

		saveBtn = this.addButton(new Button(cx - 100, y + 30, 240, 20, new StringTextComponent("Save & Close"), btn -> onSaveClicked()));

		this.addButton(new Button(cx - 100, y + 60, 240, 20, new StringTextComponent("Cancel"), btn -> minecraft.setScreen(parent)));
	}

	private void onSaveClicked() {
		if (handshakeInProgress) return;

		String v = addrField.getValue().trim();
		ParseResult pr = parseAddressOrUrl(v);
		if (!pr.ok) {
			errorText = pr.error;
			statusText = null;
			return;
		}

		errorText = null;
		statusText = pr.tls ? "Connecting (TLS)..." : "Connecting...";
		setUiEnabled(false);
		handshakeInProgress = true;

		new Thread(() -> {
			String fail = null;
			boolean ok = false;
			try {
				ok = doHandshake(pr.host, pr.port, pr.tls, 2000, 2000);
				if (!ok) fail = "Handshake failed (bad reply)";
			} catch (Exception e) {
				fail = "Cannot connect: " + e.getClass().getSimpleName();
			}

			final boolean success = ok;
			final String failMsg = fail;

			Minecraft.getInstance().execute(() -> {
				handshakeInProgress = false;
				setUiEnabled(true);

				if (!success) {
					statusText = null;
					errorText = failMsg != null ? failMsg : "Handshake failed";
					return;
				}

				// сохраняем как ввёл пользователь (URL или host:port)
				ClientConfig.SERVER_ADDR.set(v);
				ClientConfig.SPEC.save();

				statusText = "Saved!";
				minecraft.setScreen(parent);
			});
		}, "MyMod-Handshake").start();
	}

	private void setUiEnabled(boolean enabled) {
		addrField.setEditable(enabled);
		if (saveBtn != null) saveBtn.active = enabled;
	}

	private void onAddrChanged(String newText) {
		ParseResult pr = parseAddressOrUrl(newText.trim());
		errorText = pr.ok ? null : pr.error;
		if (errorText != null) statusText = null;
	}

	/**
	 * Handshake over TCP or TLS. Если tls=true, то сначала TLS handshake, затем
	 * отправляем наш HSK1.
	 */
	private static boolean doHandshake(String host, int port, boolean tls, int connectTimeoutMs, int readTimeoutMs) throws Exception {
		if (!tls) try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			s.setSoTimeout(readTimeoutMs);
			s.setTcpNoDelay(true);

			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			DataInputStream in = new DataInputStream(s.getInputStream());
			return handshakeIO(out, in);
		}
		// TLS
		SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
		try (SSLSocket s = (SSLSocket) factory.createSocket()) {
			s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			s.setSoTimeout(readTimeoutMs);
			s.setTcpNoDelay(true);

			// SNI + корректная валидация сертификата на некоторых JVM
			// (не во всех сборках обязательно, но полезно)
			s.startHandshake();

			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			DataInputStream in = new DataInputStream(s.getInputStream());
			return handshakeIO(out, in);
		}
	}

	private static boolean handshakeIO(DataOutputStream out, DataInputStream in) throws Exception {
		int payloadLen = 4 + 4;
		out.writeInt(payloadLen);
		out.writeInt(MAGIC_HSK1);
		out.writeInt(1);
		out.flush();

		int replyLen = in.readInt();
		if (replyLen != 8) return false;
		int magic = in.readInt();
		int ver = in.readInt();
		return magic == MAGIC_OKAY && ver == 1;
	}

	/**
	 * Принимает: - host:port - URL: http://host[:port], https://host[:port],
	 * tcp://host:port
	 *
	 * ЛОГИКА TLS: - https://... => tls=true, default port 443 - http://... =>
	 * tls=false, default port 80 - tcp://... => tls=false, port обязателен
	 *
	 * Ввод "host:443" НЕ включает TLS автоматически (иначе можно сломать случаи,
	 * когда у тебя на 443 слушает обычный TCP-сервер).
	 */
	private static ParseResult parseAddressOrUrl(String s) {
		if (s == null || s.isEmpty()) return ParseResult.err("Address is empty");

		if (s.contains("://")) try {
			URI uri = new URI(s);
			String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
			String host = uri.getHost();
			int port = uri.getPort(); // -1 если не указан

			if (host == null || host.trim().isEmpty()) return ParseResult.err("URL must include host");

			boolean tls;
			int defaultPort;

			if (scheme != null) {
				switch (scheme) {
				case "https":
					tls = true;
					defaultPort = 443;
					break;
				case "http":
					tls = false;
					defaultPort = 80;
					break;
				case "tcp":
					tls = false;
					defaultPort = -1; // обязателен
					break;
				default:
					return ParseResult.err("Unsupported URL scheme (use http/https/tcp)");
				}
			} else return ParseResult.err("Unsupported URL scheme (use http/https/tcp)");

			if (port == -1) {
				if (defaultPort == -1) return ParseResult.err("URL must include port");
				port = defaultPort;
			}

			host = normalizeHost(host);

			String hostErr = validateHost(host);
			if (hostErr != null) return ParseResult.err(hostErr);

			String portErr = validatePort(port);
			if (portErr != null) return ParseResult.err(portErr);

			return ParseResult.ok(host, port, tls);
		} catch (Exception e) {
			return ParseResult.err("Bad URL");
		}

		// host:port или [ipv6]:port
		String host;
		String portStr;

		if (s.startsWith("[")) {
			int close = s.indexOf(']');
			if (close < 0) return ParseResult.err("Bad IPv6 format, missing ']'");
			if (close + 1 >= s.length() || s.charAt(close + 1) != ':') return ParseResult.err("Use format [ipv6]:port");
			host = s.substring(1, close).trim();
			portStr = s.substring(close + 2).trim();
		} else {
			int idx = s.lastIndexOf(':');
			if (idx <= 0 || idx == s.length() - 1) return ParseResult.err("Use format host:port or URL");
			host = s.substring(0, idx).trim();
			portStr = s.substring(idx + 1).trim();
		}

		if (!portStr.matches("\\d+")) return ParseResult.err("Port must be a number");
		int port;
		try {
			port = Integer.parseInt(portStr);
		} catch (Exception e) {
			return ParseResult.err("Port is not valid");
		}

		host = normalizeHost(host);

		String hostErr = validateHost(host);
		if (hostErr != null) return ParseResult.err(hostErr);

		String portErr = validatePort(port);
		if (portErr != null) return ParseResult.err(portErr);

		// ВНИМАНИЕ: host:port => tls=false (явный https:// нужен для TLS)
		return ParseResult.ok(host, port, false);
	}

	private static String normalizeHost(String host) {
		host = host.trim();
		if (host.isEmpty()) return host;
		try {
			return IDN.toASCII(host);
		} catch (Exception ignored) {
			return host;
		}
	}

	private static String validatePort(int port) {
		if (port < 1 || port > 65535) return "Port must be 1..65535";
		return null;
	}

	private static String validateHost(String host) {
		if (host == null || host.isEmpty()) return "Host is empty";
		if ("localhost".equalsIgnoreCase(host)) return null;

		if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
			String[] parts = host.split("\\.");
			for (String p : parts) {
				int v = Integer.parseInt(p);
				if (v < 0 || v > 255) return "IPv4 octets must be 0..255";
			}
			return null;
		}

		if (host.contains(":") || host.matches("[A-Za-z0-9.-]+")) return null;

		return "Host is not valid";
	}

	private static class ParseResult {
		final boolean ok;
		final String host;
		final int port;
		final boolean tls;
		final String error;

		private ParseResult(boolean ok, String host, int port, boolean tls, String error) {
			this.ok = ok;
			this.host = host;
			this.port = port;
			this.tls = tls;
			this.error = error;
		}

		static ParseResult ok(String host, int port, boolean tls) {
			return new ParseResult(true, host, port, tls, null);
		}

		static ParseResult err(String error) {
			return new ParseResult(false, null, 0, false, error);
		}
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (addrField != null && addrField.charTyped(codePoint, modifiers)) return true;
		return super.charTyped(codePoint, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (addrField != null && addrField.keyPressed(keyCode, scanCode, modifiers)) return true;
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(ms);
		drawCenteredString(ms, font, title, width / 2, 20, 0xFFFFFF);

		int x = width / 2 - 100;
		int y = height / 4 - 12;
		drawString(ms, font, "Server address (URL or host:port):", x, y, 0xA0A0A0);

		addrField.render(ms, mouseX, mouseY, partialTicks);

		if (statusText != null) drawString(ms, font, statusText, x, height / 4 + 24, 0x55FF55);
		if (errorText != null) drawString(ms, font, errorText, x, height / 4 + 24, 0xFF5555);

		super.render(ms, mouseX, mouseY, partialTicks);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
