package com.fl1s.rewritten.timetravel.journal;

import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.mob.MobTrailJournal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which replay puppets must be completely inert on the server.
 */
public final class PuppetGuard {
    private PuppetGuard() {
    }

    public static final TagKey<EntityType<?>> INERT_PUPPETS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(TimeTravel.MOD_ID, "inert_puppets"));

    private static final Set<String> INERT_NAMESPACES = ConcurrentHashMap.newKeySet();

    static {
        INERT_NAMESPACES.add("irons_spellbooks");
    }

    public static void addInertNamespace(String namespace) {
        if (namespace != null && !namespace.isEmpty()) INERT_NAMESPACES.add(namespace);
    }

    private static boolean tickingPuppetMob;

    public static boolean isInert(Entity e) {
        if (e == null || e instanceof Mob) return false;
        if (!ProjectileJournal.isPuppeted(e) && !TrackedEntityJournal.isPuppeted(e)) return false;
        EntityType<?> type = e.getType();
        if (type.is(INERT_PUPPETS)) return true;
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return INERT_NAMESPACES.contains(key.getNamespace());
    }

    public static void beginTick(Entity e) {
        tickingPuppetMob = e instanceof Mob m && MobTrailJournal.isPuppeted(m);
    }

    public static void endTick() {
        tickingPuppetMob = false;
    }

    public static boolean isTickingPuppetMob() {
        return tickingPuppetMob;
    }
}
