package io.github.jun32036.manacore.mixin;

import io.github.jun32036.manacore.ManaCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 合成台、熔爐、煙燻爐等把成品交給玩家時呼叫，amount 是實際做出的數量 */
@Mixin(ItemStack.class)
public class ItemStackMixin {
    @Inject(method = "onCraftedBy", at = @At("HEAD"))
    private void manacore$crafted(Player player, int amount, CallbackInfo ci) {
        ManaCore m = ManaCore.get();
        if (m != null && player instanceof ServerPlayer sp) m.hooks().onCrafted(sp, (ItemStack) (Object) this, amount);
    }
}
