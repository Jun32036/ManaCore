package io.github.jun32036.manacore.mixin;

import io.github.jun32036.manacore.ManaCore;
import net.minecraft.advancements.triggers.TameAnimalTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TameAnimalTrigger.class)
public class TameAnimalTriggerMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void manacore$tamed(ServerPlayer player, Animal animal, CallbackInfo ci) {
        ManaCore m = ManaCore.get();
        if (m != null) m.hooks().onTame(player, animal);
    }
}
