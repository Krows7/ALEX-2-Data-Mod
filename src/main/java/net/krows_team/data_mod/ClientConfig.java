package net.krows_team.data_mod;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client-side Forge config definitions used by the mod.
 */
public class ClientConfig {
	/** Built configuration specification. */
	public static final ForgeConfigSpec SPEC;
	/** Target server address in {@code host:port} format. */
	public static ForgeConfigSpec.ConfigValue<String> SERVER_ADDR;
	/** Enables SSH tunnel mode for server connection. */
	public static ForgeConfigSpec.ConfigValue<Boolean> SSH_ENABLED;
	/** SSH server address in {@code host:port} format. */
	public static ForgeConfigSpec.ConfigValue<String> SSH_ADDR;
	/** SSH username used to open the tunnel. */
	public static ForgeConfigSpec.ConfigValue<String> SSH_USER;
	/** Path to SSH private key file. */
	public static ForgeConfigSpec.ConfigValue<String> SSH_KEY_PATH;
	/** Number of worker threads used for frame processing. */
	public static ForgeConfigSpec.IntValue PROCESSING_THREADS;
	/** Flush strategy: flush socket output once per N packets. */
	public static ForgeConfigSpec.IntValue FLUSH_EVERY_N;

	static {
		ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
		b.push("client");

		SERVER_ADDR = b.comment("Address host:port, e.g. 127.0.0.1:5000").define("serverAddr", "127.0.0.1:5000");
		SSH_ENABLED = b.comment("Enable SSH tunnel for connection").define("sshEnabled", false);
		SSH_ADDR = b.comment("SSH host:port, e.g. 10.0.0.2:22").define("sshAddr", "");
		SSH_USER = b.comment("SSH username").define("sshUser", "");
		SSH_KEY_PATH = b.comment("Path to private key, e.g. ~/.ssh/id_rsa").define("sshKeyPath", "");
		PROCESSING_THREADS = b.comment("Number of threads for frame resize processing").defineInRange("processingThreads",
				Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 1, 32);
		FLUSH_EVERY_N = b.comment("Flush socket output every N packets (1 = flush every packet)").defineInRange("flushEveryN", 1, 1, 1024);

		b.pop();
		SPEC = b.build();
	}
}
