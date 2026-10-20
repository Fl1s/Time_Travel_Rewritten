package com.fl1s.rewritten.neoforge;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import com.fl1s.rewritten.timetravel.client.TimelineClient;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

public class TimeTravelNeoForgeClient {

    @EventBusSubscriber(modid = TimeTravel.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            for (KeyMapping key : TimelineClient.ALL_KEYS) {
                event.register(key);
            }
            TimelineNet.toServerSender = payload -> PacketDistributor.sendToServer(payload);
        }

        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "timeline"), TimelineClient::render);
        }
    }

    @EventBusSubscriber(modid = TimeTravel.MOD_ID, value = Dist.CLIENT)
    public static class GameBus {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            TimelineClient.clientTick(Minecraft.getInstance());
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            CloneVanishFx.renderWorld(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            TimelineClient.reset();
        }
    }
}
