package io.github.jun32036.manacore.mixin;

import io.github.jun32036.manacore.ManaCore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 玩家放下方塊（建造手藝、記錄玩家自己放的礦石原木） */
@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void manacore$placed(BlockPlaceContext ctx, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        ManaCore m = ManaCore.get();
        if (m != null && ctx.getPlayer() instanceof ServerPlayer sp && ctx.getLevel() instanceof ServerLevel lvl) {
            m.hooks().onPlace(sp, lvl, ctx.getClickedPos(), lvl.getBlockState(ctx.getClickedPos()));
        }
    }
}
