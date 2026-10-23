package com.fl1s.rewritten.timetravel.mixin;

import com.fl1s.rewritten.timetravel.journal.HeadItemView;
import com.fl1s.rewritten.timetravel.journal.ItemFlowJournal;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityHeadMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tt$dropOrphanCopy(CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level().isClientSide || !HeadItemView.isOrphan(self)) return;
        self.discard();
        ci.cancel();
    }

    @Inject(method = "makeFakeItem", at = @At("TAIL"))
    private void tt$forgetFakeItem(CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.level().isClientSide) ItemFlowJournal.forget(self.getUUID());
    }

    @Inject(method = "hasPickUpDelay", at = @At("HEAD"), cancellable = true)
    private void tt$copiesAreNotLoot(CallbackInfoReturnable<Boolean> cir) {
        if (HeadItemView.isCopy((ItemEntity) (Object) this)) cir.setReturnValue(true);
    }
}
