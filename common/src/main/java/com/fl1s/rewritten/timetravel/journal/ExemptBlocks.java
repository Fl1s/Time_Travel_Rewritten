package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.net.ExemptBlocksPayload;
import com.fl1s.rewritten.timetravel.net.TimelineNet;
import com.fl1s.rewritten.timetravel.util.JsonStore;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExemptBlocks {
    private ExemptBlocks() {
    }

    public static final TagKey<Block> TYPES = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "exempt"));
    public static final int MAX_REGION = 32768;

    public enum Reason { POSITION, BLOCK, TYPE }

    private record Key(String dim, long pos) {}

    private static final class Saved {
        List<Spot> positions = new ArrayList<>();
        List<Spot> blocks = new ArrayList<>();
    }

    private static final class Spot {
        String dim;
        long pos;
        String block;
    }

    private static final Type SAVED_TYPE = new TypeToken<Saved>() {}.getType();

    private static final Set<Key> positions = new HashSet<>();
    private static final Map<Key, Block> blocks = new HashMap<>();
    private static final Set<Key> broken = new HashSet<>();
    private static final Set<Key> touched = new HashSet<>();
    private static final int SYNC_CHUNK = 65536;
    private static final long[] NONE = new long[0];
    private static boolean placing;
    private static int placingDepth;
    private static int setDepth;
    private static boolean dirty;

    private static Key key(Level level, BlockPos pos) {
        return new Key(Levels.canonicalId(level), pos.asLong());
    }

    public static boolean isType(BlockState state) {
        return state != null && state.is(TYPES);
    }

    public static boolean flagged(BlockEntity be) {
        if (be == null) return false;
        CustomData data = be.components().get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(Exempt.TAG);
    }

    public static boolean at(String dim, long pos) {
        if (positions.isEmpty() && blocks.isEmpty()) return false;
        Key key = new Key(dim, pos);
        return positions.contains(key) || blocks.containsKey(key);
    }

    public static boolean at(Level level, BlockPos pos) {
        if (at(Levels.canonicalId(level), pos.asLong())) return true;
        return level.isLoaded(pos) && isType(level.getBlockState(pos));
    }

    public static boolean recentlyAt(Level level, BlockPos pos) {
        return at(level, pos) || (!broken.isEmpty() && broken.contains(key(level, pos)));
    }

    public static boolean skips(String dim, long pos, BlockState a, BlockState b) {
        return isType(a) || isType(b) || at(dim, pos);
    }

    public static boolean skips(Level level, BlockPos pos, BlockState a, BlockState b) {
        return skips(Levels.canonicalId(level), pos.asLong(), a, b);
    }

    public static boolean covers(BlockEntity be) {
        if (be == null) return false;
        if (isType(be.getBlockState()) || flagged(be)) return true;
        Level level = be.getLevel();
        return level != null && at(Levels.canonicalId(level), be.getBlockPos().asLong());
    }

    public static Reason reason(Level level, BlockPos pos) {
        Key key = key(level, pos);
        if (positions.contains(key)) return Reason.POSITION;
        if (blocks.containsKey(key) || flagged(level.getBlockEntity(pos))) return Reason.BLOCK;
        if (isType(level.getBlockState(pos))) return Reason.TYPE;
        return null;
    }

    public static void onBlockEntity(BlockEntity be) {
        if (!flagged(be) || !(be.getLevel() instanceof ServerLevel level)) return;
        adopt(level, be.getBlockPos(), be.getBlockState().getBlock());
    }

    private static void adopt(Level level, BlockPos pos, Block block) {
        Key key = key(level, pos);
        if (block.equals(blocks.get(key))) return;
        blocks.put(key, block);
        touched.add(key);
        dirty = true;
        HeadWorldView.forget(key.dim(), Set.of(key.pos()));
    }

    public static void beginPlacing(ItemStack stack) {
        if (placingDepth++ == 0) placing = Exempt.is(stack);
    }

    public static void endPlacing() {
        if (placingDepth > 0 && --placingDepth == 0) placing = false;
    }

    public static void beforeSet(Level level, BlockPos pos, BlockState requested) {
        if (setDepth++ == 0 && placing && !requested.isAir()) adopt(level, pos, requested.getBlock());
    }

    public static void afterSet(Level level, BlockPos pos) {
        if (setDepth > 0) setDepth--;
        if (blocks.isEmpty()) return;
        Key key = key(level, pos);
        Block block = blocks.get(key);
        if (block == null || level.getBlockState(pos).is(block)) return;
        blocks.remove(key);
        broken.add(key);
        touched.add(key);
        dirty = true;
    }

    public static void markDrops(LootParams.Builder params, List<ItemStack> drops) {
        if (drops.isEmpty()) return;
        Vec3 origin = params.getOptionalParameter(LootContextParams.ORIGIN);
        if (origin == null) return;
        Key key = key(params.getLevel(), BlockPos.containing(origin));
        boolean exempt = blocks.containsKey(key) || broken.contains(key)
                || flagged(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY));
        if (!exempt) return;
        for (ItemStack stack : drops) Exempt.mark(stack, true);
    }

    public static int mark(ServerLevel level, BlockPos from, BlockPos to, boolean exempt) {
        String dim = Levels.canonicalId(level);
        Set<Long> changed = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            Key key = new Key(dim, pos.asLong());
            if (exempt ? positions.add(key) : positions.remove(key)) {
                changed.add(key.pos());
                touched.add(key);
            }
        }
        if (changed.isEmpty()) return 0;
        dirty = true;
        if (exempt) HeadWorldView.forget(dim, changed);
        return changed.size();
    }

    public static void sendTo(ServerPlayer player) {
        String dim = Levels.canonicalId(player.level());
        LongArrayList all = new LongArrayList();
        for (Key key : positions) if (key.dim().equals(dim)) all.add(key.pos());
        for (Key key : blocks.keySet()) if (key.dim().equals(dim) && !positions.contains(key)) all.add(key.pos());
        send(player, dim, true, all.toLongArray(), NONE);
    }

    private static void send(ServerPlayer player, String dim, boolean reset, long[] added, long[] removed) {
        int from = 0;
        do {
            long[] part = from == 0 && added.length <= SYNC_CHUNK ? added
                    : Arrays.copyOfRange(added, from, Math.min(added.length, from + SYNC_CHUNK));
            TimelineNet.sendToPlayer(player, new ExemptBlocksPayload(dim, reset && from == 0, part, from == 0 ? removed : NONE));
            from += SYNC_CHUNK;
        } while (from < added.length);
    }

    private static void flush() {
        if (touched.isEmpty() || TimeTravel.server == null) return;
        Map<String, LongArrayList> added = new HashMap<>();
        Map<String, LongArrayList> removed = new HashMap<>();
        for (Key key : touched) {
            (at(key.dim(), key.pos()) ? added : removed).computeIfAbsent(key.dim(), d -> new LongArrayList()).add(key.pos());
        }
        touched.clear();
        for (ServerPlayer player : TimeTravel.server.getPlayerList().getPlayers()) {
            if (Clones.is(player)) continue;
            String dim = Levels.canonicalId(player.level());
            LongArrayList add = added.get(dim);
            LongArrayList remove = removed.get(dim);
            if (add == null && remove == null) continue;
            send(player, dim, false, add == null ? NONE : add.toLongArray(), remove == null ? NONE : remove.toLongArray());
        }
    }

    public static void tick() {
        flush();
        broken.clear();
        placing = false;
        placingDepth = 0;
        setDepth = 0;
    }

    private static Path file() {
        return TimeTravel.worldFolder.resolve("timetravel_rewritten_exempt_blocks.json");
    }

    public static void load() {
        positions.clear();
        blocks.clear();
        broken.clear();
        dirty = false;
        if (TimeTravel.worldFolder == null) return;
        Saved saved = JsonStore.load(file(), SAVED_TYPE);
        if (saved == null) return;
        if (saved.positions != null) {
            for (Spot spot : saved.positions) positions.add(new Key(spot.dim, spot.pos));
        }
        if (saved.blocks != null) {
            for (Spot spot : saved.blocks) {
                ResourceLocation id = ResourceLocation.tryParse(spot.block);
                Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
                if (block != Blocks.AIR) blocks.put(new Key(spot.dim, spot.pos), block);
            }
        }
    }

    public static void save() {
        if (TimeTravel.worldFolder == null) return;
        Saved saved = new Saved();
        for (Key key : positions) saved.positions.add(spot(key, null));
        for (Map.Entry<Key, Block> entry : blocks.entrySet()) {
            saved.blocks.add(spot(entry.getKey(), BuiltInRegistries.BLOCK.getKey(entry.getValue()).toString()));
        }
        if (JsonStore.save(file(), saved, SAVED_TYPE)) dirty = false;
    }

    public static void saveIfDirty() {
        if (dirty) save();
    }

    private static Spot spot(Key key, String block) {
        Spot spot = new Spot();
        spot.dim = key.dim();
        spot.pos = key.pos();
        spot.block = block;
        return spot;
    }
}
