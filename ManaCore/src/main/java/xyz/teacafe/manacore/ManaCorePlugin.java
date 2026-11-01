package xyz.teacafe.manacore;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.teacafe.manacore.command.AdminCommand;
import xyz.teacafe.manacore.command.ManaCommand;
import xyz.teacafe.manacore.command.ThanksCommand;
import xyz.teacafe.manacore.gui.ManaMenu;
import xyz.teacafe.manacore.gui.Menu;
import xyz.teacafe.manacore.listener.ChatListener;
import xyz.teacafe.manacore.listener.ExploreTask;
import xyz.teacafe.manacore.listener.PlayerListener;

import java.io.File;

public final class ManaCorePlugin extends JavaPlugin {

    private final Msg msg = new Msg();
    private Database db;
    private ManaService mana;
    private LifeTree tree;
    private Places places;
    private MentorService mentors;
    private AltarMenu altar;
    private Actions actions;
    private ManaMenu menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        msg.load(getConfig().getConfigurationSection("messages"));

        db = new Database(getLogger());
        try {
            db.open(new File(getDataFolder(), "mana.db"));
            mana = new ManaService(this, db);
            tree = new LifeTree(this, db);
            tree.load();
            places = new Places(this, db);
            places.load();
            mentors = new MentorService(this, db);
            mentors.load();
        } catch (Exception e) {
            getLogger().severe("無法開啟資料庫，ManaCore 停用：" + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        altar = new AltarMenu(this);
        altar.reload();
        actions = new Actions(this);
        menu = new ManaMenu(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new ChatListener(this), this);
        pm.registerEvents(altar, this);
        pm.registerEvents(new Menu.Listen(), this);

        register("mana", new ManaCommand(this));
        register("thanks", new ThanksCommand(this));
        register("manaadmin", new AdminCommand(this));
        PluginCommand altarCmd = getCommand("altar");
        if (altarCmd != null) altarCmd.setExecutor((s, c, l, a) -> {
            if (s instanceof Player p) altar.open(p);
            return true;
        });

        long exploreTicks = Math.max(20, getConfig().getLong("sources.explore.check-interval-ticks", 100));
        Bukkit.getScheduler().runTaskTimer(this, new ExploreTask(this), exploreTicks, exploreTicks);
        Bukkit.getScheduler().runTaskTimer(this, mana::tickDay, 400L, 400L);
        Bukkit.getScheduler().runTaskTimer(this, mentors::tick, 1200L, 1200L);

        // 啟動時：補做今天的歸土、載入已在線的玩家（/reload 的情況）
        mana.runDecayIfNeeded();
        for (Player p : Bukkit.getOnlinePlayers()) mana.load(p, null);

        getServer().getServicesManager().register(ManaService.class, mana, this, ServicePriority.Normal);
        getLogger().info("ManaCore 已啟用：生命之樹累積 " + tree.total() + "，祭壇 " + places.altarCount() + " 座");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (db != null) db.close();
    }

    private void register(String name, TabExecutor ex) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor(ex);
        c.setTabCompleter(ex);
    }

    public void reloadAll() {
        reloadConfig();
        msg.load(getConfig().getConfigurationSection("messages"));
        altar.reload();
        tree.reload();
    }

    /** 回到主執行緒執行 */
    public void sync(Runnable r) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(this, r);
    }

    public Msg msg() { return msg; }
    public Database db() { return db; }
    public ManaService mana() { return mana; }
    public LifeTree tree() { return tree; }
    public Places places() { return places; }
    public MentorService mentors() { return mentors; }
    public AltarMenu altar() { return altar; }
    public Actions actions() { return actions; }
    public ManaMenu menu() { return menu; }
}
