package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.ProjectileJournal;
import com.fl1s.rewritten.timetravel.journal.TrackedEntityJournal;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {
        "io.redspace.ironsspellbooks.entity.spells.StompAoe",
        "io.redspace.ironsspellbooks.entity.spells.EarthquakeAoe",
        "io.redspace.ironsspellbooks.entity.spells.BlizzardAoe",
        "io.redspace.ironsspellbooks.entity.spells.HealingAoe",
        "io.redspace.ironsspellbooks.entity.spells.FireEruptionAoe",
        "io.redspace.ironsspellbooks.entity.spells.magma_ball.FireField",
        "io.redspace.ironsspellbooks.entity.spells.sunbeam.SunbeamEntity",
        "io.redspace.ironsspellbooks.entity.spells.poison_cloud.PoisonCloud",
        "io.redspace.ironsspellbooks.entity.spells.poison_cloud.PoisonSplash",
        "io.redspace.ironsspellbooks.entity.spells.dragon_breath.DragonBreathPool",
        "io.redspace.ironsspellbooks.entity.spells.black_hole.BlackHole",
        "io.redspace.ironsspellbooks.entity.spells.snowball.FrostField",
        "io.redspace.ironsspellbooks.entity.spells.ArrowVolleyEntity",
        "io.redspace.ironsspellbooks.entity.spells.FangSwirlEntity"
}, remap = false)
public abstract class IronSpellsEntityTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void tt$skipPuppetTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (TrackedEntityJournal.isPuppeted(self) || ProjectileJournal.isPuppeted(self)) {
            ci.cancel();
        }
    }
}
