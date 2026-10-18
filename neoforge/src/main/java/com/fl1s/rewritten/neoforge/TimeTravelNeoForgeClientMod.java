package com.fl1s.rewritten.neoforge;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = TimeTravel.MOD_ID, dist = Dist.CLIENT)
public class TimeTravelNeoForgeClientMod {
    public TimeTravelNeoForgeClientMod(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, TimeTravelConfigNeoForge.CLIENT_SPEC);

        if (!ModList.get().isLoaded("configured")) {
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
    }
}
