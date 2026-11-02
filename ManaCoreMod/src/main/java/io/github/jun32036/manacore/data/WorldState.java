package io.github.jun32036.manacore.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.jun32036.manacore.ManaCore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** 世界的 ManaCore 資料：所有玩家的進度、村民委託、玩家放置的自然方塊。存在世界資料夾的 manacore.json。 */
public final class WorldState {

    public static final class VillagerRequest {
        public String item;
        public int amount;
        public long reward;
        public long day;
        public String player;
    }

    public Map<String, PlayerState> players = new HashMap<>();
    /** 村民 UUID → 委託 */
    public Map<String, VillagerRequest> requests = new HashMap<>();
    /** 玩家放下的礦石、原木、南瓜等（維度|座標），打掉不算採集 */
    public LinkedHashSet<String> placed = new LinkedHashSet<>();
    /** 上次發村民委託的遊戲日 */
    public long lastRequestDay = -1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public PlayerState player(UUID id) {
        return players.computeIfAbsent(id.toString(), k -> new PlayerState());
    }

    public static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("manacore.json");
    }

    public static WorldState load(MinecraftServer server) {
        Path f = file(server);
        try {
            if (Files.exists(f)) {
                WorldState s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), WorldState.class);
                if (s != null) return s;
            }
        } catch (Exception e) {
            ManaCore.LOG.error("讀取 {} 失敗，改用空白資料（原檔保留為 manacore.json.broken）", f, e);
            try { Files.copy(f, f.resolveSibling("manacore.json.broken"), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) { }
        }
        return new WorldState();
    }

    public void save(MinecraftServer server) {
        Path f = file(server);
        try {
            Path tmp = f.resolveSibling("manacore.json.tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            ManaCore.LOG.error("儲存 {} 失敗", f, e);
        }
    }
}
