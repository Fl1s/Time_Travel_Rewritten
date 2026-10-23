package com.fl1s.rewritten.timetravel.clone;

import com.fl1s.rewritten.timetravel.compat.anim.AttackRelay;
import com.fl1s.rewritten.timetravel.compat.anim.BetterCombatIdleRelay;
import com.fl1s.rewritten.timetravel.data.PlayerData;
import com.fl1s.rewritten.timetravel.TimeTravel;
import com.fl1s.rewritten.timetravel.data.Visit;
import com.fl1s.rewritten.timetravel.data.Clones;
import com.fl1s.rewritten.timetravel.entity.FishingHookExt;
import com.fl1s.rewritten.timetravel.util.TimelineSceneManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class CloneTracks {
    private CloneTracks() {
    }

    public static final int FISH_CAST = 0;
    public static final int FISH_BITE = 1;
    public static final int FISH_REEL = 2;

    private static UUID reeling;

    private static Visit recordedVisit(ServerPlayer player) {
        if (!TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return null;
        if (!TimeTravel.isRealPlayer(player)) return null;
        PlayerData pd = TimeTravel.timelineSceneManager.getRecordingPlayer(player.getGameProfile().getName());
        if (pd == null || !pd.getActive()) return null;
        return pd.getCurrentVisit();
    }

    public static void onPearlThrown(ServerPlayer player) {
        Visit visit = recordedVisit(player);
        if (visit == null) return;
        if (visit.pearls == null) visit.pearls = new ArrayList<>();
        visit.pearls.add(TimeTravel.timelineTick);
    }

    private static void applyPearls(Visit visit, ServerPlayer clone, long now) {
        List<Long> list = visit.pearls;
        if (list == null || list.isEmpty()) return;
        if (Collections.binarySearch(list, now) < 0) return;
        if (!(clone.level() instanceof ServerLevel level)) return;
        ThrownEnderpearl pearl = new ThrownEnderpearl(level, clone);
        pearl.setItem(new ItemStack(Items.ENDER_PEARL));
        pearl.shootFromRotation(clone, clone.getXRot(), clone.getYRot(), 0.0F, 1.5F, 0.0F);
        level.addFreshEntity(pearl);
    }

    public static void poll(ServerPlayer player, PlayerData pd) {
        Visit visit = pd.getCurrentVisit();
        if (visit == null) return;
        long tick = TimeTravel.timelineTick;

        int mode = player.gameMode.getGameModeForPlayer().getId();
        boolean flying = player.getAbilities().flying;
        if (visit.lastMode != mode || visit.lastFlying != flying) {
            visit.modes.add(new Visit.ModeChange(tick, mode, flying));
            visit.lastMode = mode;
            visit.lastFlying = flying;
        }

        Entity vehicle = player.getVehicle();
        String vehicleId = vehicle == null ? null : vehicle.getUUID().toString();
        if (!Objects.equals(visit.lastVehicle, vehicleId)) {
            visit.rides.add(new Visit.RideChange(tick, vehicleId));
            visit.lastVehicle = vehicleId;
        }

        if (visit.healths == null) visit.healths = new ArrayList<>();
        float health = player.getHealth();
        if (visit.lastHealth != health) {
            visit.healths.add(new Visit.HealthChange(tick, health));
            visit.lastHealth = health;
        }

        if (!visit.attrInit) {
            visit.attrInit = true;
            for (AttributeInstance inst : player.getAttributes().getSyncableAttributes()) recordAttr(visit, tick, inst);
        }
    }

    public static void pollAll() {
        TimeTravel.forEachOnlineRecorded(CloneTracks::poll);
    }

    public static void onAttack(ServerPlayer player, Entity target) {
        if (AttackRelay.isInsideBcHandler()) return;
        if (target instanceof Player) return;
        Visit visit = recordedVisit(player);
        if (visit == null) return;
        visit.attacks.add(new Visit.AttackEvent(TimeTravel.timelineTick, target.getUUID().toString()));
    }

    public static void onTotem(ServerPlayer player) {
        Visit visit = recordedVisit(player);
        if (visit == null) return;
        if (visit.totems == null) visit.totems = new ArrayList<>();
        visit.totems.add(TimeTravel.timelineTick);
    }

    public static void onAttributesDirty(ServerPlayer player, Collection<AttributeInstance> dirty) {
        Visit visit = recordedVisit(player);
        if (visit == null) return;
        long tick = TimeTravel.timelineTick;
        for (AttributeInstance inst : dirty) recordAttr(visit, tick, inst);
    }

    private static void recordAttr(Visit visit, long tick, AttributeInstance inst) {
        CompoundTag tag = inst.save();
        String id = tag.getString("id");
        String nbt = tag.toString();
        if (visit.attrCache == null) visit.attrCache = new HashMap<>();
        if (visit.attrs == null) visit.attrs = new ArrayList<>();
        String prev = visit.attrCache.put(id, nbt);
        if (nbt.equals(prev)) return;
        if (prev == null && isPlayerDefault(inst, tag)) return;
        visit.attrs.add(new Visit.AttrChange(tick, id, nbt));
    }

    private static boolean isPlayerDefault(AttributeInstance inst, CompoundTag saved) {
        try {
            return !saved.contains("modifiers")
                    && inst.getBaseValue() == DefaultAttributes.getSupplier(EntityType.PLAYER).getBaseValue(inst.getAttribute());
        } catch (Exception e) {
            return false;
        }
    }

    public static void onHookSpawned(FishingHook hook) {
        if (hook.getPlayerOwner() instanceof ServerPlayer sp) addFish(sp, FISH_CAST, 0);
    }

    public static void onBite(ServerPlayer player) {
        addFish(player, FISH_BITE, 0);
    }

    public static void beginReel(UUID hook) {
        reeling = hook;
    }

    public static void onHookReeled(UUID hook, ServerPlayer player, int result) {
        if (hook.equals(reeling)) reeling = null;
        addFish(player, FISH_REEL, result);
    }

    public static void onHookRemoved(FishingHook hook) {
        if (hook.getUUID().equals(reeling)) return;
        if (hook.getPlayerOwner() instanceof ServerPlayer sp) addFish(sp, FISH_REEL, 0);
    }

    private static void addFish(ServerPlayer player, int type, int result) {
        Visit visit = recordedVisit(player);
        if (visit == null) return;
        if (visit.fishing == null) visit.fishing = new ArrayList<>();
        visit.fishing.add(new Visit.FishEvent(TimeTravel.timelineTick, type, result));
    }

    public static void replay(Visit visit, ServerPlayer clone, long now) {
        if (visit == null || clone.isRemoved()) return;
        applyMode(visit, clone, now);
        applyRide(visit, clone, now);
        CloneFx fx = TimeTravel.fxOf(clone);
        if (fx != null) {
            applyAttributes(visit, clone, now, fx);
            applyFishing(visit, clone, now, fx);
        }
        applyTotems(visit, clone, now);
        applyPearls(visit, clone, now);
        applyAttacks(visit, clone, now);
        BetterCombatIdleRelay.apply(clone);
    }

    private static Visit.HealthChange healthAt(Visit visit, long now) {
        List<Visit.HealthChange> list = visit.healths;
        if (list == null || list.isEmpty()) return null;
        int lo = 0;
        int hi = list.size() - 1;
        int idx = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).tick <= now) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return list.get(idx);
    }

    public static void snapHealth(Visit visit, ServerPlayer clone, long now) {
        Visit.HealthChange current = healthAt(visit, now);
        if (current == null) return;
        float wanted = Math.min(current.health, clone.getMaxHealth());
        if (wanted > 0.0f && clone.getHealth() != wanted) {
            clone.setHealth(wanted);
            clone.hurtTime = 0;
            clone.hurtDuration = 0;
            clone.hurtMarked = false;
        }
    }

    private static void applyHealth(Visit visit, ServerPlayer clone, long now) {
        Visit.HealthChange current = healthAt(visit, now);
        if (current == null) return;
        float wanted = Math.min(current.health, clone.getMaxHealth());
        if (wanted <= 0.0f) return;
        float actual = clone.getHealth();
        if (actual > wanted || (current.tick == now && actual != wanted)) {
            clone.setHealth(wanted);
            clone.hurtTime = 0;
            clone.hurtDuration = 0;
            clone.hurtMarked = false;
        }
    }

    public static void applyHealthAll() {
        if (!TimeTravel.isLooping || TimeTravel.timelineSceneManager == null) return;
        long now = TimeTravel.timelineTick;
        TimeTravel.timelineSceneManager.forEachRecordingPlayer(pd -> {
            for (Visit visit : pd.getVisits()) {
                Entity e = TimeTravel.entityOfVisit(visit.id);
                if (e instanceof ServerPlayer clone && Clones.is(clone) && !clone.isRemoved()) {
                    applyHealth(visit, clone, now);
                }
            }
        });
    }

    private static void applyMode(Visit visit, ServerPlayer clone, long now) {
        List<Visit.ModeChange> list = visit.modes;
        if (list == null || list.isEmpty()) return;
        Visit.ModeChange current = list.get(0);
        for (Visit.ModeChange change : list) {
            if (change.tick > now) break;
            current = change;
        }
        GameType type = GameType.byId(current.mode);
        if (clone.gameMode.getGameModeForPlayer() != type) clone.gameMode.changeGameModeForPlayer(type);
        if (clone.getAbilities().mayfly && clone.getAbilities().flying != current.flying) {
            clone.getAbilities().flying = current.flying;
        }
    }

    private static void applyRide(Visit visit, ServerPlayer clone, long now) {
        List<Visit.RideChange> list = visit.rides;
        if (list == null || list.isEmpty()) return;
        String wanted = null;
        for (Visit.RideChange change : list) {
            if (change.tick > now) break;
            wanted = change.vehicle;
        }
        Entity current = clone.getVehicle();
        if (wanted == null) {
            if (current != null) clone.stopRiding();
            return;
        }
        if (current != null && current.getUUID().toString().equals(wanted)) return;
        UUID id;
        try {
            id = UUID.fromString(wanted);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (!(clone.level() instanceof ServerLevel level)) return;
        Entity vehicle = level.getEntity(id);
        if (vehicle == null || vehicle.isRemoved()) return;
        clone.startRiding(vehicle, true);
    }

    private static void applyAttacks(Visit visit, ServerPlayer clone, long now) {
        List<Visit.AttackEvent> list = visit.attacks;
        if (list == null || list.isEmpty()) return;
        if (!(clone.level() instanceof ServerLevel level)) return;
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).tick < now) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < list.size() && list.get(i).tick == now; i++) {
            UUID id;
            try {
                id = UUID.fromString(list.get(i).target);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Entity target = level.getEntity(id);
            if (target == null || target.isRemoved()) continue;
            double d2 = clone.distanceToSqr(target);
            if (d2 > 36.0) continue;
            clone.attack(target);
        }
    }

    private static void applyTotems(Visit visit, ServerPlayer clone, long now) {
        List<Long> list = visit.totems;
        if (list == null || list.isEmpty()) return;
        if (Collections.binarySearch(list, now) >= 0) {
            clone.level().broadcastEntityEvent(clone, (byte) 35);
        }
    }

    private static void applyAttributes(Visit visit, ServerPlayer clone, long now, CloneFx fx) {
        List<Visit.AttrChange> list = visit.attrs;
        if (list == null) return;
        while (fx.attrCursor < list.size() && list.get(fx.attrCursor).tick <= now) {
            Visit.AttrChange change = list.get(fx.attrCursor++);
            try {
                var holder = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.parse(change.id));
                if (holder.isEmpty()) continue;
                AttributeInstance inst = clone.getAttributes().getInstance(holder.get());
                if (inst == null) continue;
                ListTag mods = inst.save().getList("modifiers", Tag.TAG_COMPOUND);
                for (int i = 0; i < mods.size(); i++) {
                    ResourceLocation modId = ResourceLocation.tryParse(mods.getCompound(i).getString("id"));
                    if (modId != null) inst.removeModifier(modId);
                }
                inst.load(TagParser.parseTag(change.nbt));
            } catch (Exception ex) {
                TimeTravel.TIMELINE_LOGGER.error("Failed to apply attribute change", ex);
            }
        }
    }

    private static void castHook(ServerPlayer clone) {
        if (!(clone.level() instanceof ServerLevel level)) return;
        FishingHook old = clone.fishing;
        if (old != null && !old.isRemoved()) old.discard();
        level.addFreshEntity(new FishingHook(clone, level, 0, 0));
    }

    private static void applyFishing(Visit visit, ServerPlayer clone, long now, CloneFx fx) {
        List<Visit.FishEvent> list = visit.fishing;
        if (list == null || list.isEmpty()) return;

        if (!fx.fishInit) {
            fx.fishInit = true;
            Visit.FishEvent last = null;
            for (Visit.FishEvent e : list) {
                if (e.tick >= now) break;
                last = e;
            }
            if (last != null && last.type != FISH_REEL) castHook(clone);
        }

        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).tick < now) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < list.size() && list.get(i).tick == now; i++) {
            Visit.FishEvent e = list.get(i);
            FishingHook hook = clone.fishing;
            boolean live = hook != null && !hook.isRemoved();
            switch (e.type) {
                case FISH_CAST -> castHook(clone);
                case FISH_BITE -> {
                    if (live && (Object) hook instanceof FishingHookExt ext) ext.tt$bite();
                }
                case FISH_REEL -> {
                    if (!live) break;
                    if (clone.level() instanceof ServerLevel level) {
                        if (e.result > 0) {
                            level.sendParticles(ParticleTypes.SPLASH, hook.getX(), hook.getY(), hook.getZ(), 8, 0.2, 0.1, 0.2, 0.0);
                        }
                        Entity hooked = hook.getHookedIn();
                        if (hooked != null && !hooked.isRemoved()) {
                            Vec3 pull = new Vec3(clone.getX() - hook.getX(), clone.getY() - hook.getY(), clone.getZ() - hook.getZ()).scale(0.1);
                            hooked.setDeltaMovement(hooked.getDeltaMovement().add(pull));
                            hooked.hurtMarked = true;
                            level.broadcastEntityEvent(hook, (byte) 31);
                        }
                    }
                    hook.discard();
                }
                default -> {
                }
            }
        }
    }

    public static boolean isReservedByClone(UUID id, long now) {
        TimelineSceneManager scene = TimeTravel.timelineSceneManager;
        if (scene == null) return false;
        String wanted = id.toString();
        for (PlayerData pd : scene.getRecordingPlayers()) {
            for (Visit v : pd.getVisits()) {
                if (v == pd.getCurrentVisit() || v.rides == null) continue;
                for (int i = 0; i < v.rides.size(); i++) {
                    if (!wanted.equals(v.rides.get(i).vehicle)) continue;
                    long end = i + 1 < v.rides.size() ? v.rides.get(i + 1).tick : (v.endTick > 0 ? v.endTick : Long.MAX_VALUE);
                    if (end > now) return true;
                }
            }
        }
        return false;
    }
}
