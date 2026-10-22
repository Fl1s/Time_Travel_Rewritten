package com.fl1s.rewritten.timetravel.api;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.ReplayState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

//omg my first time writing an actual mod api!! =3
public final class TimelineApi {
    private TimelineApi() {
    }

    public static final int API_MAJOR = 0;
    public static final int API_MINOR = 11;
    public static final int API_PATCH = 2;
    public static final String API_VERSION = API_MAJOR + "." + API_MINOR + "." + API_PATCH;

    private static final List<Predicate<BlockEntity>> BLOCK_ENTITY_EXCLUSIONS = new CopyOnWriteArrayList<>();

    public static String apiVersion() {
        return API_VERSION;
    }

    public static boolean isCompatible(int requiredMajor, int requiredMinor) {
        if (requiredMajor != API_MAJOR) return false;
        return API_MAJOR == 0 ? requiredMinor == API_MINOR : requiredMinor <= API_MINOR;
    }

    public static void addListener(TimelineListener listener) {
        TimelineEventBus.add(listener);
    }

    public static void removeListener(TimelineListener listener) {
        TimelineEventBus.remove(listener);
    }

    public static boolean isLooping() {
        return TimeTravel.isLooping;
    }

    public static long tick() {
        return TimeTravel.timelineTick;
    }

    public static boolean isReplaying() {
        return ReplayState.isReplaying();
    }

    public static boolean isClone(Object o) {
        return Clones.is(o);
    }

    public static boolean isRealPlayer(ServerPlayer player) {
        return TimeTravel.isRealPlayer(player);
    }

    public static void excludeFakePlayerPrefix(String classNamePrefix) {
        Clones.excludePrefix(classNamePrefix);
    }

    public static void excludeBlockEntity(Predicate<BlockEntity> predicate) {
        if (predicate != null) BLOCK_ENTITY_EXCLUSIONS.add(predicate);
    }

    public static boolean isBlockEntityExcluded(BlockEntity be) {
        for (Predicate<BlockEntity> p : BLOCK_ENTITY_EXCLUSIONS) {
            try {
                if (p.test(be)) return true;
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.error("Block entity exclusion predicate failed", t);
            }
        }
        return false;
    }

    public static void registerJournal(String name, Runnable load, Runnable save, Runnable saveIfDirty, Runnable clear, Runnable rebuildUpcoming, Runnable tick, BiConsumer<Long, Long> onJump, Consumer<Set<Integer>> eraseVisits) {
        TimeTravel.JOURNALS.register(name, load, save, saveIfDirty, clear, rebuildUpcoming, tick, onJump, eraseVisits);
    }
}
