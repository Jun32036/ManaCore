package io.github.jun32036.manacore.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.jun32036.manacore.ManaCore;
import io.github.jun32036.manacore.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 客戶端：按 M 開選單；收到伺服器的資料就更新畫面。 */
public final class ManaCoreClient implements ClientModInitializer {

    public static KeyMapping MENU_KEY;
    /** 最近一次收到的資料（打開選單時先用它畫，等新的資料到了再更新） */
    static JsonObject latest;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ManaCore.MOD_ID, "main"));
        MENU_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.manacore.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, cat));

        ClientPlayNetworking.registerGlobalReceiver(Net.Sync.TYPE, (payload, ctx) -> {
            JsonObject data;
            try {
                data = JsonParser.parseString(payload.json()).getAsJsonObject();
            } catch (Exception e) {
                ManaCore.LOG.warn("ManaCore 資料解析失敗", e);
                return;
            }
            latest = data;
            Minecraft mc = ctx.client();
            if (mc.gui.screen() instanceof ManaScreen s) s.update(data);
            else if (payload.open() && mc.gui.screen() == null) mc.gui.setScreen(new ManaScreen(data));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> latest = null);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (MENU_KEY.consumeClick()) {
                if (mc.player == null || mc.gui.screen() != null) continue;
                if (!ClientPlayNetworking.canSend(Net.Action.TYPE)) {
                    mc.player.sendSystemMessage(Component.literal("這個世界／伺服器沒有安裝 ManaCore。"));
                    continue;
                }
                if (latest != null) mc.gui.setScreen(new ManaScreen(latest));
                send("open", "", 0);
            }
        });
    }

    public static void send(String action, String arg, int count) {
        if (ClientPlayNetworking.canSend(Net.Action.TYPE)) ClientPlayNetworking.send(new Net.Action(action, arg, count));
    }
}
