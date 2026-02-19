package net.krows_team.data_mod;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MyMod.MODID, value = Dist.CLIENT)
public class ClientStreamManager {
	private static volatile StreamSender sender;

	// (чисто чтобы видеть что живое) порядковый номер пакета
	private static final AtomicInteger SEQ = new AtomicInteger(0);

	@SubscribeEvent
	public static void onLogin(ClientPlayerNetworkEvent.LoggedInEvent e) {
		start();
	}

	@SubscribeEvent
	public static void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent e) {
		stop();
	}

	public static void start() {
		nu.pattern.OpenCV.loadLocally();
		stop();

		HostPort hp = HostPort.parse(ClientConfig.SERVER_ADDR.get());
		sender = new StreamSender(hp.host, hp.port);
		sender.start();
	}

	public static void stop() {
		if (sender != null) {
			sender.shutdown();
			sender = null;
		}
	}

	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent e) {
		if (e.phase != TickEvent.Phase.END || sender == null) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.isPaused()) return;

		try {
			byte[] packet = buildObservationPacket(mc, mc.player);
			sender.enqueue(packet);
		} catch (Throwable t) {
			// не валим игру из-за разовой ошибки чтения буфера/сети
		}
	}

	private static byte[] buildObservationPacket(Minecraft mc, ClientPlayerEntity p) throws Exception {
		// --------- 1) frame 128x128 RGB ----------
		byte[] rgb = captureFrame128RGB(mc);

		// --------- 2) прочие поля ----------
		float hp = p.getHealth();
		float hpMax = p.getMaxHealth();

		int armor = p.getArmorValue();
		int hunger = p.getFoodData().getFoodLevel();
		int air = p.getAirSupply();

		int selectedSlot = p.inventory.selected;
		ItemStack mainHand = p.getMainHandItem();
		String mainHandId = itemId(mainHand);

		// Dimension id
		String dimensionId = "";
		try {
			// world.dimension() -> RegistryKey<World>, location() -> ResourceLocation
			dimensionId = mc.level.dimension().location().toString();
		} catch (Throwable t) {
			dimensionId = "";
		}

		// Biome id + category
		Biome biome = mc.level.getBiome(p.blockPosition());

		String biomeId = "";
		try {
			ResourceLocation rl = biome.getRegistryName(); // <-- работает в Forge 1.16.5
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

		long dayTime = mc.level.getDayTime(); // абсолютное время в мире (можно %24000 на стороне сервера)

		// inventory: шлём registry ids по слотам (включая пустые как "")
		PlayerInventory inv = p.inventory;

		ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
		DataOutputStream out = new DataOutputStream(baos);

		// ---- header ----
		out.writeInt(0x4D524C31); // "MRL1" magic
		out.writeInt(SEQ.getAndIncrement());

		// ---- scalars ----
		out.writeLong(dayTime);
		out.writeFloat(hp);
		out.writeFloat(hpMax);
		out.writeInt(armor);
		out.writeInt(hunger);
		out.writeInt(air);
		out.writeInt(selectedSlot);

		writeString(out, dimensionId); // NEW
		writeString(out, biomeId); // biome registry id
		writeString(out, biomeCategory); // NEW

		writeString(out, mainHandId);

		// ---- inventory ----
		// main inventory (36)
		out.writeInt(inv.items.size());
		for (ItemStack st : inv.items) writeString(out, itemId(st));

		// armor (4)
		out.writeInt(inv.armor.size());
		for (ItemStack st : inv.armor) writeString(out, itemId(st));

		// offhand (обычно 1)
		out.writeInt(inv.offhand.size());
		for (ItemStack st : inv.offhand) writeString(out, itemId(st));

		// ---- image ----
		out.writeInt(128);
		out.writeInt(128);
		out.writeInt(rgb.length);
		out.write(rgb);

		out.flush();
		return baos.toByteArray();
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

	private static byte[] captureFrame128RGB(Minecraft mc) {
		int w = mc.getWindow().getWidth();
		int h = mc.getWindow().getHeight();

		NativeImage full = ScreenShotHelper.takeScreenshot(w, h, mc.getMainRenderTarget());

		// --- NativeImage -> Mat (RGB) ---
		Mat src = new Mat(h, w, CvType.CV_8UC3);

		byte[] row = new byte[w * 3];
		for (int y = 0; y < h; y++) {
			int idx = 0;
			for (int x = 0; x < w; x++) {
				int pixel = full.getPixelRGBA(x, y);
				row[idx++] = (byte) NativeImage.getB(pixel); // OpenCV default BGR
				row[idx++] = (byte) NativeImage.getG(pixel);
				row[idx++] = (byte) NativeImage.getR(pixel);
			}
			src.put(y, 0, row);
		}

		full.close();

		// --- resize exactly like cv2.INTER_LINEAR ---
		Mat dst = new Mat();
		Imgproc.resize(src, dst, new org.opencv.core.Size(128, 128), 0, 0, Imgproc.INTER_LINEAR);

		byte[] out = new byte[128 * 128 * 3];
		dst.get(0, 0, out);

		src.release();
		dst.release();

		return out; // BGR как в cv2
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
