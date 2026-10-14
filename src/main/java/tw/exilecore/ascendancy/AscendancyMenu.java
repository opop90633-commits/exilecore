package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.Menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 昇華與試煉（/ascend、星衛明夷）：四個試煉（解鎖條件、完成了沒有、開啟）、昇華職業與昇華天賦的入口、分身獎勵。
 */
final class AscendancyMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_TREE = 20;
    private static final int SLOT_LEGACY = 22;
    private static final int SLOT_HELP = 24;
    private static final int SLOT_BACK = 27;
    private static final int SLOT_PREVIEW = 31;
    /** 月卡福利（2026-10）：隨時更換昇華職業。 */
    private static final int SLOT_RESPEC = 33;
    private static final int SLOT_CLOSE = 35;

    private final AscendancyService service;
    private final Map<Integer, AscendancyConfig.Trial> trials = new HashMap<>();

    AscendancyMenu(final ExileCore plugin, final Player player, final AscendancyService service) {
        super(plugin, player, 4, "<dark_gray>昇華與試煉");
        this.service = service;
    }

    /** n 個試煉放在第二列的哪幾格。 */
    static int[] trialSlots(final int count) {
        return switch (count) {
            case 0 -> new int[0];
            case 1 -> new int[] {13};
            case 2 -> new int[] {12, 14};
            case 3 -> new int[] {11, 13, 15};
            case 4 -> new int[] {10, 12, 14, 16};
            default -> {
                final int[] slots = new int[count];
                final int first = 13 - count / 2;
                for (int i = 0; i < count; i++) {
                    slots[i] = first + i;
                }
                yield slots;
            }
        };
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 36; i++) {
            set(i, filler());
        }
        trials.clear();
        final PlayerState state = plugin.players().get(player);
        final Ascension ascension = service.ascension(state);
        final AscendancyConfig config = service.config();
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();

        // 標題：昇華職業與昇華點
        final List<String> header = new ArrayList<>();
        final String classId = state.character() == null ? "" : state.character().characterClass().id();
        header.add("<gray>職業：" + AscendancyService.classColor(classId) + AscendancyService.className(classId)
                + (chosen == null ? "" : "<gray>　昇華：" + chosen.colored()));
        if (chosen == null) {
            header.add(ascension.canChoose() ? "<#f5b942>你可以昇華了：點下面的「選擇昇華職業」"
                    : "<gray>還沒昇華：完成第一試煉之後選擇（選了不能換）");
        }
        header.add("<gray>試煉 <white>" + ascension.completedCount() + "/" + config.trials().size() + "</white>　昇華點 <white>"
                + ascension.points() + "/" + config.totalPoints() + "</white>　已配 <white>" + ascension.spent() + "</white>　可配 <white>"
                + ascension.available());
        final int cap = config.settings().weeklyOpens();
        if (cap > 0) {
            header.add("<gray>這週開了 <white>" + service.opensThisWeek(player) + "/" + cap + "</white> 次試煉<dark_gray>（"
                    + service.untilNextWeek() + "後重置）");
        }
        header.add("<dark_gray>每個試煉第一次完成給昇華點；昇華天賦一個 1 點（四個試煉一共 8 點）");
        set(SLOT_HEADER, glowing(icon(Material.NETHER_STAR, AscendancyService.COLOR + "昇華", header)));

        // 試煉
        final List<AscendancyConfig.Trial> list = new ArrayList<>(config.trials());
        final int[] slots = trialSlots(list.size());
        final long wait = service.cooldownSeconds(player);
        final boolean capped = config.settings().weeklyOpens() > 0 && service.opensThisWeek(player) >= config.settings().weeklyOpens();
        for (int i = 0; i < list.size(); i++) {
            trials.put(slots[i], list.get(i));
            set(slots[i], trial(list.get(i), ascension, wait, capped));
        }

        // 昇華職業、昇華天賦
        set(SLOT_TREE, treeButton(ascension));
        set(SLOT_LEGACY, legacy());
        set(SLOT_HELP, icon(Material.BOOK, "<white>昇華是什麼",
                "<gray>三百年前，皇帝在熔爐前立了四道門，讓星衛一道一道走過去，",
                "<gray>把星骸煉進身體——那叫昇華。門還在星骸的記憶裡。",
                "",
                "<gray>・試煉在地圖世界裡：開了之後走地圖裝置後面的傳送門",
                "<gray>・打倒守門的爐監殷燼就算完成（隊友一起打也算）",
                "<gray>・每個試煉第一次完成給昇華點；完成過的不能再開，可以跟著隊友進去幫忙",
                "<gray>・每週能開的次數有上限（打倒前全倒了可以重開）",
                "<gray>・第一次完成後選昇華職業：每個職業兩到三個，選了不能換（有月卡隨時可以換）",
                "<gray>・昇華天賦一個 1 點；每條分支要從第一個配起",
                "<gray>・退掉昇華天賦花金幣（跟天賦樹退點同一個價格）",
                plugin.players().config().deathPenaltyText() != null
                        ? "<gray>・" + plugin.players().config().deathPenaltyText() + "（哪裡都一樣）"
                        : "<gray>・死亡不扣經驗"));
        set(SLOT_PREVIEW, icon(Material.SPYGLASS, "<white>昇華預覽",
                "<gray>還沒選、還沒解鎖也能先看：每個昇華職業的完整天賦樹",
                "<dark_gray>也可以切換成看全部職業的昇華（練分身前先看看）",
                "",
                "<yellow>左鍵：打開預覽<dark_gray>（/ascend preview）"));
        if (chosen != null) {
            final boolean card = plugin.sponsors() != null && plugin.sponsors().cardActive(player);
            set(SLOT_RESPEC, icon(Material.CLOCK, "<#bcd4ff>更換昇華職業（月卡）",
                    "<gray>現在：" + chosen.colored(),
                    "<gray>清掉昇華職業與昇華天賦（點數全部拿回來，試煉不用重打），",
                    "<gray>重新選這個職業的昇華",
                    card ? "<green>月卡：隨時可以換，不花錢" : "<dark_gray>要有月卡才能換（沒有月卡選了就不能換）",
                    "", card ? "<yellow>Shift＋左鍵：更換" : ""));
        }
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack trial(final AscendancyConfig.Trial trial, final Ascension ascension, final long wait, final boolean capped) {
        final boolean done = ascension.completed(trial);
        final String reason = ascension.lockReason(trial, service::bossName);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>怪物等級 <white>" + trial.level() + "</white>　" + (done ? "<green>✔ 完成了" : reason == null ? "<yellow>可以挑戰" : "<red>還沒解鎖"));
        if (!trial.lore().isBlank()) {
            lore.add("<dark_gray><i>" + AscendancyService.escape(trial.lore()));
        }
        lore.add("");
        final AscendancyConfig.Requirements requires = trial.requires();
        if (requires.trial() != null) {
            final AscendancyConfig.Trial before = service.config().trial(requires.trial());
            lore.add(check(before != null && ascension.completed(before), "完成「" + (before == null ? requires.trial() : before.name()) + "」"));
        }
        if (requires.boss() != null) {
            lore.add(check(plugin.players().get(player).progress().containsKey("boss." + requires.boss()),
                    "用這個角色打倒" + service.bossName(requires.boss())));
        }
        if (requires.maps() > 0) {
            final int maps = Ascension.mapsCompleted(plugin.players().get(player).progress());
            lore.add(check(maps >= requires.maps(), "完成 " + requires.maps() + " 張不同的地圖（現在 " + Math.min(maps, requires.maps()) + " 張）"));
        }
        if (requires.level() > 0) {
            lore.add(check(plugin.players().get(player).level() >= requires.level(), "等級 " + requires.level()));
        }
        if (done) {
            lore.add("<green>昇華點 +" + trial.points() + " 拿過了");
        } else {
            lore.add("<#f5b942>第一次完成：昇華點 +" + trial.points());
        }
        lore.add("<dark_gray>守門的是" + AscendancyService.escape(trial.boss().name()) + "；Boss 的物品數量 +" + trial.quantity() + "%");
        lore.add("");
        if (done) {
            lore.add("<gray>完成過了：隊友開的試煉可以跟著進去幫忙（自己不能再開）");
        } else if (reason != null) {
            lore.add("<red>" + AscendancyService.escape(reason));
        } else if (capped) {
            lore.add("<red>這週的試煉次數用完了，" + service.untilNextWeek() + "後重置");
        } else if (wait > 0) {
            lore.add("<gray>剛開過試煉，" + wait + " 秒後才能再開");
        } else {
            lore.add("<yellow>左鍵：開啟（地圖裝置後面的傳送門）");
            lore.add("<dark_gray>會關掉你目前那張地圖的傳送門；打倒前可以重開（算這週的次數）");
        }
        final Material material = done ? Material.ENDER_EYE : reason == null ? Material.END_PORTAL_FRAME : Material.IRON_BARS;
        final ItemStack icon = icon(material, (reason == null || done ? AscendancyService.COLOR : "<gray>")
                + AscendancyService.escape(trial.name()), lore);
        return reason == null && !done && !capped ? glowing(icon) : icon;
    }

    private static String check(final boolean ok, final String text) {
        return (ok ? "<green>✔ " : "<red>✘ ") + "<gray>" + AscendancyService.escape(text);
    }

    private ItemStack treeButton(final Ascension ascension) {
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        if (chosen != null) {
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>已配 <white>" + ascension.spent() + "</white> 點，可配 <white>" + ascension.available() + "</white> 點");
            for (final AscendancyConfig.Node node : ascension.allocated()) {
                lore.add("<green>✔ <white>" + AscendancyService.escape(node.name()));
            }
            lore.add("");
            lore.add("<yellow>左鍵：打開昇華天賦<dark_gray>（/ascend tree）");
            final ItemStack icon = icon(Menu.material(chosen.icon(), Material.NETHER_STAR), chosen.colored() + " <white>昇華天賦", lore);
            return ascension.canAllocateAny() ? glowing(icon) : icon;
        }
        final List<String> names = new ArrayList<>();
        for (final AscendancyConfig.Ascendancy option : ascension.options()) {
            names.add(option.colored());
        }
        if (ascension.canChoose()) {
            return glowing(icon(Material.NETHER_STAR, "<#f5b942>選擇昇華職業",
                    "<gray>你的職業可以選：" + String.join("<gray>、", names),
                    "<red>選了就不能換（這個角色）",
                    "",
                    "<yellow>左鍵：看看有哪幾條路"));
        }
        if (names.isEmpty()) {
            return icon(Material.GRAY_DYE, "<gray>昇華職業", "<dark_gray>你的職業還沒有昇華職業");
        }
        return icon(Material.SPYGLASS, "<white>昇華職業（預覽）",
                "<gray>你的職業可以選：" + String.join("<gray>、", names),
                "<dark_gray>完成第一試煉之後選擇（選了不能換）",
                "",
                "<yellow>左鍵：先看看每條路的天賦");
    }

    private ItemStack legacy() {
        final List<String> lore = new ArrayList<>();
        // 門檻照 ascendancy.yml（每種職業的階一樣時寫成「60／75／90 級」）
        final java.util.TreeSet<Integer> levels = new java.util.TreeSet<>();
        for (final List<AscendancyConfig.Tier> tiers : service.config().legacy().values()) {
            for (final AscendancyConfig.Tier tier : tiers) {
                levels.add(tier.level());
            }
        }
        final List<String> levelText = new ArrayList<>();
        for (final int level : levels) {
            levelText.add(String.valueOf(level));
        }
        lore.add("<gray>帳號裡每種職業等級最高的角色，到了 " + String.join("／", levelText) + " 級，");
        lore.add("<gray>帳號裡的每個角色（包括它自己）都拿到那個職業的加成。");
        lore.add("<dark_gray>同一種職業只算最高的那個；只算到達的最高一階");
        boolean any = false;
        for (final Legacy.Bonus bonus : service.legacy(player)) {
            lore.add("");
            final String name = AscendancyService.classColor(bonus.classId()) + AscendancyService.className(bonus.classId());
            lore.add(name + "<gray>：" + (bonus.best() == null ? "<dark_gray>還沒有這個職業的角色"
                    : "<white>" + AscendancyService.escape(bonus.best().name()) + " <gray>Lv." + bonus.best().level()));
            if (bonus.tier() != null) {
                any = true;
                lore.add("  <green>✔ " + String.join("、", StatLines.describeAll(bonus.tier().stats())) + " <dark_gray>（Lv." + bonus.tier().level() + "）");
            }
            if (bonus.next() != null) {
                lore.add("  <dark_gray>Lv." + bonus.next().level() + "：" + String.join("、", StatLines.describeAll(bonus.next().stats())));
            }
        }
        final ItemStack icon = icon(Material.TOTEM_OF_UNDYING, "<#f5b942>分身獎勵", lore);
        return any ? glowing(icon) : icon;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_CLOSE -> goBack();
            case SLOT_TREE -> service.openTree(player);
            case SLOT_PREVIEW -> service.openPreview(player, null, false);
            case SLOT_RESPEC -> {
                if (click != ClickType.SHIFT_LEFT) {
                    return;
                }
                if (plugin.sponsors() == null || !plugin.sponsors().cardActive(player)) {
                    player.sendMessage(plugin.text().prefixed("<red>更換昇華職業是月卡的福利"));
                    return;
                }
                if (service.ascension(plugin.players().get(player)).chosen() == null) {
                    return;
                }
                service.adminReset(player);
                player.sendMessage(plugin.text().prefixed("<#bcd4ff>月卡<gray>：清掉了昇華職業與昇華天賦（點數拿回來了），重新選一個"));
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
                new AscendancyChoiceMenu(plugin, player, service).open();
            }
            default -> {
                final AscendancyConfig.Trial trial = trials.get(slot);
                if (trial == null || !acceptAction(click)) {
                    return;
                }
                final String problem = service.openTrial(player, trial);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + AscendancyService.escape(problem)));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                    redraw();
                    return;
                }
                player.closeInventory();
                player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 0.8f);
            }
        }
    }
}
