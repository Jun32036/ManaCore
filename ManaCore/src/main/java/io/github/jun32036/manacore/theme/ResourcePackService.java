package io.github.jun32036.manacore.theme;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.Msg;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 介面資源包：從插件 jar 取出 manacore-pack.zip，用內建的小型 HTTP 伺服器（或設定的網址）提供給玩家，
 * 玩家進入時提示安裝；記錄哪些玩家已載入成功，選單據此決定用自訂外觀或原版外觀。
 */
public final class ResourcePackService implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("manacore:gui-pack".getBytes(StandardCharsets.UTF_8));

    private final ManaCorePlugin plugin;
    private final Set<UUID> styled = new HashSet<>();
    private byte[] bytes;
    private String sha1 = "";
    private String url;
    private HttpServer http;

    public ResourcePackService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("resource-pack.enabled", true) && bytes != null && url != null;
    }

    public boolean styled(Player p) {
        return styled.contains(p.getUniqueId());
    }

    /** 管理員手動標記（測試用）：不經資源包流程，直接切換自訂外觀 */
    public void setStyled(UUID id, boolean on) {
        if (on) styled.add(id); else styled.remove(id);
    }

    public String url() {
        return url;
    }

    public String sha1() {
        return sha1;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) return;
        try (InputStream in = plugin.getResource("manacore-pack.zip")) {
            if (in == null) {
                plugin.getLogger().warning("插件內沒有 manacore-pack.zip，介面資源包停用");
                return;
            }
            bytes = in.readAllBytes();
            File out = new File(plugin.getDataFolder(), "manacore-pack.zip");
            Files.write(out.toPath(), bytes);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) sb.append(String.format("%02x", b));
            sha1 = sb.toString();
        } catch (Exception e) {
            plugin.getLogger().warning("介面資源包準備失敗：" + e);
            bytes = null;
            return;
        }
        String cfgUrl = plugin.getConfig().getString("resource-pack.url", "");
        if (cfgUrl != null && !cfgUrl.isBlank()) {
            url = cfgUrl.strip();
        } else if (plugin.getConfig().getBoolean("resource-pack.serve.enabled", true)) {
            int port = plugin.getConfig().getInt("resource-pack.serve.port", 25580);
            String host = plugin.getConfig().getString("resource-pack.serve.public-host", "localhost");
            try {
                http = HttpServer.create(new InetSocketAddress(port), 8);
                http.createContext("/manacore-pack.zip", ex -> {
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                });
                http.setExecutor(null);
                http.start();
                url = "http://" + host + ":" + port + "/manacore-pack.zip";
                plugin.getLogger().info("介面資源包由內建伺服器提供：" + url + "（sha1 " + sha1.substring(0, 8) + "…）");
                if ("localhost".equals(host))
                    plugin.getLogger().warning("resource-pack.serve.public-host 還是 localhost：只有同一台電腦的玩家拿得到資源包。正式服請改成對外的網址或 IP。");
            } catch (Exception e) {
                plugin.getLogger().warning("介面資源包的 HTTP 伺服器啟動失敗（連接埠 " + port + "）：" + e.getMessage());
                url = null;
            }
        }
        if (url == null) plugin.getLogger().info("介面資源包未提供（沒有 resource-pack.url，內建伺服器也沒開），選單使用原版外觀");
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        styled.clear();
    }

    public void send(Player p) {
        if (!enabled()) return;
        boolean required = plugin.getConfig().getBoolean("resource-pack.required", false);
        ResourcePackRequest req = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), sha1))
                .required(required)
                .prompt(Msg.mm(plugin.getConfig().getString("resource-pack.prompt", "<aqua>生生不息</aqua> 的介面資源包：安裝後選單會有自己的樣式。不安裝也能玩。")))
                .build();
        p.sendResourcePacks(req);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) send(p); }, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getID())) return;
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> styled.add(e.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, DISCARDED, INVALID_URL, FAILED_RELOAD -> styled.remove(e.getPlayer().getUniqueId());
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        styled.remove(e.getPlayer().getUniqueId());
    }
}
