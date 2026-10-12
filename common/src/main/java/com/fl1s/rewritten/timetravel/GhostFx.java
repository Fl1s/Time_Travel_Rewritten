package com.fl1s.rewritten.timetravel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class GhostFx {
    private final Entity entity;
    public boolean killHandled;
    public long graceUntil = -1;
    private boolean hasLast;
    private double lastX, lastZ;
    private float moveDist;
    private float nextStep = 1.0f;
    private int useTicks;
    private UseAnim lastUseAnim = UseAnim.NONE;

    public GhostFx(Entity entity) {
        this.entity = entity;
    }

    public boolean isDead() {
        return entity instanceof LivingEntity living && !entity.isRemoved() && living.isDeadOrDying();
    }

    public void tick() {
        if (entity == null || entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) return;
        if (entity.getY() > 100000) {
            hasLast = false;
            return;
        }
        footsteps(level);
        eating(level);
    }

    private void footsteps(ServerLevel level) {
        if (hasLast) {
            double dx = entity.getX() - lastX;
            double dz = entity.getZ() - lastZ;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > 0.0 && dist < 2.0 && entity.onGround() && !entity.isInWater()
                    && !entity.isCrouching() && !entity.isSpectator()) {
                moveDist += (float) dist * 0.6f;
                if (moveDist > nextStep) {
                    nextStep = (int) moveDist + 1;
                    playStep(level);
                }
            }
        }
        lastX = entity.getX();
        lastZ = entity.getZ();
        hasLast = true;
    }

    private void playStep(ServerLevel level) {
        BlockPos below = entity.getOnPos();
        BlockState state = level.getBlockState(below);
        if (state.isAir()) return;

        SoundType type = state.getSoundType();
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                type.getStepSound(), SoundSource.PLAYERS, type.getVolume() * 0.15f, type.getPitch());
    }

    private void eating(ServerLevel level) {
        if (entity instanceof LivingEntity living && living.isUsingItem()) {
            ItemStack stack = living.getItemInHand(living.getUsedItemHand());
            UseAnim anim = stack.getUseAnimation();
            useTicks++;
            lastUseAnim = anim;

            if (anim == UseAnim.EAT && useTicks > 7 && useTicks % 4 == 0) {
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        stack.getEatingSound(), SoundSource.PLAYERS,
                        0.5f + 0.5f * level.random.nextInt(2),
                        (level.random.nextFloat() - level.random.nextFloat()) * 0.2f + 1.0f);
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack),
                        entity.getX(), entity.getEyeY() - 0.1, entity.getZ(), 5, 0.1, 0.1, 0.1, 0.0);
            } else if (anim == UseAnim.DRINK && useTicks % 4 == 0) {
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        stack.getDrinkingSound(), SoundSource.PLAYERS, 0.5f,
                        level.random.nextFloat() * 0.1f + 0.9f);
            }
        } else {
            //yeah that was the game changing feature
            if (useTicks >= 20 && lastUseAnim == UseAnim.EAT) {
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                        SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.5f,
                        level.random.nextFloat() * 0.1f + 0.9f);
            }
            useTicks = 0;
            lastUseAnim = UseAnim.NONE;
        }
    }

    public static Entity findNewestGhost(String nickname) {
        if (nickname == null || TimeTravel.server == null) return null;

        Set<UUID> real = new HashSet<>();
        for (ServerPlayer sp : TimeTravel.server.getPlayerList().getPlayers()) real.add(sp.getUUID());

        Entity best = null;
        for (ServerLevel level : TimeTravel.server.getAllLevels()) {
            for (ServerPlayer sp : level.players()) {
                if (real.contains(sp.getUUID())) continue;
                if (!nickname.equals(sp.getGameProfile().getName())) continue;
                if (best == null || sp.getId() > best.getId()) best = sp;
            }
        }
        return best;
    }
}
