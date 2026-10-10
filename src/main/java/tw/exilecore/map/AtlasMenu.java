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
    private static final int SLOT_CLOSE = 53;

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
        final MapConfig config = service.config();
        final Set<String> completed = service.completed(player);
        for (final Atlas.Connector connector : Atlas.connectors(config)) {
            final boolean a = completed.contains(connector.from());
            final boolean b = completed.contains(connector.to());
            final Material pane = a && b ? Material.YELLOW_STAINED_GLASS_PANE : a || b ? Material.WHITE_STAINED_GLASS_PANE
                    : Material.GRAY_STAINED_GLASS_PANE;
            set(connector.row() * 9 + connector.col(), icon(pane, " "));
        }
        for (final MapConfig.MapDef map : config.all()) {
            set(map.row() * 9 + map.col(), node(config, map, completed));
        }
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
            material = Material.GRAY_DYE;
        } else {
            final var base = plugin.items().repository().base(map.base());
            material = done ? Material.FILLED_MAP : base == null ? Material.PAPER : material(base.material(), Material.PAPER);
        }
        final String color = done ? "<#f5b942>" : unlocked ? "<white>" : "<gray>";
        final ItemStack icon = icon(material, color + ItemRenderer.escape(map.name()) + (map.isFinal() ? " <dark_gray>終局" : ""), lore);
        return done ? glowing(icon) : icon;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
        } else if (slot == SLOT_TREE) {
            new AtlasTreeMenu(plugin, player, service).open();
        }
    }
}
