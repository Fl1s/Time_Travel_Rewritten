package com.fl1s.rewritten.timetravel.client;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Exempt;
import com.fl1s.rewritten.timetravel.data.Levels;
import com.fl1s.rewritten.timetravel.journal.ExemptBlocks;
import com.fl1s.rewritten.timetravel.net.ExemptBlocksPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ExemptGlow {

    private ExemptGlow() {
    }

    private static final int RANGE = 32;
    private static final int RANGE_SQ = RANGE * RANGE;
    private static final int MAX_BLOCKS = 2048;
    private static final int SCAN_INTERVAL = 20;
    private static final int BLOCK_SPARKLE_CHANCE = 90;
    private static final int MAX_SPARKLES = 6;

    private static final Map<String, LongOpenHashSet> synced = new HashMap<>();
    private static List<BlockPos> nearby = List.of();
    private static int sinceScan = SCAN_INTERVAL;

    private static boolean enabled() {
        return TimeTravel.showExemptGlow;
    }

    public static void handle(ExemptBlocksPayload payload) {
        LongOpenHashSet set = synced.computeIfAbsent(payload.dim(), k -> new LongOpenHashSet());
        if (payload.reset()) set.clear();
        for (long pos : payload.removed()) set.remove(pos);
        for (long pos : payload.added()) set.add(pos);
        sinceScan = SCAN_INTERVAL;
    }

    public static void reset() {
        synced.clear();
        nearby = List.of();
        sinceScan = SCAN_INTERVAL;
    }

    public static boolean exemptAt(Level level, BlockPos pos, BlockState state) {
        if (ExemptBlocks.isType(state)) return true;
        LongOpenHashSet set = synced.get(Levels.canonicalId(level));
        return set != null && set.contains(pos.asLong());
    }

    public static boolean glows(BlockEntity be) {
        Level level = be.getLevel();
        return enabled() && level != null && level.isClientSide && exemptAt(level, be.getBlockPos(), be.getBlockState());
    }

    public static boolean glows(ItemStack stack) {
        return enabled() && !stack.isEmpty() && Exempt.is(stack);
    }

    public static MultiBufferSource wrap(MultiBufferSource inner) {
        MultiBufferSource.BufferSource main = Minecraft.getInstance().renderBuffers().bufferSource();
        return type -> ExemptRenderTypes.isGlint(type) ? inner.getBuffer(type)
                : VertexMultiConsumer.create(main.getBuffer(ExemptRenderTypes.itemFor(type)), inner.getBuffer(type));
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (!enabled() || level == null || mc.player == null) {
            nearby = List.of();
            return;
        }
        if (++sinceScan >= SCAN_INTERVAL) {
            sinceScan = 0;
            nearby = scan(level, mc.player.blockPosition());
        }
        sparkle(level);
    }

    private static boolean near(BlockPos center, long packed) {
        long dx = BlockPos.getX(packed) - center.getX();
        long dy = BlockPos.getY(packed) - center.getY();
        long dz = BlockPos.getZ(packed) - center.getZ();
        return dx * dx + dy * dy + dz * dz <= RANGE_SQ;
    }

    private static List<BlockPos> scan(ClientLevel level, BlockPos center) {
        LongOpenHashSet seen = new LongOpenHashSet();
        List<BlockPos> found = new ArrayList<>();
        LongOpenHashSet marked = synced.get(Levels.canonicalId(level));
        if (marked != null) {
            for (LongIterator it = marked.iterator(); it.hasNext(); ) {
                long packed = it.nextLong();
                if (!near(center, packed)) continue;
                BlockPos pos = BlockPos.of(packed);
                if (!level.getBlockState(pos).isAir() && seen.add(packed)) found.add(pos);
            }
        }

        int minX = SectionPos.blockToSectionCoord(center.getX() - RANGE);
        int maxX = SectionPos.blockToSectionCoord(center.getX() + RANGE);
        int minZ = SectionPos.blockToSectionCoord(center.getZ() - RANGE);
        int maxZ = SectionPos.blockToSectionCoord(center.getZ() + RANGE);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section.hasOnlyAir() || !section.maybeHas(ExemptBlocks::isType)) continue;
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
                    if (Math.abs(baseY + 8 - center.getY()) > RANGE + 8) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!ExemptBlocks.isType(section.getBlockState(x, y, z))) continue;
                                long packed = BlockPos.asLong(SectionPos.sectionToBlockCoord(cx) + x, baseY + y,
                                        SectionPos.sectionToBlockCoord(cz) + z);
                                if (near(center, packed) && seen.add(packed)) found.add(BlockPos.of(packed));
                            }
                        }
                    }
                }
            }
        }

        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.size() > MAX_BLOCKS ? List.copyOf(found.subList(0, MAX_BLOCKS)) : found;
    }

    private static void sparkle(ClientLevel level) {
        RandomSource random = level.random;
        int budget = MAX_SPARKLES;
        for (BlockPos pos : nearby) {
            if (budget == 0) return;
            if (random.nextInt(BLOCK_SPARKLE_CHANCE) != 0 || level.getBlockState(pos).isAir()) continue;
            level.addParticle(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() - 0.1 + random.nextDouble() * 1.2,
                    pos.getY() + random.nextDouble() * 1.1,
                    pos.getZ() - 0.1 + random.nextDouble() * 1.2, 0.0, 0.0, 0.0);
            budget--;
        }
    }

    public static void renderWorld(PoseStack poseStack, Vec3 camera) {
        if (!enabled() || poseStack == null || nearby.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(ExemptRenderTypes.BLOCK);
        for (BlockPos pos : nearby) {
            BlockState state = level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            Vec3 offset = state.getOffset(level, pos);
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x + offset.x, pos.getY() - camera.y + offset.y,
                    pos.getZ() - camera.z + offset.z);
            blocks.getModelRenderer().renderModel(poseStack.last(), consumer, state, blocks.getBlockModel(state),
                    1.0F, 1.0F, 1.0F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        buffers.endBatch(ExemptRenderTypes.BLOCK);
    }
}
