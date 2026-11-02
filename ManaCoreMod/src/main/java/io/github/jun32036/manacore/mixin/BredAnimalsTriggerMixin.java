package io.github.jun32036.manacore.mixin;

import io.github.jun32036.manacore.ManaCore;
import net.minecraft.advancements.triggers.BredAnimalsTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版在玩家繁殖動物時呼叫這個成就觸發器，借它知道「誰繁殖了什麼」 */
@Mixin(BredAnimalsTrigger.class)
public class BredAnimalsTriggerMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void manacore$bred(ServerPlayer player, Animal parent, Animal partner, AgeableMob child, CallbackInfo ci) {
        ManaCore m = ManaCore.get();
        if (m != null) m.hooks().onBreed(player, parent, child);
    }
}
