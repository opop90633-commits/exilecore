package tw.exilecore.map;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.web.UiPack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 星圖（2026-10-21：程序生成的六角格，照 PoE2）：上面 5 排是 9×5 的視窗，每格一張地圖；六角格用「奇數列往右半格」的方式擺
 * （跟棋盤遊戲一樣：奇數列的格子跟上下兩列的「同一欄」與「右邊一欄」相鄰）。起點是營地，完成的格子發光，解鎖的可以開，
 * 還沒走到的是迷霧。最下面一排：地圖裝置、上下左右捲動、回到營地、星圖天賦、進度、關閉。
 * <p>
 * 左鍵一格已解鎖的地圖：列出背包、倉庫裡的這種地圖讓玩家挑一張開（2026-10-21，AtlasMapPickMenu）；Shift＋左鍵：只設成「下次開這種地圖用這一格」；
 * 右鍵取消。塔、城塞從地圖裝置開。
 */
public final class AtlasMenu extends Menu {

    /** 主題的名字（星圖、地圖裝置上看到的）。 */
    public static final Map<String, String> THEME_NAMES = Map.ofEntries(Map.entry("beach", "海岸"), Map.entry("forest", "森林"),
            Map.entry("ruins", "廢墟"), Map.entry("swamp", "沼澤"), Map.entry("snow", "雪原"), Map.entry("cave", "礦洞"),
            Map.entry("crypt", "墓穴"), Map.entry("star", "星殿"), Map.entry("desert", "沙漠"), Map.entry("volcano", "火山"),
            Map.entry("fungal", "菌窟"), Map.entry("void", "虛空"));
    private static final int VIEW_WIDTH = 9;
    private static final int VIEW_HEIGHT = 5;
    private static final int SLOT_DEVICE = 45;
    private static final int SLOT_UP = 46;
    private static final int SLOT_DOWN = 47;
    private static final int SLOT_LEFT = 48;
    private static final int SLOT_RIGHT = 49;
    private static final int SLOT_HOME = 50;
    private static final int SLOT_TREE = 51;
    private static final int SLOT_INFO = 52;
    private static final int SLOT_CLOSE = 53;
    private static final int SCROLL = 3;

    private final MapService service;
    /** 2026-10-21：核心「腐化之心」點了：所有地圖格（淨化區以外）都當作腐化區，星圖上也畫成腐化的。 */
    private boolean allCorrupt;
    /** 視窗中心（六角格的軸座標）。 */
    private int centerQ;
    private int centerR;
    /** 這次畫的格子：slot → 軸座標。 */
    private final Map<Integer, long[]> shown = new java.util.HashMap<>();

    public AtlasMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 6, "<#f5b942>星圖");
        this.service = service;
    }

    // ---- 座標（偏移座標 ↔ 軸座標：奇數列往右半格）----

    private static int colOf(final int q, final int r) {
        return q + (r - (r & 1)) / 2;
    }

    private static int qOf(final int col, final int row) {
        return col - (row - (row & 1)) / 2;
    }

    @Override
    protected void draw() {
        shown.clear();
        allCorrupt = service.atlasBonus(player).get("atlas.all_corrupt") > 0;
        final AtlasProgress atlas = service.atlasProgress(player);
        final AtlasGrid grid = atlas.grid();
        final Set<Long> unlocked = atlas.unlocked();
        final AtlasGrid.Cell chosen = chosenCell(atlas);
        final int col0 = colOf(centerQ, centerR) - VIEW_WIDTH / 2;
        final int row0 = centerR - VIEW_HEIGHT / 2;
        final boolean pack = plugin.web() != null && plugin.web().hasPack(player); // 2026-10-21：有資源包：畫成六角格
        for (int y = 0; y < VIEW_HEIGHT; y++) {
            for (int x = 0; x < VIEW_WIDTH; x++) {
                final int row = row0 + y;
                final int col = col0 + x;
                final int q = qOf(col, row);
                final int slot = y * 9 + x;
                shown.put(slot, new long[] {q, row});
                final ItemStack plain = tile(atlas, grid, unlocked, chosen, q, row);
                set(slot, pack ? packTile(plain, atlas, grid, unlocked, chosen, q, row) : plain);
            }
        }
        set(SLOT_DEVICE, icon(Material.LODESTONE, "<#9fd3ff>地圖裝置", "<gray>在營地：地圖裝置 NPC 右鍵開地圖", "<dark_gray>（/town 回營地）"));
        set(SLOT_UP, uiIcon("arrow_up", Material.ARROW, "<white>↑ 往上", "<gray>捲 " + SCROLL + " 格（Shift 一格）")); // 2026-10-21：資源包的箭頭圖示
        set(SLOT_DOWN, uiIcon("arrow_down", Material.ARROW, "<white>↓ 往下", "<gray>捲 " + SCROLL + " 格（Shift 一格）")); // 2026-10-21：資源包的箭頭圖示
        set(SLOT_LEFT, uiIcon("arrow_left", Material.ARROW, "<white>← 往左", "<gray>捲 " + SCROLL + " 格（Shift 一格）")); // 2026-10-21：資源包的箭頭圖示
        set(SLOT_RIGHT, uiIcon("arrow_right", Material.ARROW, "<white>→ 往右", "<gray>捲 " + SCROLL + " 格（Shift 一格）")); // 2026-10-21：資源包的箭頭圖示
        set(SLOT_HOME, icon(Material.NETHER_STAR, "<white>回到營地", "<gray>視窗中心現在在 (" + centerQ + ", " + centerR + ")",
                "<dark_gray>六角格：奇數列往右半格，所以奇數列的格子跟上下列的同一欄、右邊一欄相鄰"));
        set(SLOT_TREE, treeButton(player, service));
        set(SLOT_INFO, info(atlas, chosen));
        set(SLOT_CLOSE, closeButton());
    }

    /** 2026-10-22：罩到這格的每片石板一行：「⬢ 神龕石板：…（塔 (q, r)，還能用 2 次）」。 */
    private List<String> tabletLines(final AtlasProgress atlas, final int q, final int r) {
        final List<String> lines = new ArrayList<>();
        final int radius = service.atlasConfig().tower().radius() + service.atlasBonus(player).count("atlas.tower.radius");
        for (final AtlasGrid.Cell tower : atlas.completedTowers()) {
            if (AtlasGrid.distance(tower.q(), tower.r(), q, r) > radius) {
                continue;
            }
            final AtlasTablets.Kind kind = service.tabletAt(player, tower.q(), tower.r());
            if (kind != null) {
                lines.add("<#d8c8a0>⬢ " + kind.displayName() + "<gray>：" + kind.description() + "<dark_gray>（塔 (" + tower.q() + ", " + tower.r()
                        + ")，還能用 " + service.tabletUses(player, tower.q(), tower.r()) + " 次）");
            }
        }
        return lines;
    }

    /** 2026-10-22：這格有沒有被石板罩到（資源包的格子畫一圈石板色）。 */
    private boolean tabletCovered(final AtlasProgress atlas, final int q, final int r) {
        final int radius = service.atlasConfig().tower().radius() + service.atlasBonus(player).count("atlas.tower.radius");
        for (final AtlasGrid.Cell tower : atlas.completedTowers()) {
            if (AtlasGrid.distance(tower.q(), tower.r(), q, r) <= radius && service.tabletAt(player, tower.q(), tower.r()) != null) {
                return true;
            }
        }
        return false;
    }

    private AtlasGrid.Cell chosenCell(final AtlasProgress atlas) {
        final long[] at = AtlasProgress.decodeOne(plugin.players().get(player).progress().get(AtlasProgress.CHOSEN_KEY));
        if (at == null) {
            return null;
        }
        final AtlasGrid.Cell cell = atlas.grid().cell((int) at[0], (int) at[1]);
        return atlas.isUnlocked(cell.q(), cell.r()) ? cell : null;
    }

    private ItemStack tile(final AtlasProgress atlas, final AtlasGrid grid, final Set<Long> unlocked, final AtlasGrid.Cell chosen,
                           final int q, final int r) {
        if (q == 0 && r == 0) {
            return glowing(icon(Material.NETHER_STAR, "<#e8e8f2>營地", "<gray>星圖的起點 (0, 0)", "<gray>周圍的格子一開始就解鎖"));
        }
        final long key = AtlasGrid.key(q, r);
        final AtlasGrid.Cell cell = grid.cell(q, r);
        if (cell.isEmpty()) {
            return icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        }
        final boolean isUnlocked = unlocked.contains(key);
        if (!isUnlocked) {
            boolean nearby = false;
            for (final int[] direction : AtlasGrid.DIRECTIONS) {
                if (unlocked.contains(AtlasGrid.key(q + direction[0], r + direction[1]))) {
                    nearby = true;
                    break;
                }
            }
            return nearby ? icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>迷霧 (" + q + ", " + r + ")", "<gray>完成旁邊的格子就會看到")
                    : icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        }
        final boolean done = atlas.isCompleted(q, r);
        final MapConfig config = service.config();
        final List<String> lore = new ArrayList<>();
        final String where = "<dark_gray>(" + q + ", " + r + ")・距離 " + cell.distance() + "・預設 T" + cell.tier();
        ItemStack icon;
        switch (cell.kind()) {
            case TOWER -> {
                lore.add("<gray>塔：爬到頂、守 3 波；完成後解鎖周圍 " + grid.config().unlock().onTower() + " 格、天賦點 +" + grid.config().tower().points());
                lore.add("<gray>完成後可以插一片先驅石板，加成罩到周圍 " + grid.config().tower().radius() + " 格的地圖");
                lore.add(where);
                lore.add("");
                lore.add(done ? "<#f5b942>✔ 已完成" : "<green>從地圖裝置的「塔」按鈕開（不用地圖物品）");
                icon = icon(Material.LANTERN, (done ? "<#f5b942>" : "<green>") + "塔", lore);
            }
            case CITADEL -> {
                final MapConfig.MapDef finalMap = config.find(cell.citadelFinal());
                final String name = finalMap == null ? cell.citadelFinal() : finalMap.name();
                lore.add("<gray>城塞：" + ItemRenderer.escape(name) + " 的入口");
                lore.add("<gray>第一次從這裡打，攻破後那個終局就能在地圖裝置用天穹碎片打開");
                lore.add(where);
                lore.add("");
                lore.add(done ? "<#f5b942>✔ 已攻破" : "<green>從地圖裝置的終局按鈕開（第一次不用碎片）");
                icon = icon(Material.END_CRYSTAL, (done ? "<#f5b942>" : "<#c48bff>") + "城塞・" + ItemRenderer.escape(name), lore);
            }
            default -> {
                final MapConfig.MapDef map = config.find(cell.mapId());
                final String name = map == null ? String.valueOf(cell.mapId()) : map.name();
                if (map != null) {
                    lore.add("<gray>" + THEME_NAMES.getOrDefault(map.theme(), map.theme()) + "・" + map.layout().displayName()
                            + "・Boss：<#af6025>" + ItemRenderer.escape(map.boss().name()));
                }
                if (cell.kind() == AtlasGrid.Kind.BOSS) {
                    lore.add("<#af6025>☠ Boss 格：Boss 生命 ×" + AtlasTree.number(grid.config().bossLife()) + "、必掉 " + grid.config().bossMaps()
                            + " 張地圖、傳奇機率 ×" + AtlasTree.number(grid.config().bossUnique()));
                }
                if (corrupted(cell)) {
                    final AtlasConfig.Corruption k = grid.config().corruption();
                    lore.add("<#b07aff>腐化區" + (cell.corrupted() ? "" : "（腐化之心）") + "：怪物等級 +" + k.monsterLevel() + "、怪物群 +" + AtlasTree.number(k.pack()) + "%、物品數量 +"
                            + AtlasTree.number(k.quantity()) + "%、稀有度 +" + AtlasTree.number(k.rarity()) + "%");
                    lore.add("<#b07aff>　第一次打倒 Boss：每個人 " + Math.round(grid.config().vaalChance() * 100) + "% 掉一顆蝕變石");
                }
                if (cell.purified()) {
                    lore.add("<#fff1b8>淨化區：難度不變；第一次打倒 Boss：每個人 "
                            + Math.round(grid.config().purification().fractureChance() * 100) + "% 掉一顆定星石"); // 2026-10-21
                }
                // 2026-10-22：罩到這格的先驅石板（以前完全看不出哪些格子被石板罩到）
                final List<String> tablets = tabletLines(atlas, q, r);
                if (!tablets.isEmpty()) {
                    lore.addAll(tablets);
                    if (done) {
                        lore.add("<dark_gray>　（這格已完成：石板的加成只給第一次，再開不吃也不扣次數）");
                    }
                }
                lore.add(where);
                lore.add("");
                if (done) {
                    lore.add("<#f5b942>✔ 已完成<gray>（可以再開，不再給天賦點）");
                } else {
                    lore.add("<green>已解鎖：把這種地圖放進地圖裝置就會打這一格");
                }
                final boolean isChosen = chosen != null && chosen.q() == q && chosen.r() == r;
                lore.add("<yellow>左鍵：選一張 " + ItemRenderer.escape(name) + " 開這一格<gray>（背包、倉庫裡的）");
                lore.add(isChosen ? "<yellow>✔ 下次開 " + ItemRenderer.escape(name) + " 用這一格<gray>（右鍵取消）" : "<gray>Shift＋左鍵：只指定下次開 " + ItemRenderer.escape(name) + " 用這一格");
                final Material material;
                if (done) {
                    material = Material.FILLED_MAP;
                } else {
                    final var base = map == null ? null : plugin.items().repository().base(map.base());
                    material = base == null ? Material.PAPER : material(base.material(), Material.PAPER);
                }
                final String color = done ? "<#f5b942>" : corrupted(cell) ? "<#b07aff>" : cell.purified() ? "<#fff1b8>" : "<green>";
                icon = icon(material, color + (cell.kind() == AtlasGrid.Kind.BOSS ? "☠ " : "") + ItemRenderer.escape(name) + " <dark_gray>T" + cell.tier(), lore);
                icon.setAmount(Math.max(1, Math.min(64, cell.tier())));
                if (isChosen) {
                    return targetHighlight(done ? glowing(icon) : icon);
                }
            }
        }
        return done ? glowing(icon) : highlight(icon);
    }

    // ---- 資源包：六角格的貼圖（UiPack.ATLAS_CELL）----

    private static final NamespacedKey ATLAS_CELL = NamespacedKey.fromString(UiPack.ATLAS_CELL);
    private static final Color GOLD = Color.fromRGB(0xF5C04A);
    private static final Color BRIGHT = Color.fromRGB(0xE8E8F2);
    private static final Color DIM = Color.fromRGB(0x5E5E74);
    private static final Color EDGE_LIT = Color.fromRGB(0x9C9CB8);
    private static final Color EDGE_DIM = Color.fromRGB(0x33354A);
    private static final Color TOWER = Color.fromRGB(0xD8C8A0);
    private static final Color CITADEL = Color.fromRGB(0xC48BFF);
    private static final Color BOSS = Color.fromRGB(0xFFA060);
    private static final Color CORRUPT = Color.fromRGB(0xB07AFF);
    /** 淨化區的圈（2026-10-21，跟腐化區同一張圖，換成淡金白）。 */
    private static final Color PURIFY = Color.fromRGB(0xFFF1B8);
    private static final Color CYAN = Color.fromRGB(0x55FFFF);
    /** 2026-10-22：被先驅石板罩到的格子（跟腐化區同一個圈，換成石板的淡褐色）。 */
    private static final Color TABLET = Color.fromRGB(0xD8C8A0);

    /**
     * 有資源包的人看到的格子：同一個物品模型，用 custom_model_data 決定畫哪幾層（背景星空、往鄰居的連線、六角形、光暈、腐化圈、外框）。
     * 名字與說明照沒資源包的那一份（滑鼠移上去一樣看得到）。
     */
    private ItemStack packTile(final ItemStack plain, final AtlasProgress atlas, final AtlasGrid grid, final Set<Long> unlocked,
                               final AtlasGrid.Cell chosen, final int q, final int r) {
        final long key = AtlasGrid.key(q, r);
        final boolean camp = q == 0 && r == 0;
        final AtlasGrid.Cell cell = grid.cell(q, r);
        final boolean isUnlocked = camp || unlocked.contains(key);
        boolean fog = false;
        if (!camp && !cell.isEmpty() && !isUnlocked) {
            for (final int[] direction : AtlasGrid.DIRECTIONS) {
                if (unlocked.contains(AtlasGrid.key(q + direction[0], r + direction[1]))) {
                    fog = true;
                    break;
                }
            }
        }
        final boolean empty = cell.isEmpty() || !camp && !isUnlocked && !fog;
        final ItemStack stack = empty ? new ItemStack(Material.PAPER) : plain.withType(Material.PAPER); // setType 在 Paper 已經棄用
        if (!empty) {
            stack.setAmount(1);
        }
        final boolean done = camp || atlas.isCompleted(q, r);
        final boolean isChosen = chosen != null && chosen.q() == q && chosen.r() == r;
        final boolean fogFinal = fog;
        stack.editMeta(meta -> {
            meta.setItemModel(ATLAS_CELL);
            if (empty) {
                meta.setHideTooltip(true);
            }
            final CustomModelDataComponent data = meta.getCustomModelDataComponent();
            final List<String> strings = new ArrayList<>(List.of(Integer.toString(Math.floorMod(q * 7 + r * 13 + q * r, UiPack.BACKGROUNDS)), "", "", "", ""));
            final List<Boolean> flags = new ArrayList<>(List.of(false, false, false, false, false, false));
            final List<Color> colors = new ArrayList<>(List.of(EDGE_DIM, EDGE_DIM, EDGE_DIM, EDGE_DIM, EDGE_DIM, EDGE_DIM, DIM, GOLD, CYAN, CORRUPT));
            if (!empty) {
                if (isUnlocked) {
                    // 往鄰居的連線：鄰居也解鎖了才畫；兩頭都完成是金色
                    for (int i = 0; i < AtlasGrid.DIRECTIONS.length; i++) {
                        final int nq = q + AtlasGrid.DIRECTIONS[i][0];
                        final int nr = r + AtlasGrid.DIRECTIONS[i][1];
                        final boolean neighbourUnlocked = (nq == 0 && nr == 0) || unlocked.contains(AtlasGrid.key(nq, nr));
                        if (neighbourUnlocked) {
                            flags.set(i, true);
                            final boolean neighbourDone = (nq == 0 && nr == 0) || atlas.isCompleted(nq, nr);
                            colors.set(i, done && neighbourDone ? GOLD : EDGE_LIT);
                        }
                    }
                }
                final String kind = camp ? "camp" : fogFinal ? "fog" : switch (cell.kind()) {
                    case BOSS -> "boss";
                    case TOWER -> "tower";
                    case CITADEL -> "citadel";
                    default -> "map";
                };
                strings.set(UiPack.AtlasCell.STRING_KIND, kind);
                final Color kindColor = fogFinal ? DIM : done ? GOLD : switch (cell.kind()) {
                    case TOWER -> TOWER;
                    case CITADEL -> CITADEL;
                    case BOSS -> BOSS;
                    default -> BRIGHT;
                };
                colors.set(UiPack.AtlasCell.COLOR_KIND, camp ? BRIGHT : kindColor);
                if (done) {
                    strings.set(UiPack.AtlasCell.STRING_GLOW, "glow");
                    colors.set(UiPack.AtlasCell.COLOR_GLOW, GOLD);
                }
                if (!fogFinal && corrupted(cell)) {
                    strings.set(UiPack.AtlasCell.STRING_CORRUPT, "corrupt");
                } else if (!fogFinal && cell.purified()) {
                    strings.set(UiPack.AtlasCell.STRING_CORRUPT, "corrupt"); // 2026-10-21：淨化區用同一個圈、換顏色
                    colors.set(UiPack.AtlasCell.COLOR_CORRUPT, PURIFY);
                } else if (!fogFinal && !done && cell.kind() != AtlasGrid.Kind.TOWER && cell.kind() != AtlasGrid.Kind.CITADEL
                        && tabletCovered(atlas, q, r)) {
                    strings.set(UiPack.AtlasCell.STRING_CORRUPT, "corrupt"); // 2026-10-22：石板罩到的格子：同一個圈、石板色（沒完成的才畫）
                    colors.set(UiPack.AtlasCell.COLOR_CORRUPT, TABLET);
                }
                if (isChosen) {
                    strings.set(UiPack.AtlasCell.STRING_RING, "ring");
                    colors.set(UiPack.AtlasCell.COLOR_RING, CYAN);
                }
            }
            data.setStrings(strings);
            data.setFlags(flags);
            data.setColors(colors);
            meta.setCustomModelDataComponent(data);
        });
        return stack;
    }

    /** 進度：完成幾格、最遠、掉落上限、最高完成階級、城塞。 */
    private ItemStack info(final AtlasProgress atlas, final AtlasGrid.Cell chosen) {
        final MapConfig config = service.config();
        final List<String> lore = new ArrayList<>();
        final int cap = Atlas.dropTierCap(config, atlas.capUnits());
        final int best = service.bestTier(player);
        lore.add("<gray>完成 <white>" + atlas.completed().size() + "<gray> 格（一般 " + atlas.completedMapCells() + "、塔 " + atlas.completedTowerCount()
                + "、城塞 " + atlas.completedCitadelCount() + "）・最遠 <white>" + atlas.farthest() + "<gray> 格");
        lore.add("<gray>解鎖了 <white>" + atlas.unlockedSpecies().size() + "<gray> / " + config.maps().size() + " 種地圖");
        lore.add("<gray>掉落的地圖最高 <white>T" + cap + "<gray>／T" + config.tiers().max()
                + (cap < config.tiers().max() ? "<dark_gray>（每完成 " + atlas.grid().config().dropCap().perCells() + " 格高一階）" : ""));
        lore.add("<gray>掉落的地圖階級 +1 的機率：<white>" + Math.round(Atlas.tierUpChance(config, atlas.capUnits()) * 100) + "%");
        lore.add("<gray>最高完成階級：<white>" + (best > 0 ? "T" + best : "還沒有") + "<dark_gray>（終局可以開到這一階）");
        final Set<String> completed = service.completed(player);
        final List<String> citadels = new ArrayList<>();
        for (final String finalId : service.citadelFinals()) {
            final MapConfig.MapDef map = config.find(finalId);
            citadels.add((Atlas.citadelBreached(completed, finalId) ? "<#f5b942>" : "<dark_gray>") + ItemRenderer.escape(map == null ? finalId : map.name()));
        }
        if (!citadels.isEmpty()) {
            lore.add("<gray>城塞：" + String.join("<gray>、", citadels) + "<dark_gray>（金色＝攻破，可以用碎片開）");
        }
        final List<AtlasGrid.Cell> towers = atlas.openTowers();
        final List<AtlasGrid.Cell> openCitadels = atlas.openCitadels();
        if (!towers.isEmpty() || !openCitadels.isEmpty()) {
            lore.add("<gray>看得到、還沒打的：塔 <white>" + towers.size() + "<gray>、城塞 <white>" + openCitadels.size());
        }
        if (chosen != null) {
            final MapConfig.MapDef map = config.find(chosen.mapId());
            lore.add("<yellow>下次開 " + ItemRenderer.escape(map == null ? String.valueOf(chosen.mapId()) : map.name()) + " 用 (" + chosen.q() + ", " + chosen.r() + ")");
        }
        lore.add("");
        lore.add("<gray>完成一格解鎖周圍、拿天賦點；走得越遠格子的階級越高");
        lore.add("<gray>只會掉你已經解鎖的地圖種類，前線（解鎖還沒完成）比較常掉");
        lore.add("<dark_gray>種子 " + Long.toHexString(atlas.grid().seed()));
        return icon(Material.FILLED_MAP, "<#f5b942>星圖進度", lore);
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

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_CLOSE -> goBack();
            case SLOT_TREE -> new AtlasTreeMenu(plugin, player, service).open();
            case SLOT_UP -> scroll(0, -1, click);
            case SLOT_DOWN -> scroll(0, 1, click);
            case SLOT_LEFT -> scroll(-1, 0, click);
            case SLOT_RIGHT -> scroll(1, 0, click);
            case SLOT_HOME -> {
                centerQ = 0;
                centerR = 0;
                redraw();
            }
            default -> {
                final long[] at = shown.get(slot);
                if (at != null) {
                    clickCell((int) at[0], (int) at[1], click);
                }
            }
        }
    }

    private void scroll(final int dx, final int dy, final ClickType click) {
        final int step = click.isShiftClick() ? 1 : SCROLL;
        // 左右：偏移座標的欄；上下：列（軸座標的 r）
        final int col = colOf(centerQ, centerR) + dx * step;
        final int row = centerR + dy * step;
        centerQ = qOf(col, row);
        centerR = row;
        redraw();
    }

    private void clickCell(final int q, final int r, final ClickType click) {
        if (!acceptAction(click)) {
            return;
        }
        final AtlasProgress atlas = service.atlasProgress(player);
        if (!atlas.isUnlocked(q, r)) {
            return;
        }
        final AtlasGrid.Cell cell = atlas.grid().cell(q, r);
        if (!cell.isMap()) {
            player.sendActionBar(plugin.text().parse(cell.kind() == AtlasGrid.Kind.TOWER ? "<gray>塔從地圖裝置的「塔」按鈕開"
                    : "<gray>城塞從地圖裝置的終局按鈕開"));
            return;
        }
        final MapConfig.MapDef map = service.config().find(cell.mapId());
        final String name = map == null ? String.valueOf(cell.mapId()) : map.name();
        if (click.isRightClick()) {
            service.chooseCell(player, null);
            player.sendActionBar(plugin.text().parse("<gray>取消指定：開圖時自動選最近的未完成格"));
        } else {
            service.chooseCell(player, cell);
            if (!click.isShiftClick() && map != null) {
                // 2026-10-21：左鍵：列出背包、倉庫裡的這種地圖讓玩家挑（沒有就只指定這一格）
                if (AtlasMapPickMenu.anyFor(plugin, player, service, map)) {
                    new AtlasMapPickMenu(plugin, player, service, map, cell).open();
                    return;
                }
                player.sendActionBar(plugin.text().parse("<red>背包和倉庫都沒有 " + ItemRenderer.escape(name)
                        + "<gray>（已經指定這一格：拿到這種地圖後從地圖裝置開）"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                redraw();
                return;
            }
            player.sendActionBar(plugin.text().parse("<yellow>下次開 " + ItemRenderer.escape(name) + " 用 (" + q + ", " + r + ")"));
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        redraw();
    }

    /** 這格開圖時算不算腐化區：本來就是，或點了「腐化之心」（淨化區、塔、城塞除外）。 */
    private boolean corrupted(final AtlasGrid.Cell cell) {
        return cell.corrupted() || allCorrupt && !cell.purified() && cell.isMap();
    }

}
