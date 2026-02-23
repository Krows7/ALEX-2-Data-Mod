package net.krows_team.data_mod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.IntConsumer;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AbstractSlider;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.StringTextComponent;

/**
 * In-game configuration screen for stream connection and processing settings.
 */
public class DataModConfigScreen extends Screen {
	private static final int HEADER_Y = 6;
	private static final int HEADER_BOTTOM = 28;
	private static final int CONTENT_TOP_OFFSET = HEADER_BOTTOM + 8;

	private final Screen parent;

	private TextFieldWidget addrField;
	private TextFieldWidget processingThreadsField;
	private FlushEveryNSlider flushEveryNSlider;
	private ScrollOffsetSlider scrollOffsetSlider;
	private TextFieldWidget sshAddrField;
	private TextFieldWidget sshUserField;
	private TextFieldWidget sshKeyField;

	private Button connectBtn;
	private Button disconnectBtn;
	private Button sshToggleBtn;
	private Button closeBtn;

	private boolean sshEnabled;
	private String errorText = null;
	private String sshErrorText = null;
	private String statusText = null;
	private volatile boolean actionInProgress = false;
	private int scrollOffset = 0;
	private int maxScroll = 0;

	private static final int MAGIC_HSK1 = 0x48534B31; // "HSK1"
	private static final int MAGIC_OKAY = 0x4F4B4159; // "OKAY"

	/**
	 * Creates screen linked to parent screen.
	 *
	 * @param parent previous screen to return to on close
	 */
	public DataModConfigScreen(Screen parent) {
		super(new StringTextComponent("MyMod Config"));
		this.parent = parent;
	}

	/**
	 * Initializes widgets and loads values from {@link ClientConfig}.
	 */
	@Override
	protected void init() {
		Layout layout = layout();
		int x = layout.x;
		int y = layout.top;

		sshEnabled = ClientConfig.SSH_ENABLED.get();

		addrField = new TextFieldWidget(font, x, y + layout.addrFieldY, 240, 20, new StringTextComponent("IP:port"));
		addrField.setMaxLength(256);
		addrField.setValue(ClientConfig.SERVER_ADDR.get());
		children.add(addrField);
		setInitialFocus(addrField);

		processingThreadsField = new TextFieldWidget(font, x, y + layout.processingThreadsFieldY, 240, 20,
				new StringTextComponent("1..32"));
		processingThreadsField.setMaxLength(2);
		processingThreadsField.setValue(String.valueOf(sanitizeProcessingThreads(ClientConfig.PROCESSING_THREADS.get())));
		children.add(processingThreadsField);

		flushEveryNSlider = this.addButton(
				new FlushEveryNSlider(x, y + layout.flushEveryNFieldY, 240, 20, sanitizeFlushEveryN(ClientConfig.FLUSH_EVERY_N.get())));
		scrollOffsetSlider = this.addButton(new ScrollOffsetSlider(0, 0, 12, 40, this::setScrollOffsetClamped));

		sshToggleBtn = this.addButton(new Button(x, y + layout.sshToggleY, 240, 20, StringTextComponent.EMPTY, b -> {
			sshEnabled = !sshEnabled;
			refreshSshToggleLabel();
			refreshFieldState();
		}));
		refreshSshToggleLabel();

		sshAddrField = new TextFieldWidget(font, x, y + layout.sshAddrFieldY, 240, 20, new StringTextComponent("ssh-host:port"));
		sshAddrField.setMaxLength(256);
		sshAddrField.setValue(ClientConfig.SSH_ADDR.get());
		children.add(sshAddrField);

		sshUserField = new TextFieldWidget(font, x, y + layout.sshUserFieldY, 240, 20, new StringTextComponent("ssh-user"));
		sshUserField.setMaxLength(128);
		sshUserField.setValue(ClientConfig.SSH_USER.get());
		children.add(sshUserField);

		sshKeyField = new TextFieldWidget(font, x, y + layout.sshKeyFieldY, 240, 20, new StringTextComponent("/path/to/private_key"));
		sshKeyField.setMaxLength(512);
		sshKeyField.setValue(ClientConfig.SSH_KEY_PATH.get());
		children.add(sshKeyField);

		connectBtn = this
				.addButton(new Button(x, y + layout.connectButtonsY, 116, 20, new StringTextComponent("Connect"), b -> onConnectClicked()));
		disconnectBtn = this.addButton(new Button(x + 124, y + layout.connectButtonsY, 116, 20, new StringTextComponent("Disconnect"),
				b -> onDisconnectClicked()));
		closeBtn = this.addButton(
				new Button(x, y + layout.closeButtonY, 240, 20, new StringTextComponent("Close"), b -> minecraft.setScreen(parent)));

		refreshFieldState();
		scrollOffset = 0;
		updateScrollBounds(layout);
		applyWidgetPositions(layout, x, y - scrollOffset);
	}

	private void refreshSshToggleLabel() {
		if (sshToggleBtn != null) sshToggleBtn.setMessage(new StringTextComponent(sshEnabled ? "SSH tunnel: ON" : "SSH tunnel: OFF"));
	}

	private void refreshFieldState() {
		if (sshAddrField != null) sshAddrField.setEditable(sshEnabled && !actionInProgress);
		if (sshUserField != null) sshUserField.setEditable(sshEnabled && !actionInProgress);
		if (sshKeyField != null) sshKeyField.setEditable(sshEnabled && !actionInProgress);
	}

	private void onConnectClicked() {
		if (actionInProgress) return;

		final Integer processingThreads = parseProcessingThreads(processingThreadsField.getValue().trim());
		if (processingThreads == null) {
			errorText = "Processing threads must be 1..32";
			statusText = null;
			return;
		}
		final int flushEveryN = flushEveryNSlider != null ? flushEveryNSlider.getFlushEveryN() : 1;

		final ParseResult target = parseHostPort(addrField.getValue().trim(), "Server");
		if (!target.ok) {
			errorText = target.error;
			statusText = null;
			return;
		}

		final ParseResult sshAddr;
		final String sshUser;
		final String sshKey;
		if (sshEnabled) {
			sshAddr = parseHostPort(sshAddrField.getValue().trim(), "SSH");
			if (!sshAddr.ok) {
				errorText = sshAddr.error;
				statusText = null;
				return;
			}

			sshUser = sshUserField.getValue().trim();
			if (sshUser.isEmpty()) {
				errorText = "SSH user is empty";
				statusText = null;
				return;
			}

			sshKey = sshKeyField.getValue().trim();
			if (sshKey.isEmpty()) {
				errorText = "SSH key path is empty";
				statusText = null;
				return;
			}
		} else {
			sshAddr = null;
			sshUser = "";
			sshKey = "";
		}

		errorText = null;
		sshErrorText = null;
		statusText = sshEnabled ? "Connecting (SSH tunnel)..." : "Connecting...";
		actionInProgress = true;
		setUiEnabled(false);

		new Thread(() -> {
			String fail = null;
			String sshFail = null;
			boolean ok = false;
			String connectHost = target.host;
			int connectPort = target.port;

			try {
				if (sshEnabled) try {
					int localPort = SshTunnelManager.openTunnel(sshAddr.host, sshAddr.port, sshUser, sshKey, target.host, target.port);
					connectHost = "127.0.0.1";
					connectPort = localPort;
				} catch (Exception e) {
					sshFail = "SSH error: " + shortenError(e);
					throw e;
				}
				else SshTunnelManager.closeTunnel();

				ok = doHandshake(connectHost, connectPort, 2000, 2000);
				if (!ok) fail = "Handshake failed (bad reply)";
			} catch (Exception e) {
				if (fail == null) fail = "Cannot connect: " + shortenError(e);
				SshTunnelManager.closeTunnel();
			}

			final boolean success = ok;
			final String failMsg = fail;
			final String sshFailMsg = sshFail;
			final String finalHost = connectHost;
			final int finalPort = connectPort;

			Minecraft.getInstance().execute(() -> {
				actionInProgress = false;
				setUiEnabled(true);
				refreshFieldState();

				if (!success) {
					statusText = null;
					errorText = failMsg != null ? failMsg : "Handshake failed";
					sshErrorText = sshFailMsg;
					ClientStreamManager.disconnect();
					return;
				}

				ClientConfig.SERVER_ADDR.set(addrField.getValue().trim());
				ClientConfig.SSH_ENABLED.set(sshEnabled);
				ClientConfig.SSH_ADDR.set(sshAddrField.getValue().trim());
				ClientConfig.SSH_USER.set(sshUserField.getValue().trim());
				ClientConfig.SSH_KEY_PATH.set(sshKeyField.getValue().trim());
				ClientConfig.PROCESSING_THREADS.set(processingThreads);
				ClientConfig.FLUSH_EVERY_N.set(sanitizeFlushEveryN(flushEveryN));
				ClientConfig.SPEC.save();

				ClientStreamManager.connect(finalHost, finalPort);
				errorText = null;
				sshErrorText = null;
				statusText = sshEnabled ? "Connected via SSH tunnel" : "Connected";
			});
		}, "MyMod-Connect").start();
	}

	private void onDisconnectClicked() {
		if (actionInProgress) return;

		ClientStreamManager.disconnect();
		SshTunnelManager.closeTunnel();
		errorText = null;
		sshErrorText = null;
		statusText = "Disconnected";
	}

	private void setUiEnabled(boolean enabled) {
		addrField.setEditable(enabled);
		processingThreadsField.setEditable(enabled);
		if (flushEveryNSlider != null) flushEveryNSlider.active = enabled;
		if (connectBtn != null) connectBtn.active = enabled;
		if (disconnectBtn != null) disconnectBtn.active = enabled;
		if (sshToggleBtn != null) sshToggleBtn.active = enabled;
	}

	private static boolean doHandshake(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws Exception {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			s.setSoTimeout(readTimeoutMs);
			s.setTcpNoDelay(true);

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

	private static ParseResult parseHostPort(String s, String label) {
		if (s == null || s.isEmpty()) return ParseResult.err(label + " address is empty");

		String host;
		String portStr;

		if (s.startsWith("[")) {
			int close = s.indexOf(']');
			if (close < 0) return ParseResult.err(label + ": bad IPv6 format, missing ']'");
			if (close + 1 >= s.length() || s.charAt(close + 1) != ':') return ParseResult.err(label + ": use [ipv6]:port");
			host = s.substring(1, close).trim();
			portStr = s.substring(close + 2).trim();
		} else {
			int idx = s.lastIndexOf(':');
			if (idx <= 0 || idx == s.length() - 1) return ParseResult.err(label + ": use host:port");
			host = s.substring(0, idx).trim();
			portStr = s.substring(idx + 1).trim();
		}

		if (!portStr.matches("\\d+")) return ParseResult.err(label + ": port must be a number");
		int port;
		try {
			port = Integer.parseInt(portStr);
		} catch (Exception e) {
			return ParseResult.err(label + ": bad port");
		}

		host = normalizeHost(host);
		String hostErr = validateHost(host);
		if (hostErr != null) return ParseResult.err(label + ": " + hostErr);

		String portErr = validatePort(port);
		if (portErr != null) return ParseResult.err(label + ": " + portErr);

		return ParseResult.ok(host, port);
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
		if (port < 1 || port > 65535) return "port must be 1..65535";
		return null;
	}

	private static String validateHost(String host) {
		if (host == null || host.isEmpty()) return "host is empty";
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

		return "host is not valid";
	}

	private static Integer parseProcessingThreads(String s) {
		if (s == null || s.isEmpty() || !s.matches("\\d+")) return null;
		try {
			int v = Integer.parseInt(s);
			if (v < 1 || v > 32) return null;
			return v;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static int sanitizeProcessingThreads(int v) {
		if (v < 1) return 1;
		return Math.min(v, 32);
	}

	private static int sanitizeFlushEveryN(int v) {
		if (v < 1) return 1;
		return Math.min(v, 1024);
	}

	private static String shortenError(Exception e) {
		String msg = e.getMessage();
		if (msg == null || msg.trim().isEmpty()) return e.getClass().getSimpleName();
		msg = msg.trim();
		if (msg.length() > 90) return msg.substring(0, 90) + "...";
		return msg;
	}

	private static class ParseResult {
		final boolean ok;
		final String host;
		final int port;
		final String error;

		private ParseResult(boolean ok, String host, int port, String error) {
			this.ok = ok;
			this.host = host;
			this.port = port;
			this.error = error;
		}

		static ParseResult ok(String host, int port) {
			return new ParseResult(true, host, port, null);
		}

		static ParseResult err(String error) {
			return new ParseResult(false, null, 0, error);
		}
	}

	/**
	 * Handles character input for all text fields.
	 *
	 * @param codePoint typed character
	 * @param modifiers keyboard modifiers bit mask
	 * @return {@code true} if event was consumed
	 */
	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (addrField != null && addrField.charTyped(codePoint, modifiers)
				|| processingThreadsField != null && processingThreadsField.charTyped(codePoint, modifiers))
			return true;
		if ((sshAddrField != null && sshAddrField.charTyped(codePoint, modifiers)) || (sshUserField != null && sshUserField.charTyped(codePoint, modifiers))) return true;
		if (sshKeyField != null && sshKeyField.charTyped(codePoint, modifiers)) return true;
		return super.charTyped(codePoint, modifiers);
	}

	/**
	 * Handles key presses for all text fields.
	 *
	 * @param keyCode key code
	 * @param scanCode scan code
	 * @param modifiers keyboard modifiers bit mask
	 * @return {@code true} if event was consumed
	 */
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (addrField != null && addrField.keyPressed(keyCode, scanCode, modifiers)
				|| processingThreadsField != null && processingThreadsField.keyPressed(keyCode, scanCode, modifiers))
			return true;
		if ((sshAddrField != null && sshAddrField.keyPressed(keyCode, scanCode, modifiers)) || (sshUserField != null && sshUserField.keyPressed(keyCode, scanCode, modifiers))) return true;
		if (sshKeyField != null && sshKeyField.keyPressed(keyCode, scanCode, modifiers)) return true;
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	/**
	 * Renders full screen, widgets and status lines.
	 *
	 * @param ms render matrix stack
	 * @param mouseX mouse x
	 * @param mouseY mouse y
	 * @param partialTicks partial tick interpolation
	 */
	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(ms);

		Layout layout = layout();
		int x = layout.x;
		updateScrollBounds(layout);
		int y = layout.top - scrollOffset;
		applyWidgetPositions(layout, x, y);

		drawString(ms, font, "Server (IP:port):", x, y + layout.serverLabelY, 0xA0A0A0);
		drawString(ms, font, "Processing threads:", x, y + layout.processingThreadsLabelY, 0xA0A0A0);
		drawString(ms, font, "Flush every N packets:", x, y + layout.flushEveryNLabelY, 0xA0A0A0);
		drawString(ms, font, "SSH host:port:", x, y + layout.sshHostLabelY, 0xA0A0A0);
		drawString(ms, font, "SSH user:", x, y + layout.sshUserLabelY, 0xA0A0A0);
		drawString(ms, font, "SSH private key path:", x, y + layout.sshKeyLabelY, 0xA0A0A0);

		addrField.render(ms, mouseX, mouseY, partialTicks);
		processingThreadsField.render(ms, mouseX, mouseY, partialTicks);
		sshAddrField.render(ms, mouseX, mouseY, partialTicks);
		sshUserField.render(ms, mouseX, mouseY, partialTicks);
		sshKeyField.render(ms, mouseX, mouseY, partialTicks);

		String conn = ClientStreamManager.isConnected() ? "Stream: connected" : "Stream: disconnected";
		drawString(ms, font, conn, x, y + layout.connectionLineY, ClientStreamManager.isConnected() ? 0x55FF55 : 0xFFAA55);

		if (statusText != null) drawString(ms, font, statusText, x, y + layout.statusLineY, 0x55FF55);
		if (errorText != null) drawString(ms, font, errorText, x, y + layout.errorLineY, 0xFF5555);
		if (sshErrorText != null) drawString(ms, font, sshErrorText, x, y + layout.sshErrorLineY, 0xFFAA55);
		super.render(ms, mouseX, mouseY, partialTicks);
		fill(ms, 0, 0, width, HEADER_BOTTOM, 0xA0000000);
		drawCenteredString(ms, font, title, width / 2, HEADER_Y, 0xFFFFFF);
	}

	/**
	 * Scrolls configuration content for low-height windows.
	 *
	 * @param mouseX mouse x
	 * @param mouseY mouse y
	 * @param delta wheel delta
	 * @return {@code true} if scroll was handled
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (maxScroll <= 0) return super.mouseScrolled(mouseX, mouseY, delta);
		int next = scrollOffset - (int) Math.signum(delta) * 20;
		if (setScrollOffsetClamped(next)) return true;
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	private Layout layout() {
		boolean compact = height < 360;
		int top = compact ? CONTENT_TOP_OFFSET : CONTENT_TOP_OFFSET + 8;
		int x = width / 2 - 120;
		return compact ? Layout.compact(x, top) : Layout.normal(x, top);
	}

	private void updateScrollBounds(Layout layout) {
		int contentBottomOffset = Math.max(layout.closeButtonY + 22, layout.sshErrorLineY + 12);
		int availableBottom = height - 6;
		int max = layout.top + contentBottomOffset - availableBottom;
		maxScroll = Math.max(0, max);
		if (scrollOffset > maxScroll) scrollOffset = maxScroll;
		updateScrollSlider(layout);
	}

	private boolean setScrollOffsetClamped(int offset) {
		int next = offset;
		if (next < 0) next = 0;
		if (next > maxScroll) next = maxScroll;
		if (next == scrollOffset) return false;
		scrollOffset = next;
		if (scrollOffsetSlider != null) scrollOffsetSlider.setScrollOffset(scrollOffset);
		return true;
	}

	private void updateScrollSlider(Layout layout) {
		if (scrollOffsetSlider == null) return;
		int sliderX = layout.x + 246;
		int sliderY = Math.max(layout.top + 8, HEADER_BOTTOM + 2);
		int sliderHeight = Math.max(40, height - sliderY - 8);
		scrollOffsetSlider.setBounds(sliderX, sliderY, sliderHeight);
		scrollOffsetSlider.setScrollRange(maxScroll);
		scrollOffsetSlider.setScrollOffset(scrollOffset);
		scrollOffsetSlider.active = maxScroll > 0;
		scrollOffsetSlider.visible = maxScroll > 0;
	}

	private void applyWidgetPositions(Layout layout, int x, int y) {
		addrField.x = x;
		addrField.y = y + layout.addrFieldY;
		processingThreadsField.x = x;
		processingThreadsField.y = y + layout.processingThreadsFieldY;
		if (flushEveryNSlider != null) {
			flushEveryNSlider.x = x;
			flushEveryNSlider.y = y + layout.flushEveryNFieldY;
		}
		sshToggleBtn.x = x;
		sshToggleBtn.y = y + layout.sshToggleY;
		sshAddrField.x = x;
		sshAddrField.y = y + layout.sshAddrFieldY;
		sshUserField.x = x;
		sshUserField.y = y + layout.sshUserFieldY;
		sshKeyField.x = x;
		sshKeyField.y = y + layout.sshKeyFieldY;
		connectBtn.x = x;
		connectBtn.y = y + layout.connectButtonsY;
		disconnectBtn.x = x + 124;
		disconnectBtn.y = y + layout.connectButtonsY;
		closeBtn.x = x;
		closeBtn.y = y + layout.closeButtonY;
	}

	private static class Layout {
		final int x;
		final int top;
		final int serverLabelY;
		final int addrFieldY;
		final int processingThreadsLabelY;
		final int processingThreadsFieldY;
		final int flushEveryNLabelY;
		final int flushEveryNFieldY;
		final int sshToggleY;
		final int sshHostLabelY;
		final int sshAddrFieldY;
		final int sshUserLabelY;
		final int sshUserFieldY;
		final int sshKeyLabelY;
		final int sshKeyFieldY;
		final int connectButtonsY;
		final int closeButtonY;
		final int connectionLineY;
		final int statusLineY;
		final int errorLineY;
		final int sshErrorLineY;

		private Layout(int x, int top, int serverLabelY, int addrFieldY, int processingThreadsLabelY, int processingThreadsFieldY,
				int flushEveryNLabelY, int flushEveryNFieldY, int sshToggleY, int sshHostLabelY, int sshAddrFieldY, int sshUserLabelY,
				int sshUserFieldY, int sshKeyLabelY, int sshKeyFieldY, int connectButtonsY, int closeButtonY, int connectionLineY,
				int statusLineY, int errorLineY, int sshErrorLineY) {
			this.x = x;
			this.top = top;
			this.serverLabelY = serverLabelY;
			this.addrFieldY = addrFieldY;
			this.processingThreadsLabelY = processingThreadsLabelY;
			this.processingThreadsFieldY = processingThreadsFieldY;
			this.flushEveryNLabelY = flushEveryNLabelY;
			this.flushEveryNFieldY = flushEveryNFieldY;
			this.sshToggleY = sshToggleY;
			this.sshHostLabelY = sshHostLabelY;
			this.sshAddrFieldY = sshAddrFieldY;
			this.sshUserLabelY = sshUserLabelY;
			this.sshUserFieldY = sshUserFieldY;
			this.sshKeyLabelY = sshKeyLabelY;
			this.sshKeyFieldY = sshKeyFieldY;
			this.connectButtonsY = connectButtonsY;
			this.closeButtonY = closeButtonY;
			this.connectionLineY = connectionLineY;
			this.statusLineY = statusLineY;
			this.errorLineY = errorLineY;
			this.sshErrorLineY = sshErrorLineY;
		}

		static Layout normal(int x, int top) {
			return new Layout(x, top, 0, 10, 38, 48, 76, 86, 114, 140, 152, 178, 190, 216, 228, 262, 288, 322, 334, 346, 358);
		}

		static Layout compact(int x, int top) {
			return new Layout(x, top, 0, 8, 32, 42, 66, 76, 100, 124, 134, 158, 168, 192, 202, 228, 252, 270, 280, 290, 300);
		}
	}

	private static class ScrollOffsetSlider extends Button {
		private final IntConsumer onOffsetChanged;
		private int maxScroll;
		private int scrollOffset;
		private boolean dragging;
		private int dragOffsetY;
		private static final int MIN_THUMB_H = 16;

		ScrollOffsetSlider(int x, int y, int width, int height, IntConsumer onOffsetChanged) {
			super(x, y, width, height, StringTextComponent.EMPTY, b -> {
			});
			this.onOffsetChanged = onOffsetChanged;
			maxScroll = 0;
			scrollOffset = 0;
		}

		void setBounds(int x, int y, int height) {
			this.x = x;
			this.y = y;
			this.height = height;
		}

		void setScrollRange(int maxScroll) {
			this.maxScroll = Math.max(0, maxScroll);
			if (this.maxScroll == 0) scrollOffset = 0;
		}

		void setScrollOffset(int offset) {
			int clamped = offset;
			if (clamped < 0) clamped = 0;
			if (clamped > maxScroll) clamped = maxScroll;
			scrollOffset = clamped;
		}

		@Override
		public void onPress() {
			// handled in mouseClicked/mouseDragged
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) return false;

			int thumbY = getThumbY();
			int thumbH = getThumbHeight();
			int mouseYInt = (int) mouseY;
			if (mouseYInt >= thumbY && mouseYInt <= thumbY + thumbH) {
				dragging = true;
				dragOffsetY = mouseYInt - thumbY;
			} else {
				updateFromMouse(mouseYInt - thumbH / 2);
				dragging = true;
				dragOffsetY = thumbH / 2;
			}
			return true;
		}

		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			if (!active || !visible || !dragging || button != 0) return false;
			updateFromMouse((int) mouseY - dragOffsetY);
			return true;
		}

		@Override
		public boolean mouseReleased(double mouseX, double mouseY, int button) {
			if (button == 0) dragging = false;
			return super.mouseReleased(mouseX, mouseY, button);
		}

		@Override
		public void renderButton(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
			if (!visible) return;
			fill(ms, x, y, x + width, y + height, 0x80404040);
			int thumbY = getThumbY();
			int thumbH = getThumbHeight();
			int thumbColor = active ? 0xFFA0A0A0 : 0xFF707070;
			fill(ms, x + 1, thumbY, x + width - 1, thumbY + thumbH, thumbColor);
		}

		private int getThumbHeight() {
			if (maxScroll <= 0) return height;
			int h = (int) Math.round(height * (height / (double) (height + maxScroll)));
			if (h < MIN_THUMB_H) h = MIN_THUMB_H;
			if (h > height) h = height;
			return h;
		}

		private int getThumbY() {
			int thumbH = getThumbHeight();
			int track = Math.max(1, height - thumbH);
			if (maxScroll <= 0) return y;
			return y + (int) Math.round(track * (scrollOffset / (double) maxScroll));
		}

		private void updateFromMouse(int desiredThumbY) {
			int thumbH = getThumbHeight();
			int minY = y;
			int maxY = y + height - thumbH;
			int thumbY = desiredThumbY;
			if (thumbY < minY) thumbY = minY;
			if (thumbY > maxY) thumbY = maxY;

			int track = Math.max(1, height - thumbH);
			int offset = maxScroll <= 0 ? 0 : (int) Math.round((thumbY - y) * (maxScroll / (double) track));
			onOffsetChanged.accept(offset);
		}
	}

	private static class FlushEveryNSlider extends AbstractSlider {
		private static final int MIN = 1;
		private static final int MAX = 512;
		private int flushEveryN;

		FlushEveryNSlider(int x, int y, int width, int height, int initialValue) {
			super(x, y, width, height, StringTextComponent.EMPTY, toSliderValue(clamp(initialValue)));
			flushEveryN = clamp(initialValue);
			updateMessage();
		}

		int getFlushEveryN() {
			return flushEveryN;
		}

		@Override
		protected void updateMessage() {
			setMessage(new StringTextComponent(String.valueOf(flushEveryN)));
		}

		@Override
		protected void applyValue() {
			flushEveryN = fromSliderValue(value);
			updateMessage();
		}

		private static int clamp(int v) {
			if (v < MIN) return MIN;
			return Math.min(v, MAX);
		}

		private static double toSliderValue(int v) {
			return (v - MIN) / (double) (MAX - MIN);
		}

		private static int fromSliderValue(double v) {
			double clamped = Math.max(0.0, Math.min(1.0, v));
			return MIN + (int) Math.round(clamped * (MAX - MIN));
		}
	}

	/**
	 * Closes config screen and returns to parent.
	 */
	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
