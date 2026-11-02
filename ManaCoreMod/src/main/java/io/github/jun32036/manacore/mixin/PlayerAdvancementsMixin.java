package io.github.jun32036.manacore.mixin;

import io.github.jun32036.manacore.ManaCore;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 原版成就完成時給 mana（每個成就一次） */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Shadow
    public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder holder);

    @Inject(method = "award", at = @At("RETURN"))
    private void manacore$award(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        ManaCore m = ManaCore.get();
        if (m != null && getOrStartProgress(holder).isDone()) m.onAdvancement(player, holder);
    }
}
