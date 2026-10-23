package com.fl1s.rewritten.timetravel.compat.ironsspells;

import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class IrSpellsCompat {
    private IrSpellsCompat() {
    }

    private static Boolean loaded;

    private static Class<?> wispClass;
    private static Field wispOwnerUUID;
    private static Field wispCachedOwner;

    private static Class<?> magicSummonInterface;
    private static Method getSummonerMethod;
    private static Method summonManagerSetOwner;

    private static Method skeletonTriggerRise;
    private static Method zombieTriggerRise;

    private static final net.minecraft.resources.ResourceLocation CHAIN_LIGHTNING =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "chain_lightning");

    public static boolean isUntracked(Entity entity) {
        return entity != null && CHAIN_LIGHTNING.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static boolean isLoaded() {
        Boolean l = loaded;
        if (l != null) return l;
        try {
            Class.forName("io.redspace.ironsspellbooks.IronsSpellbooks");
            initReflection();
            loaded = Boolean.TRUE;
        } catch (Throwable t) {
            loaded = Boolean.FALSE;
        }
        return loaded;
    }

    private static void initReflection() {
        try {
            wispClass = Class.forName("io.redspace.ironsspellbooks.entity.spells.wisp.WispEntity");
            wispOwnerUUID = wispClass.getDeclaredField("ownerUUID");
            wispOwnerUUID.setAccessible(true);
            wispCachedOwner = wispClass.getDeclaredField("cachedOwner");
            wispCachedOwner.setAccessible(true);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] wisp reflection failed: {}", t.toString());
        }

        try {
            magicSummonInterface = Class.forName("io.redspace.ironsspellbooks.entity.mobs.IMagicSummon");
            getSummonerMethod = magicSummonInterface.getMethod("getSummoner");
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] IMagicSummon reflection failed: {}", t.toString());
        }

        try {
            Class<?> summonManagerClass = Class.forName("io.redspace.ironsspellbooks.capabilities.magic.SummonManager");
            summonManagerSetOwner = summonManagerClass.getMethod("setOwner", Entity.class, LivingEntity.class);
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] SummonManager reflection failed: {}", t.toString());
        }

        initRiseReflection();
    }

    public static void onRecord(Entity entity, CompoundTag tag) {
        if (!isLoaded() || entity == null || tag == null) return;
        if (tag.hasUUID("tt_owner")) return;

        if (wispClass != null && wispClass.isInstance(entity)) {
            try {
                UUID ownerUuid = (UUID) wispOwnerUUID.get(entity);
                if (ownerUuid != null) tag.putUUID("tt_owner", ownerUuid);
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] wisp record failed: {}", t.toString());
            }
            return;
        }

        if (magicSummonInterface != null && magicSummonInterface.isInstance(entity) && getSummonerMethod != null) {
            try {
                Entity owner = (Entity) getSummonerMethod.invoke(entity);
                if (owner != null) tag.putUUID("tt_owner", owner.getUUID());
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] summon record failed: {}", t.toString());
            }
        }
    }

    public static void onPuppetSpawned(Entity entity, CompoundTag tag, ServerLevel level) {
        if (!isLoaded() || entity == null || tag == null || level == null) return;
        if (!tag.hasUUID("tt_owner")) return;

        UUID ownerUuid;
        try {
            ownerUuid = tag.getUUID("tt_owner");
        } catch (Throwable t) {
            return;
        }

        if (wispClass != null && wispClass.isInstance(entity)) {
            try {
                Entity owner = level.getEntity(ownerUuid);
                wispOwnerUUID.set(entity, ownerUuid);
                wispCachedOwner.set(entity, owner);
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] wisp restore failed: {}", t.toString());
            }
            return;
        }

        if (magicSummonInterface != null && magicSummonInterface.isInstance(entity) && summonManagerSetOwner != null) {
            try {
                Entity owner = level.getEntity(ownerUuid);
                if (owner instanceof LivingEntity living) {
                    summonManagerSetOwner.invoke(null, entity, living);
                }
            } catch (Throwable t) {
                TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] summon restore failed: {}", t.toString());
            }
        }
    }

    private static void initRiseReflection() {
        try {
            Class<?> cls = Class.forName("io.redspace.ironsspellbooks.entity.mobs.SummonedSkeleton");
            skeletonTriggerRise = cls.getMethod("triggerRiseAnimation");
        } catch (Throwable ignored) {}
        try {
            Class<?> cls = Class.forName("io.redspace.ironsspellbooks.entity.mobs.SummonedZombie");
            zombieTriggerRise = cls.getMethod("triggerRiseAnimation");
        } catch (Throwable ignored) {}
    }

    public static void onRiseAnimation(Entity entity) {
        if (entity == null || skeletonTriggerRise == null && zombieTriggerRise == null) return;
        try {
            if (skeletonTriggerRise != null && skeletonTriggerRise.getDeclaringClass().isInstance(entity)) {
                skeletonTriggerRise.invoke(entity);
                return;
            }
            if (zombieTriggerRise != null && zombieTriggerRise.getDeclaringClass().isInstance(entity)) {
                zombieTriggerRise.invoke(entity);
            }
        } catch (Throwable t) {
            TimeTravel.TIMELINE_LOGGER.debug("[IrSpells] rise anim failed: {}", t.toString());
        }
    }
}
