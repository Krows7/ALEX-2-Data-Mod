package net.krows_team.data_mod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(MyMod.MODID)
public class MyMod {
	public static final String MODID = "examplemod";

	public MyMod() {
		// TOML конфиг
		ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

		// Кнопка "Config" в меню Mods -> откроет твой экран
		ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
				() -> (mc, parent) -> new MyModConfigScreen(parent));

		// События/тики
		MinecraftForge.EVENT_BUS.register(ClientStreamManager.class);
	}
}
