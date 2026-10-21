package com.fl1s.rewritten.fabric;

import com.fl1s.rewritten.timetravel.client.CloneVanishFx;
import com.fl1s.rewritten.timetravel.client.TimelineClient;
import com.fl1s.rewritten.timetravel.net.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;

public class TimeTravelFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TimeTravelConfigFabric.loadClient();

        for (KeyMapping key : TimelineClient.ALL_KEYS) {
            KeyBindingHelper.registerKeyBinding(key);
        }

        ClientPlayNetworking.registerGlobalReceiver(TimelineSyncPayload.TYPE,
                (payload, context) -> TimelineClient.onSync(payload));
        ClientPlayNetworking.registerGlobalReceiver(WardenAnimPayload.TYPE,
                (payload, context) -> WardenAnimClient.handle(payload));
        ClientPlayNetworking.registerGlobalReceiver(CloneVanishPayload.TYPE, (payload, context) -> CloneVanishFx.handle(payload));
        ClientPlayNetworking.registerGlobalReceiver(DragonDeathPayload.TYPE,
                (payload, context) -> DragonDeathClient.handle(payload));
        ClientPlayNetworking.registerGlobalReceiver(CloneAppearPayload.TYPE, (payload, context) -> CloneVanishFx.handleAppear(payload));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> CloneVanishFx.renderWorld(context.matrixStack(), context.camera().getPosition(), context.tickCounter().getGameTimeDeltaPartialTick(false)));

        ClientPlayNetworking.registerGlobalReceiver(ClearChatPayload.TYPE,
                (payload, context) -> TimelineClient.onClearChat());

        TimelineNet.toServerSender = payload -> {
            if (ClientPlayNetworking.canSend(payload.type())) {
                ClientPlayNetworking.send(payload);
            }
        };

        ClientTickEvents.END_CLIENT_TICK.register(TimelineClient::clientTick);
        HudRenderCallback.EVENT.register(TimelineClient::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TimelineClient.reset());
    }
}
