package io.github.jun32036.manacore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 讀取 config 的 messages 區塊並以 MiniMessage 送出。佔位符的值一律當純文字處理，避免玩家輸入注入格式。 */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** 1.2.0 以前的預設前綴；舊設定檔仍是這個值時，自動改用新的藍色寶石前綴 */
    private static final String OLD_PREFIX = "<green>✿ </green>";
    public static final String DEFAULT_PREFIX = "<aqua>◆ </aqua>";
    private final Map<String, String> templates = new HashMap<>();
    private Component prefix = Component.empty();

    public void load(ConfigurationSection section) {
        templates.clear();
        if (section == null) return;
        for (String k : section.getKeys(false)) templates.put(k, section.getString(k, ""));
        String pre = templates.getOrDefault("prefix", DEFAULT_PREFIX);
        if (pre.strip().equals(OLD_PREFIX.strip())) pre = DEFAULT_PREFIX;
        prefix = MM.deserialize(pre);
    }

    /** pairs: key1, value1, key2, value2 ...；值若是 Component 則原樣插入 */
    public Component render(String key, Object... pairs) {
        String tpl = templates.getOrDefault(key, key);
        return MM.deserialize(tpl, resolvers(pairs));
    }

    public void send(CommandSender to, String key, Object... pairs) {
        to.sendMessage(prefix.append(render(key, pairs)));
    }

    /** 直接送出一段 MiniMessage（給程式內建、不需要讓服主改的文字）。 */
    public void raw(CommandSender to, String miniMessage, Object... pairs) {
        to.sendMessage(prefix.append(MM.deserialize(miniMessage, resolvers(pairs))));
    }

    public static Component mm(String miniMessage, Object... pairs) {
        return MM.deserialize(miniMessage, resolvers(pairs));
    }

    private static TagResolver resolvers(Object... pairs) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object v = pairs[i + 1];
            if (v instanceof Component c) list.add(Placeholder.component(name, c));
            else list.add(Placeholder.unparsed(name, String.valueOf(v)));
        }
        return TagResolver.resolver(list);
    }
}
