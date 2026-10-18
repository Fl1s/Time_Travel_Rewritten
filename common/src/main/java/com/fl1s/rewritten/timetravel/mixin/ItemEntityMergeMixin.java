package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.ItemFlowJournal;
import com.fl1s.rewritten.timetravel.TimeTravel;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMergeMixin {

    @Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
    private void tt$noMergeTracked(CallbackInfoReturnable<Boolean> cir) {
        if (!TimeTravel.isLooping) return;
        ItemEntity self = (ItemEntity) (Object) this;
        if (ItemFlowJournal.isTracked(self.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
