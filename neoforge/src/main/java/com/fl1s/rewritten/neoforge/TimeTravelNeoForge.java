package com.fl1s.rewritten.neoforge;

import com.fl1s.rewritten.timetravel.commands.LoopCommands;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.neoforge.events.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.fl1s.rewritten.timetravel.BlockJournal;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.network.Connection;
import java.lang.reflect.Field;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.fl1s.rewritten.timetravel.client.TimelineClient;
import com.fl1s.rewritten.timetravel.ChatTimeline;
import com.fl1s.rewritten.timetravel.MobJournal;
import com.fl1s.rewritten.timetravel.net.ToggleTimelinePayload;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import com.fl1s.rewritten.timetravel.net.ClearChatPayload;
import net.neoforged.neoforge.event.ServerChatEvent;
import com.fl1s.rewritten.timetravel.net.JumpRequestPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.net.TimelineSyncPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Mod(TimeTravel.MOD_ID)
public class TimeTravelNeoForge {

    public static final Logger LOOP_LOGGER = LoggerFactory.getLogger("TimeTravel");

    public TimeTravelNeoForge(IEventBus modEventBus) {
        LOOP_LOGGER.info("Initializing TimeTravel mod (NeoForge)");

        ModList modList = ModList.get();
        TimeTravel.voiceChatLoaded = modList.isLoaded("voicechat");
        TimeTravel.voiceInteractionLoaded = modList.isLoaded("vcinteraction");

        LOOP_LOGGER.info("Mod detection - voicechat: {}, vcinteraction: {}",
                TimeTravel.voiceChatLoaded, TimeTravel.voiceInteractionLoaded);

        // Register our own voice GameEvent (timetravel_rewritten:voice) only when
        // voicechat-interaction is installed. When vcinteraction is NOT
        // installed, we skip registration - no sculk/warden interaction.
        if (TimeTravel.voiceInteractionLoaded) {
            DeferredRegister<GameEvent> gameEvents =
                    DeferredRegister.create(Registries.GAME_EVENT, TimeTravel.MOD_ID);
            gameEvents.register("voice", () -> new GameEvent(16));
            gameEvents.register(modEventBus);
        }

        TimeTravel.init();

        modEventBus.addListener(this::registerPayloads);
        TimelineNet.toPlayerSender = (player, payload) -> PacketDistributor.sendToPlayer(player, payload);

        // Register gameplay event listeners to the NeoForge EVENT bus
        NeoForge.EVENT_BUS.register(this);

        LOOP_LOGGER.info("TimeTravel mod initialized successfully (NeoForge)");
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp) BlockJournal.watch(sp, event.getPos());
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer sp) BlockJournal.watch(sp, event.getPos());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer sp) BlockJournal.watchLookTarget(sp);
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        MobJournal.onEntityDeath(event.getEntity());
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ChatTimeline.onPlayerChat(event.getPlayer(), event.getRawText());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();

        registrar.playToClient(TimelineSyncPayload.TYPE, TimelineSyncPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> TimelineClient.onSync(payload)));

        registrar.playToClient(ClearChatPayload.TYPE, ClearChatPayload.CODEC,
                (payload, context) -> context.enqueueWork(TimelineClient::onClearChat));

        registrar.playToServer(ToggleTimelinePayload.TYPE, ToggleTimelinePayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        TimelineNet.handleToggleRequest(player);
                    }
                }));

        registrar.playToServer(JumpRequestPayload.TYPE, JumpRequestPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        TimelineNet.handleJumpRequest(player, payload.targetTick());
                    }
                }));
    }

    // Command registration handler
    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        LOOP_LOGGER.info("Registering TimeTravel commands (NeoForge)");
        LoopCommands.register(event.getDispatcher());
    }

    // Server Tick handler
    @SubscribeEvent
    public void onEndServerTick(ServerTickEvent.Post event) {
        TickNeoForgeEvent.onEndServerTick(event.getServer());
    }

    // Server Started handler
    @SubscribeEvent
    public void onServerStart(ServerStartedEvent event) {
        LifecycleNeoForgeEvent.onServerStart(event.getServer());
    }

    // Server Stopping handler
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LifecycleNeoForgeEvent.onServerStopping(event.getServer());
    }

    // Player Join handler
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // Cast the entity to ServerPlayer to access server-side fields/methods
        if (event.getEntity() instanceof ServerPlayer player) {
            // Get the network connection handler from the player
            ServerGamePacketListenerImpl connection = player.connection;
            MinecraftServer server = player.level().getServer(); // Get server from player
            
            PlayConnectionNeoForgeEvent.onJoin(connection, server);
        } else {
            LOOP_LOGGER.warn("PlayerLoggedInEvent received a non-ServerPlayer entity?");
        }
    }

    // Player Disconnect handler
    @SubscribeEvent
    public void onPlayerDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        // Cast the entity to ServerPlayer
        if (event.getEntity() instanceof ServerPlayer player) {
            // Get the network connection handler from the player
            ServerGamePacketListenerImpl connection = player.connection;
            
            PlayConnectionNeoForgeEvent.onDisconnect(connection);
        } else {
            LOOP_LOGGER.warn("PlayerLoggedOutEvent received a non-ServerPlayer entity?");
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNeoForgeEvent.dimensionChange(serverPlayer);
        }
    }

    // FIXME - remove when mocap updates as it implements its own EmbeddedChannel
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.getClass().getName().contains("FakePlayer")) {
                try {
                    Connection conn = player.connection.getConnection();
                    if (conn.channel() == null) {
                        Field channelField = Connection.class.getDeclaredField("channel");
                        channelField.setAccessible(true);
                        channelField.set(conn, new EmbeddedChannel());
                        LOOP_LOGGER.debug("Injected EmbeddedChannel into FakePlayer connection to prevent NeoForge crashes.");
                    }
                } catch (Exception e) {
                    LOOP_LOGGER.error("Failed to inject dummy channel into FakePlayer", e);
                }
            }
        }
    }
}