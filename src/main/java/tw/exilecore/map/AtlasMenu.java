package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 星圖（Atlas）：跟天賦樹一樣的格點視窗——地圖是節點、相連是連線；完成的節點發光，
 * 解鎖的可以開、鎖住的是灰的。最下面一排是完成度、掉落的階級上限、最高完成階級與終局的狀態。
 * 地圖本身沒有階級（階級寫在地圖物品上，T1–T16 都能開），節點上寫的是第幾圈。
 */
public final class AtlasMenu extends Menu {

    /** 主題的名字（星圖、地圖裝置上看到的）。 */
    public static final Map<String, String> THEME_NAMES = Map.ofEntries(Map.entry("beach", "海岸"), Map.entry("forest", "森林"),
            Map.entry("ruins", "廢墟"), Map.entry("swamp", "沼澤"), Map.entry("snow", "雪原"), Map.entry("cave", "礦洞"),
            Map.entry("crypt", "墓穴"), Map.entry("star", "星殿"), Map.entry("desert", "沙漠"), Map.entry("volcano", "火山"),
            Map.entry("fungal", "菌窟"));
    private static final int SLOT_DEVICE = 45;
    private static final int SLOT_TREE = 47;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_HELP = 50;
    private static final int SLOT_CLOSE = 53;
    /** 格子 → 地圖（點節點時用）。 */
    private final Map<Integer, MapConfig.MapDef> shown = new java.util.HashMap<>();

    private final MapService service;

    public AtlasMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 6, "<#f5b942>星圖");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        final MapConfig config = service.config();
        final Set<String> completed = service.completed(player);
        // 2026-10：連線的顏色＝路的狀態——金色：兩頭都完成；綠色：一頭完成（可以往這邊走）；深灰：還沒走到
        for (final Atlas.Connector connector : Atlas.connectors(config)) {
            final boolean a = completed.contains(connector.from());
            final boolean b = completed.contains(connector.to());
            final Material pane = a && b ? Material.YELLOW_STAINED_GLASS_PANE : a || b ? Material.LIME_STAINED_GLASS_PANE
                    : Material.GRAY_STAINED_GLASS_PANE;
            final ItemStack line = new ItemStack(pane);
            line.editMeta(meta -> meta.setHideTooltip(true));
            set(connector.row() * 9 + connector.col(), line);
        }
        final List<String> next = new ArrayList<>();
        for (final MapConfig.MapDef map : config.all()) {
            if (!map.onAtlas()) {
                continue; // 2026-10-18：事件巔峰不在星圖上（地圖裝置的「事件巔峰」按鈕）
            }
            final int slot = map.row() * 9 + map.col();
            shown.put(slot, map);
            set(slot, node(config, map, completed));
            if (!completed.contains(map.id()) && Atlas.unlocked(config, map, completed)) {
                next.add((map.isFinal() ? "<#f5b942>" : "<green>") + ItemRenderer.escape(map.name()) + "<dark_gray>（" + (map.isFinal() ? "終局" : "第 " + map.ring() + " 圈") + "）");
            }
        }
        final List<String> nextLore = new ArrayList<>();
        nextLore.add("<gray>已經解鎖、還沒完成的地圖（金框標出來的）：");
        if (next.isEmpty()) {
            nextLore.add("<dark_gray>　沒有——全部完成了");
        } else {
            for (int i = 0; i < Math.min(8, next.size()); i++) {
                nextLore.add("<gray>　• " + next.get(i));
            }
            if (next.size() > 8) {
                nextLore.add("<dark_gray>　…還有 " + (next.size() - 8) + " 張");
            }
        }
        nextLore.add("");
        nextLore.add("<gray>完成一張不同的地圖，相連的就會解鎖、掉落的地圖也會高一階");
        set(SLOT_NEXT, next.isEmpty() ? icon(Material.FILLED_MAP, "<white>下一張", nextLore) : glowing(icon(Material.FILLED_MAP, "<green>下一張（" + next.size() + " 張可以開）", nextLore)));
        set(SLOT_HELP, helpButton(List.of(
                "<gray>每個格子是一張地圖，右下角的數字是第幾圈；連線是走法",
                "<#f5b942>金色發光<gray>＝已完成　<green>金框<gray>＝已解鎖、可以開　<dark_gray>空白地圖＝還沒解鎖",
                "<gray>連線：<#f5b942>金<gray>＝兩頭都完成　<green>綠<gray>＝可以往這邊走　<dark_gray>灰＝還沒走到",
                "<gray>滑鼠移到地圖上看主題、Boss、相連的地圖；點一下在聊天欄列出來",
                "<gray>地圖本身沒有階級：階級寫在地圖物品上（T1–T16），任何一張都能開")));
        set(SLOT_DEVICE, icon(Material.LODESTONE, "<#9fd3ff>地圖裝置", "<gray>在營地：地圖裝置 NPC 右鍵開地圖",
                "<dark_gray>（/town 回營地）"));
        final int done = Atlas.completedMaps(config, completed);
        final int best = service.bestTier(player);
        final int cap = Atlas.dropTierCap(config, done);
        set(SLOT_INFO, icon(Material.FILLED_MAP, "<#f5b942>完成 " + done + " / " + config.maps().size() + " 張",
                "<gray>掉落的地圖最高 <white>T" + cap + "<gray>／T" + config.tiers().max()
                        + (cap < config.tiers().max() ? "<dark_gray>（每完成一張不同的地圖高一階）" : ""),
                "<gray>掉落的地圖階級 +1 的機率：<white>" + Math.round(Atlas.tierUpChance(config, done) * 100) + "%",
                "<gray>最高完成階級：<white>" + (best > 0 ? "T" + best : "還沒有") + "<dark_gray>（終局可以開到這一階）",
                "<gray>只會掉你已經解鎖的地圖，還沒完成的比較常掉",
                Atlas.finalUnlocked(config, completed) ? "<#f5b942>終局已解鎖"
                        : "<dark_gray>完成最外圈的地圖（" + ItemRenderer.escape(service.ringNames(config.maxRing())) + "）解鎖終局"));
        set(SLOT_TREE, treeButton(player, service));
        set(SLOT_CLOSE, closeButton());
    }

    /** 星圖天賦的按鈕（星圖、地圖裝置共用）：還沒用的天賦點、目前的效果；有點數沒用時發光。 */
    static ItemStack treeButton(final Player player, final MapService service) {
        final AtlasTree.Earned earned = service.atlasEarned(player);
        final int left = service.atlasUnspent(player);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>完成地圖拿天賦點，讓你開的地圖更好");
        lore.add("<gray>天賦點：還沒用 <white>" + left + "<gray> / 總共 " + earned.total());
        final List<String> effects = service.atlasBonus(player).describe();
        for (int i = 0; i < Math.min(4, effects.size()); i++) {
            lore.add("<green>• " + ItemRenderer.escape(effects.get(i)));
        }
        if (effects.size() > 4) {
            lore.add("<dark_gray>…還有 " + (effects.size() - 4) + " 項");
        }
        lore.add("<dark_gray>點一下打開（也可以輸入 /atlas tree）");
        final ItemStack icon = icon(Material.END_CRYSTAL, "<#9fd3ff>星圖天賦" + (left > 0 ? " <yellow>（" + left + " 點沒用）" : ""), lore);
        return left > 0 ? glowing(icon) : icon;
    }

    private ItemStack node(final MapConfig config, final MapConfig.MapDef map, final Set<String> completed) {
        final boolean done = completed.contains(map.id());
        final boolean unlocked = Atlas.unlocked(config, map, completed);
        final List<String> lore = new ArrayList<>();
        final MapStats.Tiers tiers = config.tiers();
        final String tier = map.isFinal() ? "終局" : "第 " + map.ring() + " 圈";
        lore.add("<gray>" + tier + "・" + THEME_NAMES.getOrDefault(map.theme(), map.theme()) + "・" + map.layout().displayName()
                + " <dark_gray>（" + map.layout().description() + "）");
        if (map.isFinal()) {
            lore.addAll(service.finalTierLines(player));
        } else {
            lore.add("<gray>可以開 T1–T" + tiers.max() + "<gray>・怪物等級 <white>" + tiers.level(1) + "–" + tiers.level(tiers.max())
                    + "<dark_gray>（階級寫在地圖上）");
        }
        if (!map.lore().isBlank()) {
            lore.add("<gray><i>" + ItemRenderer.escape(map.lore()));
        }
        lore.add("<gray>Boss：<#af6025>" + ItemRenderer.escape(map.boss().name()) + (map.boss().named() ? " <dark_purple>(具名)" : ""));
        final List<String> links = new ArrayList<>();
        for (final String link : map.links()) {
            final MapConfig.MapDef other = config.find(link);
            if (other != null) {
                links.add(other.name());
            }
        }
        if (!links.isEmpty()) {
            lore.add("<dark_gray>相連：" + ItemRenderer.escape(String.join("、", links)));
        }
        if (done) {
            lore.add("<#f5b942>✔ 已完成");
        } else if (unlocked) {
            lore.add(map.isFinal() ? "<green>已解鎖：在地圖裝置用天穹碎片打開" : "<green>已解鎖：把這張地圖放進地圖裝置");
        } else {
            final String missing = map.isFinal() ? Atlas.missingRequirements(config, map, completed) : "";
            lore.add(map.isFinal() ? !missing.isEmpty() ? "<red>先打贏 " + ItemRenderer.escape(missing) + " 才能開" : "<red>完成最外圈的地圖解鎖"
                    : "<red>未解鎖：先完成相連的地圖");
        }
        final Material material;
        if (map.isFinal()) {
            material = unlocked ? Material.NETHER_STAR : Material.FIREWORK_STAR;
        } else if (!unlocked) {
            material = Material.MAP; // 2026-10：還沒解鎖畫成空白地圖（原本的灰色染料看起來像一團雜物）
        } else {
            final var base = plugin.items().repository().base(map.base());
            material = done ? Material.FILLED_MAP : base == null ? Material.PAPER : material(base.material(), Material.PAPER);
        }
        final String color = done ? "<#f5b942>" : unlocked ? "<green>" : "<gray>";
        final String ring = map.isFinal() ? " <dark_gray>終局" : " <dark_gray>第 " + map.ring() + " 圈";
        final ItemStack icon = icon(material, color + ItemRenderer.escape(map.name()) + ring, lore);
        if (!map.isFinal()) {
            icon.setAmount(Math.max(1, Math.min(64, map.ring()))); // 右下角的數字＝第幾圈
        }
        if (done) {
            return glowing(icon);
        }
        return unlocked ? highlight(icon) : icon; // 2026-10：已解鎖、還沒完成的用金框標出來（沒資源包的人看到發光）
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
        } else if (slot == SLOT_TREE) {
            new AtlasTreeMenu(plugin, player, service).open();
        } else if (shown.containsKey(slot)) {
            // 2026-10：點地圖：把狀態、相連的地圖列在聊天欄（滑鼠移開也看得到）
            final MapConfig.MapDef map = shown.get(slot);
            final Set<String> completed = service.completed(player);
            final boolean done = completed.contains(map.id());
            final boolean unlocked = Atlas.unlocked(service.config(), map, completed);
            final List<String> links = new ArrayList<>();
            for (final String link : map.links()) {
                final MapConfig.MapDef other = service.config().find(link);
                if (other != null) {
                    links.add((completed.contains(other.id()) ? "<#f5b942>" : Atlas.unlocked(service.config(), other, completed) ? "<green>" : "<gray>")
                            + ItemRenderer.escape(other.name()) + "</" + (completed.contains(other.id()) ? "#f5b942" : Atlas.unlocked(service.config(), other, completed) ? "green" : "gray") + ">");
                }
            }
            player.sendMessage(plugin.text().prefixed("<white>" + ItemRenderer.escape(map.name()) + "<gray>（" + (map.isFinal() ? "終局" : "第 " + map.ring() + " 圈")
                    + "・" + THEME_NAMES.getOrDefault(map.theme(), map.theme()) + "）"
                    + (done ? "<#f5b942>已完成" : unlocked ? "<green>已解鎖，可以開" : "<gray>還沒解鎖")
                    + (links.isEmpty() ? "" : "<gray>　相連：" + String.join("<gray>、", links))));
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        }
    }
}
