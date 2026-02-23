package net.krows_team.data_mod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Main Forge entry point of the mod.
 * <p>
 * Registers client configuration, config GUI screen and client-side event handlers.
 */
@Mod(DataMod.MODID)
public class DataMod {
	/** Mod identifier used in Forge annotations and resources. */
	public static final String MODID = "data_mod";

	/**
	 * Creates mod bootstrap and registers client systems.
	 */
	public DataMod() {
		ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

		ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
				() -> (mc, parent) -> new DataModConfigScreen(parent));

		MinecraftForge.EVENT_BUS.register(ClientStreamManager.class);
	}
}
