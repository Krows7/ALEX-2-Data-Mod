package net.krows_team.data_mod;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side stream orchestrator.
 * <p>
 * Captures player state and frame snapshots on ticks, encodes packets and sends
 * them to {@link StreamSender} while preserving tick order.
 */
@Mod.EventBusSubscriber(modid = DataMod.MODID, value = Dist.CLIENT)
public class ClientStreamManager {
	private static final boolean IS_DEBUG = false;
	private static final boolean IS_RENDER_CALL = true;
	private static final boolean IS_ASYNC_PBO = true;
	private static final int VERBOSE_N = 50;
	private static final long RENDER_CALL_TIMEOUT_MS = 250L;

	private static volatile StreamSender sender;
	private static volatile boolean connectRequested = false;
	private static volatile HostPort endpoint = null;

	private static volatile ExecutorService processingExecutor;
	private static volatile int processingExecutorThreads = -1;
	private static volatile Semaphore inflightPermits = new Semaphore(1);

	private static final Object PROCESSING_LOCK = new Object();
	private static final Object ORDER_LOCK = new Object();
	private static final ConcurrentHashMap<Integer, byte[]> readyPackets = new ConcurrentHashMap<>();
	private static final AtomicInteger scheduledTickSeq = new AtomicInteger(0);
	private static final AtomicInteger nextSendTickSeq = new AtomicInteger(0);
	private static final int TARGET_W = 128;
	private static final int TARGET_H = 128;
	private static final int TARGET_RGBA_BYTES = TARGET_W * TARGET_H * 4;
	private static final byte[] SKIP_PACKET = {};
	private static final int PBO_COUNT = 2;
	private static volatile int downsampleFboId = -1;
	private static volatile int downsampleTexId = -1;
	private static volatile int[] downsamplePboIds = null;
	private static volatile int pboWriteIndex = 0;
	private static volatile boolean pboPrimed = false;
	private static volatile ByteBuffer pooledRgbaBuffer = null;
	private static final Path DEBUG_LOG_PATH = Paths.get("logs", "client_stream_debug.log");
	private static final Object DEBUG_LOG_LOCK = new Object();
	private static volatile Writer debugLogWriter = null;
	private static final AtomicInteger debugTickCounter = new AtomicInteger(0);

	private static final AtomicLong dbgCaptureSnapshotNs = new AtomicLong(0);
	private static final AtomicLong dbgCaptureSnapshotCnt = new AtomicLong(0);
	private static final AtomicLong dbgCaptureFrameNs = new AtomicLong(0);
	private static final AtomicLong dbgCaptureFrameCnt = new AtomicLong(0);
	private static final AtomicLong dbgResizeNs = new AtomicLong(0);
	private static final AtomicLong dbgResizeCnt = new AtomicLong(0);
	private static final AtomicLong dbgBuildPacketNs = new AtomicLong(0);
	private static final AtomicLong dbgBuildPacketCnt = new AtomicLong(0);
	private static final AtomicLong dbgFlushReadyNs = new AtomicLong(0);
	private static final AtomicLong dbgFlushReadyCnt = new AtomicLong(0);
	private static final AtomicLong dbgWorkerTotalNs = new AtomicLong(0);
	private static final AtomicLong dbgWorkerTotalCnt = new AtomicLong(0);
	private static final AtomicLong dbgPboHitCnt = new AtomicLong(0);
	private static final AtomicLong dbgPboFallbackCnt = new AtomicLong(0);
	private static final AtomicLong dbgPboMapNullCnt = new AtomicLong(0);

	/**
	 * Auto-starts stream after world login when connection was requested earlier.
	 *
	 * @param e Forge login event
	 */
	@SubscribeEvent
	public static void onLogin(ClientPlayerNetworkEvent.LoggedInEvent e) {
		if (connectRequested && endpoint != null) start(endpoint.host, endpoint.port);
	}

	/**
	 * Stops stream when player leaves world/server.
	 *
	 * @param e Forge logout event
	 */
	@SubscribeEvent
	public static void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent e) {
		stop();
	}

	/**
	 * Connects using endpoint from client config.
	 */
	public static void start() {
		HostPort hp = HostPort.parse(ClientConfig.SERVER_ADDR.get());
		connect(hp.host, hp.port);
	}

	/**
	 * Requests persistent connection and starts sender immediately.
	 *
	 * @param host target host
	 * @param port target port
	 */
	public static synchronized void connect(String host, int port) {
		endpoint = new HostPort(host, port);
		connectRequested = true;
		start(host, port);
	}

	/**
	 * Cancels persistent connection request and stops active sender.
	 */
	public static synchronized void disconnect() {
		connectRequested = false;
		endpoint = null;
		stop();
	}

	/**
	 * @return {@code true} when user requested connection and sender is running
	 */
	public static synchronized boolean isConnected() {
		return connectRequested && sender != null;
	}

	private static void start(String host, int port) {
		stop();
		ensureProcessingExecutor();
		sender = new StreamSender(host, port);
		sender.start();
	}

	/**
	 * Fully stops sending pipeline and releases resources.
	 */
	public static void stop() {
		if (sender != null) {
			sender.shutdown();
			sender = null;
		}
		shutdownProcessingExecutor();
		releaseDownsampleResources();
		closeDebugLog();
		resetOrderingState();
	}

	/**
	 * Per-tick capture and enqueue scheduling entry point.
	 *
	 * @param e client tick event
	 */
	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent e) {
		if (e.phase != TickEvent.Phase.END) return;
		maybePrintDebugAverages();
		if (sender == null) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.isPaused()) return;

		Semaphore permits = inflightPermits;
		if (permits == null || !permits.tryAcquire()) return;

		final int tickSeq = scheduledTickSeq.get();
		final ObservationSnapshot snapshot;
		long captureSnapshotStartNs = debugNow();
		try {
			snapshot = captureSnapshot(mc, mc.player, tickSeq);
		} catch (Throwable t) {
			debugRecord(dbgCaptureSnapshotNs, dbgCaptureSnapshotCnt, captureSnapshotStartNs);
			permits.release();
			return;
		}
		debugRecord(dbgCaptureSnapshotNs, dbgCaptureSnapshotCnt, captureSnapshotStartNs);

		ExecutorService exec = processingExecutor;
		if (exec == null) {
			permits.release();
			return;
		}

		try {
			exec.submit(() -> {
				long workerStartNs = debugNow();
				try {
					byte[] resized;
					if (snapshot.frameW == TARGET_W && snapshot.frameH == TARGET_H) resized = snapshot.frameBgr;
					else {
						long resizeStartNs = debugNow();
						resized = resizeBgrBilinear(snapshot.frameBgr, snapshot.frameW, snapshot.frameH, TARGET_W, TARGET_H);
						debugRecord(dbgResizeNs, dbgResizeCnt, resizeStartNs);
					}

					long buildPacketStartNs = debugNow();
					byte[] packet = buildPacket(snapshot, resized);
					debugRecord(dbgBuildPacketNs, dbgBuildPacketCnt, buildPacketStartNs);

					readyPackets.put(snapshot.tickSeq, packet);
					long flushStartNs = debugNow();
					flushReadyPackets();
					debugRecord(dbgFlushReadyNs, dbgFlushReadyCnt, flushStartNs);
				} catch (Throwable ignored) {
					readyPackets.put(snapshot.tickSeq, SKIP_PACKET);
					long flushStartNs = debugNow();
					flushReadyPackets();
					debugRecord(dbgFlushReadyNs, dbgFlushReadyCnt, flushStartNs);
				} finally {
					debugRecord(dbgWorkerTotalNs, dbgWorkerTotalCnt, workerStartNs);
					permits.release();
				}
			});
			scheduledTickSeq.incrementAndGet();
		} catch (RejectedExecutionException ex) {
			permits.release();
		}
	}

	/**
	 * Draws debug stream stats while pause menu is open.
	 *
	 * @param e post GUI draw event
	 */
	@SubscribeEvent
	public static void onPauseScreenDraw(GuiScreenEvent.DrawScreenEvent.Post e) {
		if (!IS_DEBUG || !(e.getGui() instanceof IngameMenuScreen)) return;

		StreamSender s = sender;
		int queuePackets = s != null ? s.getQueueSize() : 0;
		int bufferedFrames = readyPackets.size();
		long sentPackets = s != null ? s.getSentPackets() : 0L;
		long enqueuedPackets = s != null ? s.getEnqueuedPackets() : 0L;
		double sentPercent = enqueuedPackets > 0L ? sentPackets * 100.0 / enqueuedPackets : 0.0;

		MatrixStack ms = e.getMatrixStack();
		Minecraft mc = Minecraft.getInstance();
		int color = 0x00FF00;
		int rightPadding = 8;
		int y = 8;

		y = drawTopRight(ms, mc, String.format("Send queue: %d", queuePackets), y, color, rightPadding);
		y = drawTopRight(ms, mc, String.format("Frame buffer: %d", bufferedFrames), y, color, rightPadding);
		y = drawTopRight(ms, mc, String.format("Sent: %d", sentPackets), y, color, rightPadding);
		drawTopRight(ms, mc, String.format("Sent ratio: %.1f%% (%d/%d)", sentPercent, sentPackets, enqueuedPackets), y, color,
				rightPadding);
	}

	private static int drawTopRight(MatrixStack ms, Minecraft mc, String text, int y, int color, int rightPadding) {
		int x = mc.getWindow().getGuiScaledWidth() - mc.font.width(text) - rightPadding;
		mc.font.drawShadow(ms, text, x, y, color);
		return y + 10;
	}

	private static void ensureProcessingExecutor() {
		synchronized (PROCESSING_LOCK) {
			int threads = getProcessingThreads();
			if (processingExecutor != null && processingExecutorThreads == threads) return;

			shutdownProcessingExecutorLocked();
			processingExecutor = Executors.newFixedThreadPool(threads, r -> {
				Thread t = new Thread(r, "MyMod-TickWorker");
				t.setDaemon(true);
				return t;
			});
			processingExecutorThreads = threads;
			inflightPermits = new Semaphore(Math.max(1, threads * 3));
		}
	}

	private static void shutdownProcessingExecutor() {
		synchronized (PROCESSING_LOCK) {
			shutdownProcessingExecutorLocked();
		}
	}

	private static void shutdownProcessingExecutorLocked() {
		if (processingExecutor != null) try {
			processingExecutor.shutdownNow();
		} catch (Exception ignored) {
		}
		processingExecutor = null;
		processingExecutorThreads = -1;
		inflightPermits = new Semaphore(1);
	}

	private static void resetOrderingState() {
		readyPackets.clear();
		scheduledTickSeq.set(0);
		nextSendTickSeq.set(0);
	}

	private static void flushReadyPackets() {
		synchronized (ORDER_LOCK) {
			while (true) {
				int expected = nextSendTickSeq.get();
				byte[] packet = readyPackets.remove(expected);
				if (packet == null) return;

				StreamSender s = sender;
				if (s == null) {
					readyPackets.clear();
					return;
				}

				if (packet.length > 0) s.enqueue(packet);
				nextSendTickSeq.incrementAndGet();
			}
		}
	}

	private static int getProcessingThreads() {
		int threads = 1;
		try {
			threads = ClientConfig.PROCESSING_THREADS.get();
		} catch (Exception ignored) {
		}
		if (threads < 1) return 1;
		return Math.min(threads, 32);
	}

	private static ObservationSnapshot captureSnapshot(Minecraft mc, ClientPlayerEntity p, int tickSeq) throws Exception {
		FrameData frame = captureFrameBgr(mc);

		float hp = p.getHealth();
		float hpMax = p.getMaxHealth();
		int armor = p.getArmorValue();
		int hunger = p.getFoodData().getFoodLevel();
		int air = p.getAirSupply();
		int selectedSlot = p.inventory.selected;
		String mainHandId = itemId(p.getMainHandItem());

		String dimensionId = "";
		try {
			dimensionId = mc.level.dimension().location().toString();
		} catch (Throwable t) {
			dimensionId = "";
		}

		Biome biome = mc.level.getBiome(p.blockPosition());

		String biomeId = "";
		try {
			ResourceLocation rl = biome.getRegistryName();
			biomeId = rl != null ? rl.toString() : "";
		} catch (Throwable t) {
			biomeId = "";
		}

		String biomeCategory = "";
		try {
			Biome.Category cat = biome.getBiomeCategory();
			biomeCategory = cat != null ? cat.getName() : "";
		} catch (Throwable t) {
			biomeCategory = "";
		}

		long dayTime = mc.level.getDayTime();
		PlayerInventory inv = p.inventory;

		String[] invItems = toItemIdArray(inv.items);
		String[] invArmor = toItemIdArray(inv.armor);
		String[] invOffhand = toItemIdArray(inv.offhand);

		return new ObservationSnapshot(tickSeq, dayTime, hp, hpMax, armor, hunger, air, selectedSlot, dimensionId, biomeId, biomeCategory,
				mainHandId, invItems, invArmor, invOffhand, frame.w, frame.h, frame.bgr);
	}

	private static String[] toItemIdArray(java.util.List<ItemStack> items) {
		String[] out = new String[items.size()];
		for (int i = 0; i < items.size(); i++) out[i] = itemId(items.get(i));
		return out;
	}

	private static byte[] buildPacket(ObservationSnapshot s, byte[] resizedBgr) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
		DataOutputStream out = new DataOutputStream(baos);

		out.writeInt(0x4D524C31); // "MRL1"
		out.writeInt(s.tickSeq);

		out.writeLong(s.dayTime);
		out.writeFloat(s.hp);
		out.writeFloat(s.hpMax);
		out.writeInt(s.armor);
		out.writeInt(s.hunger);
		out.writeInt(s.air);
		out.writeInt(s.selectedSlot);

		writeString(out, s.dimensionId);
		writeString(out, s.biomeId);
		writeString(out, s.biomeCategory);
		writeString(out, s.mainHandId);

		out.writeInt(s.invItems.length);
		for (String id : s.invItems) writeString(out, id);

		out.writeInt(s.invArmor.length);
		for (String id : s.invArmor) writeString(out, id);

		out.writeInt(s.invOffhand.length);
		for (String id : s.invOffhand) writeString(out, id);

		out.writeInt(TARGET_W);
		out.writeInt(TARGET_H);
		out.writeInt(resizedBgr.length);
		out.write(resizedBgr);

		out.flush();
		return baos.toByteArray();
	}

	private static FrameData captureFrameBgr(Minecraft mc) {
		long captureFrameStartNs = debugNow();
		FrameData frame = IS_RENDER_CALL ? captureFrameBgrViaRenderCall(mc) : captureFrameBgrDirect(mc);
		debugRecord(dbgCaptureFrameNs, dbgCaptureFrameCnt, captureFrameStartNs);
		return frame;
	}

	private static FrameData captureFrameBgrViaRenderCall(Minecraft mc) {
		if (RenderSystem.isOnRenderThreadOrInit()) return captureFrameBgrDownsampledOnRenderThread(mc);

		AtomicReference<FrameData> result = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		RenderSystem.recordRenderCall(() -> {
			try {
				result.set(captureFrameBgrDownsampledOnRenderThread(mc));
			} catch (Throwable t) {
				error.set(t);
			} finally {
				done.countDown();
			}
		});

		try {
			if (!done.await(RENDER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS))
				throw new RuntimeException("Timed out waiting for RenderSystem.recordRenderCall");
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while waiting for RenderSystem.recordRenderCall", ie);
		}

		Throwable t = error.get();
		if (t != null) throw new RuntimeException(t);
		FrameData frame = result.get();
		if (frame == null) throw new RuntimeException("Render call finished without frame data");
		return frame;
	}

	private static FrameData captureFrameBgrDownsampledOnRenderThread(Minecraft mc) {
		ensureOnRenderThread();
		ensureDownsampleResourcesOnRenderThread();

		int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);

		int srcW = mc.getWindow().getWidth();
		int srcH = mc.getWindow().getHeight();
		try {
			mc.getMainRenderTarget().bindRead();
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, downsampleFboId);
			GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, TARGET_W, TARGET_H, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, downsampleFboId);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

			if (IS_ASYNC_PBO && downsamplePboIds != null && downsamplePboIds.length == PBO_COUNT) {
				int writeIndex = pboWriteIndex;
				int readIndex = (writeIndex + 1) % PBO_COUNT;

				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, downsamplePboIds[writeIndex]);
				GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, TARGET_RGBA_BYTES, GL15.GL_STREAM_READ);
				GL11.glReadPixels(0, 0, TARGET_W, TARGET_H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

				if (pboPrimed) {
					GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, downsamplePboIds[readIndex]);
					ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, TARGET_RGBA_BYTES, GL30.GL_MAP_READ_BIT, null);
					if (mapped != null) {
						byte[] bgr = rgbaToBgrFlipped(mapped);
						GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
						dbgPboHitCnt.incrementAndGet();
						pboWriteIndex = (writeIndex + 1) % PBO_COUNT;
						return new FrameData(TARGET_W, TARGET_H, bgr);
					}
					dbgPboMapNullCnt.incrementAndGet();
				}

				pboPrimed = true;
				pboWriteIndex = (writeIndex + 1) % PBO_COUNT;
			}

			dbgPboFallbackCnt.incrementAndGet();
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			ByteBuffer rgba = borrowRgbaBuffer();
			GL11.glReadPixels(0, 0, TARGET_W, TARGET_H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
			return new FrameData(TARGET_W, TARGET_H, rgbaToBgrFlipped(rgba));
		} finally {
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
		}
	}

	private static void ensureDownsampleResourcesOnRenderThread() {
		boolean pboReady = !IS_ASYNC_PBO || downsamplePboIds != null && downsamplePboIds.length == PBO_COUNT;
		if (downsampleFboId != -1 && downsampleTexId != -1 && pboReady) return;
		ensureOnRenderThread();

		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);

		if (downsampleFboId != -1 || downsampleTexId != -1 || downsamplePboIds != null) releaseDownsampleResourcesOnRenderThread();

		downsampleFboId = GL30.glGenFramebuffers();
		downsampleTexId = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, downsampleTexId);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, TARGET_W, TARGET_H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
				(ByteBuffer) null);

		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, downsampleFboId);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, downsampleTexId, 0);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			releaseDownsampleResourcesOnRenderThread();
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
			throw new RuntimeException("Downsample framebuffer is incomplete: " + status);
		}

		if (IS_ASYNC_PBO) {
			downsamplePboIds = new int[PBO_COUNT];
			for (int i = 0; i < PBO_COUNT; i++) {
				downsamplePboIds[i] = GL15.glGenBuffers();
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, downsamplePboIds[i]);
				GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, TARGET_RGBA_BYTES, GL15.GL_STREAM_READ);
			}
		} else downsamplePboIds = null;
		pboWriteIndex = 0;
		pboPrimed = false;
		pooledRgbaBuffer = null;

		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
	}

	private static void releaseDownsampleResources() {
		if (downsampleFboId == -1 && downsampleTexId == -1 && downsamplePboIds == null) return;
		if (RenderSystem.isOnRenderThreadOrInit()) releaseDownsampleResourcesOnRenderThread();
		else RenderSystem.recordRenderCall(ClientStreamManager::releaseDownsampleResourcesOnRenderThread);
	}

	private static void releaseDownsampleResourcesOnRenderThread() {
		if (downsampleFboId != -1) {
			GL30.glDeleteFramebuffers(downsampleFboId);
			downsampleFboId = -1;
		}
		if (downsampleTexId != -1) {
			GL11.glDeleteTextures(downsampleTexId);
			downsampleTexId = -1;
		}
		if (downsamplePboIds != null) {
			for (int pboId : downsamplePboIds) if (pboId != 0) GL15.glDeleteBuffers(pboId);
			downsamplePboIds = null;
		}
		pboWriteIndex = 0;
		pboPrimed = false;
		pooledRgbaBuffer = null;
	}

	private static void ensureOnRenderThread() {
		if (!RenderSystem.isOnRenderThreadOrInit()) throw new IllegalStateException("OpenGL call outside render thread");
	}

	private static ByteBuffer borrowRgbaBuffer() {
		ByteBuffer rgba = pooledRgbaBuffer;
		if (rgba == null || rgba.capacity() < TARGET_RGBA_BYTES) {
			rgba = BufferUtils.createByteBuffer(TARGET_RGBA_BYTES);
			pooledRgbaBuffer = rgba;
		}
		rgba.clear();
		rgba.limit(TARGET_RGBA_BYTES);
		return rgba;
	}

	private static byte[] rgbaToBgrFlipped(ByteBuffer rgba) {
		byte[] bgr = new byte[TARGET_W * TARGET_H * 3];
		int out = 0;
		for (int y = 0; y < TARGET_H; y++) {
			int srcY = TARGET_H - 1 - y;
			int row = srcY * TARGET_W * 4;
			for (int x = 0; x < TARGET_W; x++) {
				int i = row + x * 4;
				bgr[out++] = rgba.get(i + 2);
				bgr[out++] = rgba.get(i + 1);
				bgr[out++] = rgba.get(i);
			}
		}
		return bgr;
	}

	private static FrameData captureFrameBgrDirect(Minecraft mc) {
		int w = mc.getWindow().getWidth();
		int h = mc.getWindow().getHeight();
		NativeImage full = ScreenShotHelper.takeScreenshot(w, h, mc.getMainRenderTarget());

		byte[] bgr = new byte[w * h * 3];
		int idx = 0;
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
			int pixel = full.getPixelRGBA(x, y);
			bgr[idx++] = (byte) NativeImage.getB(pixel);
			bgr[idx++] = (byte) NativeImage.getG(pixel);
			bgr[idx++] = (byte) NativeImage.getR(pixel);
		}
		full.close();
		return new FrameData(w, h, bgr);
	}

	private static long debugNow() {
		return IS_DEBUG ? System.nanoTime() : 0L;
	}

	private static void debugRecord(AtomicLong totalNs, AtomicLong count, long startedAtNs) {
		if (!IS_DEBUG || startedAtNs == 0L) return;
		totalNs.addAndGet(System.nanoTime() - startedAtNs);
		count.incrementAndGet();
	}

	private static void maybePrintDebugAverages() {
		if (!IS_DEBUG || VERBOSE_N <= 0) return;
		int t = debugTickCounter.incrementAndGet();
		if (t % VERBOSE_N != 0) return;

		DebugStat captureSnapshot = drainStat(dbgCaptureSnapshotNs, dbgCaptureSnapshotCnt);
		DebugStat captureFrame = drainStat(dbgCaptureFrameNs, dbgCaptureFrameCnt);
		DebugStat resize = drainStat(dbgResizeNs, dbgResizeCnt);
		DebugStat buildPacket = drainStat(dbgBuildPacketNs, dbgBuildPacketCnt);
		DebugStat flushReady = drainStat(dbgFlushReadyNs, dbgFlushReadyCnt);
		DebugStat workerTotal = drainStat(dbgWorkerTotalNs, dbgWorkerTotalCnt);
		long pboHit = drainCount(dbgPboHitCnt);
		long pboFallback = drainCount(dbgPboFallbackCnt);
		long pboMapNull = drainCount(dbgPboMapNullCnt);
		String line = String.format(
				"[DataMod Debug] tick=%d | captureSnapshot=%.3f ms (n=%d) | captureFrame=%.3f ms (n=%d) | resize=%.3f ms (n=%d) | buildPacket=%.3f ms (n=%d) | flushReady=%.3f ms (n=%d) | workerTotal=%.3f ms (n=%d) | pboHit=%d | pboFallback=%d | pboMapNull=%d",
				t, captureSnapshot.avgMs, captureSnapshot.count, captureFrame.avgMs, captureFrame.count, resize.avgMs, resize.count,
				buildPacket.avgMs, buildPacket.count, flushReady.avgMs, flushReady.count, workerTotal.avgMs, workerTotal.count, pboHit,
				pboFallback, pboMapNull);
		System.out.println(line);
		appendDebugLogLine(line);
	}

	private static DebugStat drainStat(AtomicLong totalNs, AtomicLong count) {
		long c = count.getAndSet(0L);
		long ns = totalNs.getAndSet(0L);
		if (c <= 0L) return new DebugStat(0.0, 0L);
		return new DebugStat(ns / 1_000_000.0 / c, c);
	}

	private static long drainCount(AtomicLong count) {
		return count.getAndSet(0L);
	}

	private static void appendDebugLogLine(String line) {
		if (!IS_DEBUG || line == null) return;
		synchronized (DEBUG_LOG_LOCK) {
			try {
				if (debugLogWriter == null) {
					Path parent = DEBUG_LOG_PATH.getParent();
					if (parent != null) Files.createDirectories(parent);
					debugLogWriter = Files.newBufferedWriter(DEBUG_LOG_PATH, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
							StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				}
				debugLogWriter.write(line);
				debugLogWriter.write(System.lineSeparator());
				debugLogWriter.flush();
			} catch (IOException ignored) {
			}
		}
	}

	private static void closeDebugLog() {
		synchronized (DEBUG_LOG_LOCK) {
			if (debugLogWriter == null) return;
			try {
				debugLogWriter.close();
			} catch (IOException ignored) {
			} finally {
				debugLogWriter = null;
			}
		}
	}

	private static class DebugStat {
		final double avgMs;
		final long count;

		DebugStat(double avgMs, long count) {
			this.avgMs = avgMs;
			this.count = count;
		}
	}

	private static byte[] resizeBgrBilinear(byte[] src, int sw, int sh, int dw, int dh) {
		if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return new byte[0];
		if (sw == dw && sh == dh) return src.clone();

		final int fpShift = 14;
		final int fpOne = 1 << fpShift;
		final long round = 1L << fpShift * 2 - 1;

		int[] x0 = new int[dw];
		int[] x1 = new int[dw];
		int[] wx = new int[dw];
		int[] iwx = new int[dw];

		for (int dx = 0; dx < dw; dx++) {
			float fx = (dx + 0.5f) * sw / dw - 0.5f;
			int ix0 = (int) Math.floor(fx);
			int ix1 = ix0 + 1;
			float frac = fx - ix0;

			if (ix0 < 0) {
				ix0 = 0;
				frac = 0f;
			}
			if (ix1 >= sw) ix1 = sw - 1;

			int wFrac = (int) (frac * fpOne + 0.5f);
			x0[dx] = ix0 * 3;
			x1[dx] = ix1 * 3;
			wx[dx] = wFrac;
			iwx[dx] = fpOne - wFrac;
		}

		byte[] dst = new byte[dw * dh * 3];
		for (int dy = 0; dy < dh; dy++) {
			float fy = (dy + 0.5f) * sh / dh - 0.5f;
			int iy0 = (int) Math.floor(fy);
			int iy1 = iy0 + 1;
			float fracY = fy - iy0;

			if (iy0 < 0) {
				iy0 = 0;
				fracY = 0f;
			}
			if (iy1 >= sh) iy1 = sh - 1;

			int wy = (int) (fracY * fpOne + 0.5f);
			int iwy = fpOne - wy;
			int row0 = iy0 * sw * 3;
			int row1 = iy1 * sw * 3;

			for (int dx = 0; dx < dw; dx++) {
				int p00 = row0 + x0[dx];
				int p01 = row0 + x1[dx];
				int p10 = row1 + x0[dx];
				int p11 = row1 + x1[dx];
				int out = (dy * dw + dx) * 3;
				int wx0 = iwx[dx];
				int wx1 = wx[dx];

				for (int c = 0; c < 3; c++) {
					long top = (src[p00 + c] & 0xFF) * (long) wx0 + (src[p01 + c] & 0xFF) * (long) wx1;
					long bot = (src[p10 + c] & 0xFF) * (long) wx0 + (src[p11 + c] & 0xFF) * (long) wx1;
					int v = (int) (top * iwy + bot * wy + round >> fpShift * 2);
					if (v < 0) v = 0;
					else if (v > 255) v = 255;
					dst[out + c] = (byte) v;
				}
			}
		}

		return dst;
	}

	private static String itemId(ItemStack st) {
		if (st == null || st.isEmpty()) return "";
		ResourceLocation rl = st.getItem().getRegistryName();
		return rl != null ? rl.toString() : "";
	}

	private static void writeString(DataOutputStream out, String s) throws Exception {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(b.length);
		out.write(b);
	}

	private static class FrameData {
		final int w;
		final int h;
		final byte[] bgr;

		FrameData(int w, int h, byte[] bgr) {
			this.w = w;
			this.h = h;
			this.bgr = bgr;
		}
	}

	private static class ObservationSnapshot {
		final int tickSeq;
		final long dayTime;
		final float hp;
		final float hpMax;
		final int armor;
		final int hunger;
		final int air;
		final int selectedSlot;
		final String dimensionId;
		final String biomeId;
		final String biomeCategory;
		final String mainHandId;
		final String[] invItems;
		final String[] invArmor;
		final String[] invOffhand;
		final int frameW;
		final int frameH;
		final byte[] frameBgr;

		ObservationSnapshot(int tickSeq, long dayTime, float hp, float hpMax, int armor, int hunger, int air, int selectedSlot,
				String dimensionId, String biomeId, String biomeCategory, String mainHandId, String[] invItems, String[] invArmor,
				String[] invOffhand, int frameW, int frameH, byte[] frameBgr) {
			this.tickSeq = tickSeq;
			this.dayTime = dayTime;
			this.hp = hp;
			this.hpMax = hpMax;
			this.armor = armor;
			this.hunger = hunger;
			this.air = air;
			this.selectedSlot = selectedSlot;
			this.dimensionId = dimensionId;
			this.biomeId = biomeId;
			this.biomeCategory = biomeCategory;
			this.mainHandId = mainHandId;
			this.invItems = invItems;
			this.invArmor = invArmor;
			this.invOffhand = invOffhand;
			this.frameW = frameW;
			this.frameH = frameH;
			this.frameBgr = frameBgr;
		}
	}

	private static class HostPort {
		final String host;
		final int port;

		HostPort(String host, int port) {
			this.host = host;
			this.port = port;
		}

		static HostPort parse(String s) {
			if (s == null) return new HostPort("127.0.0.1", 5000);
			s = s.trim();
			int i = s.lastIndexOf(':');
			if (i <= 0 || i == s.length() - 1) return new HostPort("127.0.0.1", 5000);
			String host = s.substring(0, i).trim();
			int port;
			try {
				port = Integer.parseInt(s.substring(i + 1).trim());
			} catch (Exception e) {
				port = 5000;
			}
			return new HostPort(host.isEmpty() ? "127.0.0.1" : host, port);
		}
	}
}
