package io.github.jun32036.manacore.mixin;

import io.github.jun32036.manacore.ManaCore;
import net.minecraft.advancements.triggers.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/** 釣到東西時原版會呼叫這個觸發器，裡面就是釣到的物品 */
@Mixin(FishingRodHookedTrigger.class)
public class FishingRodHookedTriggerMixin {
    @Inject(method = "trigger", at = @At("HEAD"))
    private void manacore$fished(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items, CallbackInfo ci) {
        ManaCore m = ManaCore.get();
        if (m != null && !items.isEmpty()) m.hooks().onFish(player, items);
    }
}
