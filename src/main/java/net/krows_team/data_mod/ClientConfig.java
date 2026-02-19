package net.krows_team.data_mod;

import net.minecraftforge.common.ForgeConfigSpec;

public class ClientConfig {
	public static final ForgeConfigSpec SPEC;
	public static ForgeConfigSpec.ConfigValue<String> SERVER_ADDR;

	static {
		ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
		b.push("client");

		SERVER_ADDR = b.comment("Address host:port, e.g. 127.0.0.1:5000").define("serverAddr", "127.0.0.1:5000");

		b.pop();
		SPEC = b.build();
	}
}
