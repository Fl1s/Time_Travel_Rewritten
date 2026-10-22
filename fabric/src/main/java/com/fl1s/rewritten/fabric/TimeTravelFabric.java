package com.fl1s.rewritten.fabric;

import com.fl1s.rewritten.fabric.events.LifecycleFabricEvent;
import com.fl1s.rewritten.fabric.events.PlayConnectionFabricEvent;
import com.fl1s.rewritten.fabric.events.PlayerFabricEvent;
import com.fl1s.rewritten.fabric.events.TickFabricEvent;
import com.fl1s.rewritten.timetravel.*;
import com.fl1s.rewritten.timetravel.commands.TimelineCommands;
import com.fl1s.rewritten.timetravel.journal.BlockJournal;
import com.fl1s.rewritten.timetravel.mob.MobJournal;
import com.fl1s.rewritten.timetravel.mob.MobStateJournal;
import com.fl1s.rewritten.timetravel.net.*;
import com.fl1s.rewritten.timetravel.timeline.ChatTimeline;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gameevent.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TimeTravelFabric implements ModInitializer {
    public static final Logger TIMELINE_LOGGER = LoggerFactory.getLogger("TimeTravel");
    
    @Override
    public void onInitialize() {
        TIMELINE_LOGGER.info("Initializing TimeTravel mod (Fabric)");

        FabricLoader loader = FabricLoader.getInstance();

        TimeTravel.voiceChatLoaded = loader.isModLoaded("voicechat");
        TimeTravel.voiceInteractionLoaded = loader.isModLoaded("vcinteraction");

        TIMELINE_LOGGER.info("Mod detection - voicechat: {}, vcinteraction: {}",
                TimeTravel.voiceChatLoaded, TimeTravel.voiceInteractionLoaded);

        // Register our own voice GameEvent (timetravel_rewritten:voice) only when
        // voicechat-interaction is installed. We reuse voiceinteraction's
        // sculk frequency mapping for its event, but need our own event
        // for replay entities. When vcinteraction is NOT installed,
        // we skip registration entirely - no sculk/warden interaction.
        if (TimeTravel.voiceInteractionLoaded) {
            Registry.registerForHolder(
                    BuiltInRegistries.GAME_EVENT,
                    ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "voice"),
                    new GameEvent(16)
            );
        }

        TimeTravel.init();

        PayloadTypeRegistry.playS2C().register(TimelineSyncPayload.TYPE, TimelineSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WardenAnimPayload.TYPE, WardenAnimPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DragonDeathPayload.TYPE, DragonDeathPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StopDragonSoundPayload.TYPE, StopDragonSoundPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClearChatPayload.TYPE, ClearChatPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CloneVanishPayload.TYPE, CloneVanishPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CloneAppearPayload.TYPE, CloneAppearPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(JumpRequestPayload.TYPE, JumpRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ToggleTimelinePayload.TYPE, ToggleTimelinePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ToggleTimelinePayload.TYPE,
                (payload, context) -> TimelineNet.handleToggleRequest(context.player()));

        ServerLivingEntityEvents.AFTER_DEATH.register(MobJournal::onEntityDeath);

        ServerEntityEvents.EQUIPMENT_CHANGE.register((livingEntity, equipmentSlot, previousStack, currentStack) -> {
            if (livingEntity instanceof Mob mob) {
                MobStateJournal.onChanged(mob);}});

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                ChatTimeline.onPlayerChat(sender, message.signedContent()));

        ServerPlayNetworking.registerGlobalReceiver(JumpRequestPayload.TYPE,
                (payload, context) -> TimelineNet.handleJumpRequest(context.player(), payload.targetTick()));

        TimelineNet.toPlayerSender = (player, payload) -> {
            if (ServerPlayNetworking.canSend(player, payload.type())) {
                ServerPlayNetworking.send(player, payload);
            }
        };

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Only register commands on the logical server
            if (environment.includeDedicated || environment.includeIntegrated) {
                TimelineCommands.register(dispatcher);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(LifecycleFabricEvent::onServerStart);

        ServerLifecycleEvents.SERVER_STOPPING.register(LifecycleFabricEvent::onServerStopping);

        ServerPlayConnectionEvents.JOIN.register(PlayConnectionFabricEvent::onJoin);

        ServerPlayConnectionEvents.DISCONNECT.register(PlayConnectionFabricEvent::onDisconnect);

        ServerTickEvents.END_SERVER_TICK.register(TickFabricEvent::onEndServerTick);

        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) BlockJournal.watch(sp, pos);
            return true;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (player instanceof ServerPlayer sp) BlockJournal.watch(sp, hitResult.getBlockPos());
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (player instanceof ServerPlayer sp) BlockJournal.watchLookTarget(sp);
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(PlayerFabricEvent::dimensionChange);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> PlayerFabricEvent.respawn(newPlayer));

        TIMELINE_LOGGER.info("TimeTravel mod initialized successfully (Fabric)");
    }
    
}