package xyz.teacafe.manacore;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.teacafe.manacore.command.AdminCommand;
import xyz.teacafe.manacore.command.ManaCommand;
import xyz.teacafe.manacore.command.ThanksCommand;
import xyz.teacafe.manacore.economy.CommissionService;
import xyz.teacafe.manacore.economy.CoopService;
import xyz.teacafe.manacore.economy.CraftService;
import xyz.teacafe.manacore.economy.DexService;
import xyz.teacafe.manacore.economy.OrderService;
import xyz.teacafe.manacore.economy.ProjectService;
import xyz.teacafe.manacore.gui.EconomyMenu;
import xyz.teacafe.manacore.gui.ManaMenu;
import xyz.teacafe.manacore.gui.Menu;
import xyz.teacafe.manacore.listener.ChatListener;
import xyz.teacafe.manacore.listener.ExploreTask;
import xyz.teacafe.manacore.listener.LifeListener;
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
    private EconomyMenu econMenu;
    private CraftService craft;
    private DexService dex;
    private CoopService coop;
    private OrderService orders;
    private CommissionService commissions;
    private ProjectService projects;

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
            commissions = new CommissionService(this);
            commissions.load();
            projects = new ProjectService(this);
            projects.load();
        } catch (Exception e) {
            getLogger().severe("無法開啟資料庫，ManaCore 停用：" + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        altar = new AltarMenu(this);
        altar.reload();
        actions = new Actions(this);
        menu = new ManaMenu(this);
        econMenu = new EconomyMenu(this);
        craft = new CraftService(this);
        dex = new DexService(this);
        coop = new CoopService(this);
        orders = new OrderService(this);
        orders.loadToday(false);
        commissions.expireOld();

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new ChatListener(this), this);
        pm.registerEvents(new LifeListener(this), this);
        pm.registerEvents(coop, this);
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
        if (mana != null) for (PlayerData d : mana.all()) mana.saveAllCrafts(d);
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
        orders.loadToday(false);
        try {
            projects.load();
        } catch (Exception e) {
            getLogger().warning("重新載入社區工程失敗：" + e);
        }
    }

    /** 跨日時由 ManaService 呼叫 */
    public void onNewDay() {
        orders.loadToday(false);
        commissions.expireOld();
    }

    /**
     * 讀取設定區塊；使用者的 config.yml 是舊版、沒有這個區塊時，改用插件內建的預設值。
     * （Bukkit 的 getConfigurationSection 遇到缺少的區塊會回傳空區塊，讀不到預設內容）
     */
    public ConfigurationSection section(String path) {
        return section(getConfig(), path);
    }

    public static ConfigurationSection section(org.bukkit.configuration.Configuration cfg, String path) {
        if (!cfg.isSet(path) && cfg.getDefaults() != null) {
            ConfigurationSection def = cfg.getDefaults().getConfigurationSection(path);
            if (def != null) return def;
        }
        return cfg.getConfigurationSection(path);
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
    public EconomyMenu econMenu() { return econMenu; }
    public CraftService craft() { return craft; }
    public DexService dex() { return dex; }
    public CoopService coop() { return coop; }
    public OrderService orders() { return orders; }
    public CommissionService commissions() { return commissions; }
    public ProjectService projects() { return projects; }
}
