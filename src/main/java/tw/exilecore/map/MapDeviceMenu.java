package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.LevelPace;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 地圖裝置（營地的 NPC 右鍵）：點背包裡的一張地圖選起來 → 按「開啟」，地圖被裝置吃掉，後面的傳送門亮起來
 * （每個人或隊伍自己的，互不佔位）。選地圖只是記住背包的哪一格（東西不離開背包），按開啟時再確認還是同一張，
 * 不會吃錯、也不會複製。另外有星圖按鈕、終局（兩片天穹碎片，選要開第幾階）、你（隊伍）目前這張地圖的狀態。
 */
public final class MapDeviceMenu extends Menu {

    private static final int SLOT_MAP = 13;
    private static final int SLOT_OPEN = 22;
    private static final int SLOT_ATLAS = 29;
    private static final int SLOT_FINAL = 31;
    private static final int SLOT_HELP = 33;
    private static final int SLOT_UBER = 40;
    /** 巔峰（2026-10）：集齊四種碎片才能開一次的最終 Boss。 */
    private static final int SLOT_PINNACLE = 42;
    private static final int SLOT_TREE = 38;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_STATUS = 49;

    private final MapService service;
    private int selectedSlot = -1;
    private ItemData selected;

    public MapDeviceMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 6, "<#9fd3ff>地圖裝置");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        final MapConfig config = service.config();
        final Set<String> completed = service.completed(player);
        if (selected == null) {
            set(SLOT_MAP, icon(Material.MAP, "<gray>還沒放地圖",
                    "<dark_gray>點一下你背包裡的地圖，放上裝置"));
            set(SLOT_OPEN, icon(Material.GRAY_DYE, "<gray>開啟", "<dark_gray>先選一張地圖"));
        } else {
            final ItemStack preview = plugin.items().toStack(selected);
            set(SLOT_MAP, preview);
            final MapConfig.MapDef map = config.byBase(selected.base());
            final List<String> lore = new ArrayList<>();
            boolean ok = true;
            if (map == null) {
                lore.add("<red>maps.yml 沒有這張地圖，不能開");
                ok = false;
            } else if (!Atlas.unlocked(config, map, completed)) {
                lore.add("<red>星圖上還沒解鎖：先完成跟它相連的地圖");
                ok = false;
            } else {
                final MapStats stats = MapStats.of(plugin.items().repository(), selected, config.tiers());
                lore.add("<gray>T" + stats.tier() + "・怪物等級 <white>" + stats.monsterLevel() + "<gray>・" + ItemRenderer.escape(stats.summary()));
                // 高等級：等級差懲罰與高等級的經驗減少，選地圖前就看得到
                final String penalty = LevelPace.penaltyNote(plugin.players().config(), plugin.players().get(player).level(),
                        stats.monsterLevel());
                if (penalty != null) {
                    lore.add("<yellow>" + penalty);
                }
                final AtlasBonus atlas = service.atlasBonus(player);
                if (!atlas.isEmpty()) {
                    lore.add("<#9fd3ff>＋星圖天賦<gray>：" + ItemRenderer.escape(MapService.atlasSummary(atlas, 3)));
                }
                lore.add("<gray>地圖會被裝置吃掉，後面的傳送門會亮起來");
                lore.add("<gray>可以進入 <white>" + config.settings().portals() + "<gray> 次（你和隊友走進去都算一次）");
                final double death = plugin.players().config().mapDeathPenalty();
                lore.add(death > 0 ? "<gray>" + plugin.players().config().deathPenaltyFromLevel() + " 等以上死亡扣這一級 " + LevelPace.percent(death)
                        + " 的經驗（哪裡都一樣）" : "<gray>死亡不扣經驗");
                final String craft = plugin.items().renderer().mapCraftHint(selected);
                if (craft != null) {
                    // PoE2：沒洗的地圖等於少拿東西——開之前提醒還能洗什麼
                    lore.add("");
                    lore.add(craft);
                    lore.add("<dark_gray>（先拿回背包，右鍵通貨再點地圖；洗完再放上來）");
                }
                final MapInstance current = service.target(player);
                if (current != null) {
                    lore.add("");
                    lore.add("<yellow>你" + (inParty() ? "（隊伍）" : "") + "已經有一張開著的地圖：" + ItemRenderer.escape(current.label()));
                    lore.add("<yellow>開新的會關掉那張的傳送門<dark_gray>（還在裡面的人可以打完再出來）");
                }
            }
            set(SLOT_OPEN, ok ? glowing(icon(Material.LIME_DYE, "<green>開啟 " + ItemRenderer.escape(map.name() + " T"
                    + MapStats.of(plugin.items().repository(), selected, config.tiers()).tier()), lore))
                    : icon(Material.RED_DYE, "<red>不能開啟", lore));
        }
        set(SLOT_ATLAS, icon(Material.FILLED_MAP, "<#f5b942>星圖",
                "<gray>完成 <white>" + Atlas.completedMaps(config, completed) + "<gray> / " + config.maps().size() + " 張",
                "<gray>完成一張地圖（殺死 Boss）就解鎖跟它相連的地圖",
                "<dark_gray>點一下打開（也可以輸入 /atlas）"));
        set(SLOT_TREE, AtlasMenu.treeButton(player, service));
        drawFinal(config, completed);
        drawUber(config, completed);
        drawPinnacle(config, completed);
        set(SLOT_HELP, icon(Material.BOOK, "<white>怎麼用",
                "<gray>1. 點背包裡的地圖，放上裝置",
                "<gray>2. 按「開啟」：地圖世界裡會生出那張地圖",
                "<gray>3. 走進後面亮起來的門（只有你和隊友看得到）",
                "<gray>   還能進幾次就亮幾道，每走進去一次少一道",
                "<gray>4. 殺死地圖 Boss 完成：星圖點亮、解鎖相鄰的地圖",
                "<gray>起點房間西邊的門可以回營地（不扣次數）",
                "<gray>每張地圖都能開 T1–T" + config.tiers().max() + "（階級寫在地圖上）：",
                "<gray>   階級越高怪物越強，掉的地圖與裝備越好",
                "<dark_gray>地圖從霜頂山脈的 Boss 開始掉，之後地圖怪會掉地圖",
                "<#f5b942>開之前先洗地圖：<gray>微光石→魔法、添輝石 +1 條、豐饒石→稀有、至臻石 +1 條，",
                "<gray>   詞綴越多怪越強、掉越多；星盤刻刀品質 +5（每 1% = +1% 物品數量，上限 20）"));
        drawStatus();
        set(SLOT_CLOSE, closeButton());
    }

    private void drawFinal(final MapConfig config, final Set<String> completed) {
        final List<MapConfig.MapDef> finals = config.regularFinals();
        if (finals.isEmpty()) {
            return;
        }
        final MapConfig.MapDef map = finals.getFirst();
        final CurrencyType fragment = plugin.currencies().repository().get(config.drops().fragment());
        final int have = fragment == null ? 0 : service.countCurrency(player, fragment);
        final int cost = config.drops().fragmentCost();
        final boolean unlocked = Atlas.finalUnlocked(config, completed);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + ItemRenderer.escape(map.lore()));
        if (finals.size() == 1) {
            lore.add("<gray>Boss：<#af6025>" + ItemRenderer.escape(map.boss().name()));
        } else {
            // 好幾個終局 Boss：列出來，寫打贏了沒、能不能開
            lore.add("<gray>終局 Boss（" + finals.size() + " 個）：");
            for (final MapConfig.MapDef other : finals) {
                final boolean done = completed.contains(other.id());
                final boolean openable = Atlas.unlocked(config, other, completed);
                final String missing = Atlas.missingRequirements(config, other, completed);
                lore.add((done ? "<green>✔ " : openable ? "<white>・" : "<dark_gray>・") + (openable || done ? "<#af6025>" : "<dark_gray>")
                        + ItemRenderer.escape(other.boss().name()) + " <gray>（" + ItemRenderer.escape(other.name()) + "）"
                        + (done ? " <green>已打贏" : !missing.isEmpty() ? " <dark_gray>先打贏 " + ItemRenderer.escape(missing) : ""));
            }
        }
        lore.addAll(service.finalTierLines(player));
        lore.add("<dark_gray>階級越高 Boss 越強，掉的地圖與裝備越好");
        if (!unlocked) {
            lore.add("<red>先完成星圖最外圈的地圖（" + ItemRenderer.escape(service.ringNames(config.maxRing())) + "）");
        }
        lore.add((have >= cost ? "<green>" : "<red>") + "需要 " + cost + " 片" + (fragment == null ? "天穹碎片" : ItemRenderer.escape(fragment.name()))
                + "（你有 " + have + " 片）");
        lore.add("<dark_gray>天穹碎片：" + ItemRenderer.escape(service.fragmentSources()) + " 的 Boss 必掉一片");
        final String title = finals.size() == 1 ? map.name() : "終局";
        if (unlocked && have >= cost) {
            lore.add("<green>點一下選" + (finals.size() > 1 ? " Boss 與" : "") + "階級、打開傳送門");
            set(SLOT_FINAL, glowing(icon(Material.NETHER_STAR, "<#f5b942>" + ItemRenderer.escape(title), lore)));
        } else {
            set(SLOT_FINAL, icon(unlocked ? Material.NETHER_STAR : Material.FIREWORK_STAR, "<gray>" + ItemRenderer.escape(title), lore));
        }
    }

    /** 終局：選要打哪一個 Boss（好幾個的話）、開第幾階（最高到完成過的最高階），確定才用掉碎片。 */
    private void chooseFinalTier() {
        final MapConfig config = service.config();
        final List<MapConfig.MapDef> finals = config.regularFinals();
        // 能開的終局；一個都不能開就把主要那個的原因說出來
        final List<MapConfig.MapDef> openable = new ArrayList<>();
        for (final MapConfig.MapDef map : finals) {
            if (service.finalProblem(player, map) == null) {
                openable.add(map);
            }
        }
        if (openable.isEmpty()) {
            final String problem = service.finalProblem(player, finals.isEmpty() ? null : finals.getFirst());
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem == null ? "終局開不了" : problem)));
            return;
        }
        final int best = Math.max(1, service.bestTier(player));
        final List<Dialogs.Option> tiers = new ArrayList<>();
        for (int tier = best; tier >= 1; tier--) {
            final double life = config.finalLifeMultiplier(tier);
            tiers.add(new Dialogs.Option(String.valueOf(tier), "T" + tier + " <gray>（怪物等級 " + service.finalLevel(tier)
                    + (life > 1.0 ? "・Boss 生命 ×" + number(life) : "") + "）"));
        }
        final List<Dialogs.Field> fields = new ArrayList<>();
        if (openable.size() > 1 || finals.size() > 1) {
            final Set<String> completed = service.completed(player);
            final List<Dialogs.Option> bosses = new ArrayList<>();
            for (final MapConfig.MapDef map : openable) {
                bosses.add(new Dialogs.Option(map.id(), "<#af6025>" + ItemRenderer.escape(map.boss().name()) + " <gray>（" + ItemRenderer.escape(map.name())
                        + (completed.contains(map.id()) ? "，已打贏）" : "，還沒打贏）")));
            }
            fields.add(new Dialogs.Choice("boss", "Boss", bosses, openable.getFirst().id()));
        }
        fields.add(new Dialogs.Choice("tier", "階級", tiers, String.valueOf(best)));
        final CurrencyType fragment = plugin.currencies().repository().get(config.drops().fragment());
        player.closeInventory();
        plugin.dialogs().form(player, "<#f5b942>" + ItemRenderer.escape(openable.size() == 1 && finals.size() == 1 ? openable.getFirst().name() : "終局"),
                List.of("<gray>要用掉 " + config.drops().fragmentCost() + " 片" + (fragment == null ? "天穹碎片" : ItemRenderer.escape(fragment.name())),
                        "<gray>最高能開到你完成過的最高階（T" + best + "）",
                        "<dark_gray>階級越高 Boss 越強，掉的地圖與裝備越好"
                                + (finals.size() > openable.size() ? "；還沒解鎖的 Boss 不在清單裡（地圖裝置的終局按鈕寫著要先打贏誰）" : "")),
                fields, "打開傳送門",
                answers -> {
                    final int tier;
                    try {
                        tier = Integer.parseInt(answers.get("tier"));
                    } catch (final NumberFormatException e) {
                        return;
                    }
                    final MapConfig.MapDef chosen = answers.get("boss").isEmpty() ? openable.getFirst() : config.find(answers.get("boss"));
                    if (chosen == null || !chosen.isFinal()) {
                        return;
                    }
                    final String failed = service.activateFinal(player, chosen, tier);
                    if (failed != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(failed)));
                    }
                }, null);
    }

    /** 有極境版本的終局（依設定檔順序）。 */
    private List<MapConfig.MapDef> uberFinals(final MapConfig config) {
        final List<MapConfig.MapDef> list = new ArrayList<>();
        for (final MapConfig.MapDef map : config.regularFinals()) {
            if (config.uber(map.id()) != null) {
                list.add(map);
            }
        }
        return list;
    }

    /** 巔峰（2026-10）：集齊每一種碎片各一個才能開一次的最終 Boss；列出碎片集到哪、要先打贏誰。 */
    private void drawPinnacle(final MapConfig config, final Set<String> completed) {
        final List<MapConfig.MapDef> pinnacles = config.pinnacles();
        if (pinnacles.isEmpty()) {
            return;
        }
        final MapConfig.MapDef map = pinnacles.getFirst();
        final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + ItemRenderer.escape(map.lore()));
        lore.add("<gray>Boss：<#c77dff>" + ItemRenderer.escape(map.boss().name()));
        lore.add("<gray>固定開在 T" + config.tiers().max() + " 再高幾級（怪物等級 <white>"
                + config.pinnacleStats(map.id(), pinnacle).monsterLevel() + "<gray>），只有 Boss 房");
        lore.add("<red>每個人只能走進去一次<dark_gray>（死了或離開就不能再進）");
        lore.add("");
        final String missing = Atlas.missingRequirements(config, map, completed);
        if (!missing.isEmpty()) {
            lore.add("<red>先打贏 " + ItemRenderer.escape(missing));
        } else {
            lore.add((completed.contains(map.id()) ? "<green>✔ 已打贏" : "<white>要先打贏的終局都打贏了"));
        }
        lore.add("<gray>碎片（每一種各 1 個）：");
        boolean allFragments = true;
        for (final MapConfig.FragmentSource fragment : pinnacle.fragments()) {
            final CurrencyType type = plugin.currencies().repository().get(fragment.currency());
            final int have = type == null ? 0 : service.countCurrency(player, type);
            final MapConfig.MapDef from = config.find(fragment.from());
            allFragments &= have >= 1;
            lore.add((have >= 1 ? "<green>✔ " : "<dark_gray>・") + (have >= 1 ? "<white>" : "<gray>")
                    + ItemRenderer.escape(type == null ? fragment.currency() : type.name()) + " <dark_gray>×" + have
                    + "　" + ItemRenderer.escape(from == null ? fragment.from() : from.name()) + (fragment.minTier() > 1 ? " T" + fragment.minTier() + "+" : "")
                    + " 的 Boss " + Math.round(fragment.chance() * 100) + "%");
        }
        final boolean ready = service.pinnacleProblem(player, map) == null;
        if (ready) {
            lore.add("");
            lore.add("<green>點一下打開傳送門（會用掉碎片）");
            set(SLOT_PINNACLE, glowing(icon(Material.END_CRYSTAL, "<#c77dff>巔峰：" + ItemRenderer.escape(map.name()), lore)));
        } else {
            set(SLOT_PINNACLE, icon(allFragments && missing.isEmpty() ? Material.END_CRYSTAL : Material.ENDER_EYE,
                    "<gray>巔峰：" + ItemRenderer.escape(map.name()), lore));
        }
    }

    /** 巔峰：確認才用掉碎片。 */
    private void choosePinnacle() {
        final MapConfig config = service.config();
        final List<MapConfig.MapDef> pinnacles = config.pinnacles();
        final List<MapConfig.MapDef> openable = new ArrayList<>();
        for (final MapConfig.MapDef map : pinnacles) {
            if (service.pinnacleProblem(player, map) == null) {
                openable.add(map);
            }
        }
        if (openable.isEmpty()) {
            final String problem = pinnacles.isEmpty() ? "沒有巔峰" : service.pinnacleProblem(player, pinnacles.getFirst());
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem == null ? "巔峰開不了" : problem)));
            return;
        }
        final List<Dialogs.Option> bosses = new ArrayList<>();
        for (final MapConfig.MapDef map : openable) {
            final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
            bosses.add(new Dialogs.Option(map.id(), "<#c77dff>" + ItemRenderer.escape(map.boss().name()) + " <gray>"
                    + config.pinnacleStats(map.id(), pinnacle).monsterLevel() + " 級（" + ItemRenderer.escape(map.name()) + "）"));
        }
        player.closeInventory();
        plugin.dialogs().form(player, "<#c77dff>巔峰",
                List.of("<gray>要用掉每一種碎片各 1 個，開在 T" + config.tiers().max() + " 再高幾級",
                        "<gray>只有 Boss 房：一進去下一間就是 Boss",
                        "<red>每個人只能走進去一次：死了或離開（包括回營地）就不能再進",
                        "<dark_gray>準備好藥劑與抗性再進去；隊友也是各進一次"),
                List.of(new Dialogs.Choice("boss", "挑戰", bosses, openable.getFirst().id())), "打開傳送門",
                answers -> {
                    final MapConfig.MapDef chosen = answers.get("boss").isEmpty() ? openable.getFirst() : config.find(answers.get("boss"));
                    if (chosen == null || !chosen.isFinal()) {
                        return;
                    }
                    final String failed = service.activatePinnacle(player, chosen);
                    if (failed != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(failed)));
                    }
                }, null);
    }

    /** 極境：終局 Boss 的最強版本；列出每一個能不能開、打贏了沒，鑰匙夠不夠。 */
    private void drawUber(final MapConfig config, final Set<String> completed) {
        final List<MapConfig.MapDef> ubers = uberFinals(config);
        if (ubers.isEmpty()) {
            return;
        }
        final Set<String> defeated = service.uberDefeated(player);
        final MapConfig.Uber first = config.uber(ubers.getFirst().id());
        final boolean sameKey = sameKey(config, ubers);
        final CurrencyType key = plugin.currencies().repository().get(first.key());
        final int have = key == null ? 0 : service.countCurrency(player, key);
        final String keyName = key == null ? first.key() : key.name();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>終局 Boss 的最強版本：固定開在最高階再高幾級，");
        lore.add("<gray>Boss 更硬、更痛、招式更快，還有極境才有的招式與機制");
        lore.add("<red>每個人只能走進去一次<dark_gray>（死了或離開就不能再進）");
        lore.add("");
        boolean any = false;
        for (final MapConfig.MapDef map : ubers) {
            final MapConfig.Uber uber = config.uber(map.id());
            final String problem = service.uberProblem(player, map);
            final boolean done = defeated.contains(map.id());
            final boolean ready = problem == null;
            any |= ready;
            final String bossName = uber.name() != null ? uber.name() : map.boss().name() + "・極境";
            lore.add((done ? "<green>✔ " : ready ? "<white>・" : "<dark_gray>・") + (ready || done ? "<#ff5f5f>" : "<dark_gray>")
                    + ItemRenderer.escape(bossName) + (done ? " <green>已打贏" : "")
                    + (!completed.contains(map.id()) ? " <dark_gray>先打贏一般的" + ItemRenderer.escape(map.boss().name()) : "")
                    + (sameKey ? "" : " <dark_gray>" + keyText(uber)));
        }
        lore.add("");
        if (sameKey) {
            lore.add((have >= first.keyCost() ? "<green>" : "<red>") + "需要 " + first.keyCost() + " 個" + ItemRenderer.escape(keyName)
                    + "（你有 " + have + " 個）");
            lore.add("<dark_gray>" + ItemRenderer.escape(keyName) + "：終局開到 T" + first.keyMinTier() + " 以上，Boss 有機率掉");
        } else {
            lore.add("<dark_gray>開門的鑰匙：那個終局開到夠高的階，Boss 有機率掉");
        }
        if (any) {
            lore.add("<green>點一下選要挑戰哪一個");
            set(SLOT_UBER, glowing(icon(Material.CRYING_OBSIDIAN, "<#ff5f5f>極境", lore)));
        } else {
            set(SLOT_UBER, icon(Material.OBSIDIAN, "<gray>極境", lore));
        }
    }

    /** 極境：選一個（能開的），確定才用掉鑰匙。 */
    private void chooseUber() {
        final MapConfig config = service.config();
        final List<MapConfig.MapDef> ubers = uberFinals(config);
        final List<MapConfig.MapDef> openable = new ArrayList<>();
        for (final MapConfig.MapDef map : ubers) {
            if (service.uberProblem(player, map) == null) {
                openable.add(map);
            }
        }
        if (openable.isEmpty()) {
            final String problem = ubers.isEmpty() ? "沒有極境" : service.uberProblem(player, ubers.getFirst());
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem == null ? "極境開不了" : problem)));
            return;
        }
        final boolean sameKey = sameKey(config, openable);
        final List<Dialogs.Option> bosses = new ArrayList<>();
        for (final MapConfig.MapDef map : openable) {
            final MapConfig.Uber uber = config.uber(map.id());
            final int level = config.uberStats(map.id(), uber).monsterLevel();
            bosses.add(new Dialogs.Option(map.id(), "<#ff5f5f>" + ItemRenderer.escape(uber.name() != null ? uber.name() : map.boss().name() + "・極境")
                    + " <gray>" + level + " 級・生命 ×" + number(uber.life()) + "（比 T" + config.tiers().max() + " 終局）・傷害 ×" + number(uber.damage())
                    + (sameKey ? "" : "・" + keyText(uber))));
        }
        final MapConfig.Uber first = config.uber(openable.getFirst().id());
        player.closeInventory();
        plugin.dialogs().form(player, "<#ff5f5f>極境",
                List.of("<gray>要用掉 " + (sameKey ? keyText(first) : "清單上寫的鑰匙") + "，開在 T" + config.tiers().max() + " 再高幾級",
                        "<gray>Boss 的生命、傷害比一般版本高（清單上的倍率），所有怪物的招式冷卻更短",
                        "<red>每個人只能走進去一次：死了或離開（包括回營地）就不能再進",
                        "<dark_gray>準備好藥劑與抗性再進去；隊友也是各進一次"),
                List.of(new Dialogs.Choice("boss", "挑戰", bosses, openable.getFirst().id())), "打開傳送門",
                answers -> {
                    final MapConfig.MapDef chosen = answers.get("boss").isEmpty() ? openable.getFirst() : config.find(answers.get("boss"));
                    if (chosen == null || !chosen.isFinal()) {
                        return;
                    }
                    final String failed = service.activateUber(player, chosen);
                    if (failed != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(failed)));
                    }
                }, null);
    }

    /** 這些極境開門的鑰匙都一樣（通貨與個數）。 */
    private static boolean sameKey(final MapConfig config, final List<MapConfig.MapDef> ubers) {
        final MapConfig.Uber first = config.uber(ubers.getFirst().id());
        for (final MapConfig.MapDef map : ubers) {
            final MapConfig.Uber uber = config.uber(map.id());
            if (!uber.key().equals(first.key()) || uber.keyCost() != first.keyCost()) {
                return false;
            }
        }
        return true;
    }

    /** 「1 個深淵星核」。 */
    private String keyText(final MapConfig.Uber uber) {
        final CurrencyType key = plugin.currencies().repository().get(uber.key());
        return uber.keyCost() + " 個" + ItemRenderer.escape(key == null ? uber.key() : key.name());
    }

    /** 3.0 → 「3」、1.5 → 「1.5」。 */
    static String number(final double value) {
        final double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    private boolean inParty() {
        return plugin.party() != null && plugin.party().inParty(player);
    }

    /** 你（隊伍）目前的地圖：傳送門還能進幾次、誰在裡面。 */
    private void drawStatus() {
        final MapInstance instance = service.target(player);
        if (instance == null) {
            set(SLOT_STATUS, icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>你" + (inParty() ? "和隊友" : "") + "沒有開著的地圖",
                    "<dark_gray>開一張地圖，後面的門就會亮起來",
                    "<dark_gray>每個人（隊伍）有自己的傳送門，互不佔位"));
            return;
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + ItemRenderer.escape(instance.ownerName()) + " 開的・怪物等級 " + instance.level());
        lore.add(instance.state() == MapInstance.State.BUILDING ? "<yellow>成形中……"
                : (instance.usesAttempts() ? "<#f5b942>" + MapService.attemptText(instance) + "<gray>"
                : instance.singleEntry() ? "<red>每個人只能進入 1 次<gray>" : "<gray>" + MapService.waveText(instance))
                        + "・裡面 " + instance.inside().size() + " 人");
        if (instance.bossKilled()) {
            lore.add("<#f5b942>已完成");
        }
        if (!instance.atlas().isEmpty()) {
            lore.add("<#9fd3ff>星圖天賦<gray>（" + ItemRenderer.escape(instance.ownerName()) + " 點的）："
                    + ItemRenderer.escape(MapService.atlasSummary(instance.atlas(), 3)));
        }
        if (instance.usesAttempts()) {
            lore.add(instance.attemptActive() && instance.enteredThisAttempt(player.getUniqueId())
                    ? "<red>這一門你已經出來了：等裡面的人全部倒下（或出來）再走下一門"
                    : "<gray>一門一次挑戰：裡面的人全部倒下，下一門 Boss 回滿重來；打倒 Boss 門全部關掉");
        }
        if (instance.singleEntry() && !instance.usesAttempts()) {
            lore.add(instance.uberEntered(player.getUniqueId()) ? "<red>每個人只能進一次：你已經進去過了"
                    : "<red>每個人只能進一次<dark_gray>（死了或離開就不能再進）");
        }
        lore.add("<dark_gray>走進後面亮著的門進去");
        set(SLOT_STATUS, glowing(icon(Material.PURPLE_STAINED_GLASS_PANE, "<#9fd3ff>目前的地圖：" + ItemRenderer.escape(instance.label()), lore)));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_MAP -> {
                selected = null;
                selectedSlot = -1;
                redraw();
            }
            case SLOT_OPEN -> {
                if (selected == null) {
                    return;
                }
                final String problem = service.activate(player, selectedSlot, selected);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                    selected = null;
                    selectedSlot = -1;
                    redraw();
                    return;
                }
                player.closeInventory();
            }
            case SLOT_ATLAS -> new AtlasMenu(plugin, player, service).open();
            case SLOT_TREE -> new AtlasTreeMenu(plugin, player, service).open();
            case SLOT_FINAL -> chooseFinalTier();
            case SLOT_PINNACLE -> {
                if (!service.config().pinnacles().isEmpty()) {
                    choosePinnacle();
                }
            }
            case SLOT_UBER -> {
                if (!uberFinals(service.config()).isEmpty()) {
                    chooseUber();
                }
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    /** 點背包裡的地圖：選起來（東西不動）。 */
    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || !service.isMap(data.get())) {
            player.sendMessage(plugin.text().prefixed("<gray>這不是地圖"));
            return;
        }
        selected = data.get();
        selectedSlot = slot;
        redraw();
    }
}
