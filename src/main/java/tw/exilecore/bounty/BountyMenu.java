package tw.exilecore.bounty;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.Menu;
import tw.exilecore.map.MapStats;
import tw.exilecore.zone.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * 委託板（/bounty、營地的軍需官）：今天的每日委託、每週回響、功勳與兌換。
 */
final class BountyMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    /** 每天幾個委託 → 放在第二列的哪幾格。 */
    private static final int[][] DAILY_SLOTS = {{}, {13}, {12, 14}, {11, 13, 15}, {10, 12, 14, 16}, {11, 12, 13, 14, 15},
            {10, 11, 12, 14, 15, 16}};
    private static final int SLOT_WEEKLY = 29;
    private static final int SLOT_INFO = 31;
    private static final int SLOT_SHOP = 33;
    private static final int SLOT_BACK = 36;
    private static final int SLOT_CLOSE = 44;

    private final BountyService service;

    BountyMenu(final ExileCore plugin, final Player player, final BountyService service) {
        super(plugin, player, 5, "<dark_gray>委託板");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 45; i++) {
            set(i, filler());
        }
        final BountyConfig config = service.config();
        final List<BountyBoard.Daily> dailies = service.today(player);
        int done = 0;
        for (final BountyBoard.Daily daily : dailies) {
            if (daily.done()) {
                done++;
            }
        }
        final long merits = Math.max(0, service.merits(player));
        final boolean weeklyDone = service.weeklyDone(player);
        set(SLOT_HEADER, glowing(icon(Material.WRITABLE_BOOK, BountyService.COLOR + "委託板",
                "<gray>功勳 " + BountyService.MERIT + BountyService.format(merits) + "</#f5b942> <dark_gray>（帳號共用、不能交易）",
                "<gray>每日委託 <white>" + done + "/" + dailies.size() + " <dark_gray>（" + service.untilTomorrow() + "後換新的）",
                config.weekly().enabled() ? "<gray>每週回響 " + (weeklyDone ? "<green>完成了" : "<yellow>還沒打")
                        + " <dark_gray>（" + service.untilNextWeek() + "後重置）" : "",
                "",
                "<dark_gray>整個帳號共用：哪個角色做都算，經驗給完成的角色")));
        final int[] slots = DAILY_SLOTS[Math.min(DAILY_SLOTS.length - 1, config.dailyCount())];
        for (int i = 0; i < slots.length; i++) {
            set(slots[i], i < dailies.size() ? daily(config, dailies.get(i))
                    : icon(Material.GRAY_DYE, "<dark_gray>（還沒有委託）", "<gray>選好角色就會有"));
        }
        set(SLOT_WEEKLY, weekly(config, weeklyDone));
        set(SLOT_INFO, icon(Material.BOOK, "<white>說明",
                "<gray>每天 0 點換一批每日委託、每週一 0 點重置回響",
                "<gray>完成時給功勳（帳號共用）與經驗（給完成的角色，",
                "<gray>升到下一級所需經驗的百分比" + (config.experienceLevelCap() > 0
                        ? "；超過 " + config.experienceLevelCap() + " 級照 " + config.experienceLevelCap() + " 級算" : "") + "；滿級沒有）",
                "<gray>擊殺、撿東西、打 Boss、完成地圖只算",
                "<gray>怪物等級不比你低超過 <white>" + config.levelGap() + "</white> 級的",
                "<dark_gray>（" + MapStats.tiers().topLevel() + " 級以上的角色當作 " + MapStats.tiers().topLevel() + " 級）",
                "<gray>功勳不能交易，在這裡換倉庫頁、藥劑、天穹碎片、稱號"));
        if (config.shop().enabled()) {
            set(SLOT_SHOP, glowing(icon(Material.EMERALD, BountyService.MERIT + "功勳兌換",
                    "<gray>功勳 " + BountyService.MERIT + BountyService.format(merits),
                    "<gray>倉庫頁、藥劑、天穹碎片、稱號",
                    "<dark_gray>都是便利品：不賣通貨、不賣金幣",
                    "",
                    "<yellow>左鍵：打開")));
        }
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack daily(final BountyConfig config, final BountyBoard.Daily daily) {
        final BountyConfig.Template template = config.template(daily.template());
        final String text = BountyService.escape(template.describe());
        if (daily.done()) {
            // 做完了：經驗已經給過，不寫數字（升級之後算出來的會跟當時拿到的不一樣）
            return icon(Material.WRITTEN_BOOK, "<green>✔ " + text, "<gray>完成了：功勳 +" + template.merits()
                    + (template.experience() > 0 ? "、經驗" : ""), "", "<dark_gray>明天 0 點換新的");
        }
        final String reward = "功勳 +" + template.merits() + experienceText(template.experience());
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>進度 <white>" + daily.count() + "/" + template.count());
        lore.add("<gray>獎勵：<white>" + reward);
        lore.add("");
        switch (template.goal()) {
            case KILL -> lore.add("<dark_gray>" + switch (template.filter()) {
                case ANY -> "什麼怪都算";
                case MAGIC -> "魔法、稀有怪與 Boss 都算";
                case RARE -> "稀有怪與 Boss 都算";
            });
            case COLLECT -> lore.add("<dark_gray>普通怪 " + Math.round(template.chance() * 100) + "%、魔法怪 "
                    + Math.round(Math.min(1, template.chance() * 2) * 100) + "%、稀有以上必掉（不佔背包）");
            case BOSS -> lore.add("<dark_gray>區域 Boss、地圖 Boss、回響都算（有打到 5% 傷害）");
            case MAPS -> lore.add("<dark_gray>地圖 Boss 倒下時在地圖裡就算");
        }
        lore.add("<dark_gray>" + service.levelGapText());
        final ItemStack icon = icon(material(template.goal()), "<white>" + text, lore);
        if (daily.count() > 0) {
            withCount(icon, Math.min(99, daily.count()));
        }
        return icon;
    }

    /** 「、經驗 +2,166,264（升級所需的 15%）」：這個角色現在完成會拿到多少；沒有經驗（或滿級）是空字串。 */
    private String experienceText(final double fraction) {
        final long xp = service.experience(player, fraction);
        if (fraction <= 0 || xp <= 0) {
            return "";
        }
        final int cap = service.config().experienceLevelCap();
        final boolean capped = cap > 0 && plugin.players().get(player).level() > cap;
        final String bonus = plugin.players().config().experienceBonusText();
        return "、經驗 +" + BountyService.format(xp) + "<dark_gray>（" + (capped ? cap + " 級" : "") + "升級所需的 "
                + Math.round(fraction * 100) + "%" + (bonus == null ? "" : "，全服 " + bonus) + "）<white>";
    }

    private static Material material(final BountyConfig.Goal goal) {
        return switch (goal) {
            case KILL -> Material.IRON_SWORD;
            case COLLECT -> Material.AMETHYST_SHARD;
            case BOSS -> Material.WITHER_SKELETON_SKULL;
            case MAPS -> Material.FILLED_MAP;
        };
    }

    private ItemStack weekly(final BountyConfig config, final boolean done) {
        final BountyConfig.Weekly weekly = config.weekly();
        if (!weekly.enabled()) {
            return icon(Material.GRAY_DYE, "<dark_gray>每週回響（沒有開放）");
        }
        if (done) {
            return icon(Material.ECHO_SHARD, "<green>✔ 每週回響：這週完成了", "<gray>" + service.untilNextWeek() + "後可以再打一次");
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>星骸記得它吞下過的東西：打倒過的區域 Boss");
        lore.add("<gray>會以更強的「回響」回來（生命 ×" + trim(weekly.life()) + "、傷害 ×" + trim(weekly.damage()) + "）");
        lore.add("<gray>獎勵：<white>功勳 +" + weekly.merits() + experienceText(weekly.experience())
                + "<gray>，掉落 +" + Math.round(weekly.quantity()) + "% 數量");
        lore.add("<dark_gray>每個帳號每週一次；怪物等級跟著你的等級");
        lore.add("");
        final List<Zone> zones = service.echoZones(player);
        if (zones.isEmpty()) {
            lore.add("<red>這個角色還沒打倒過任何區域 Boss");
        } else {
            final List<String> names = new ArrayList<>();
            for (final Zone zone : zones) {
                names.add(BountyService.escape(zone.boss().name()));
            }
            lore.add("<gray>可以挑戰：<white>" + String.join("、", names));
            lore.add("<yellow>左鍵：選一隻 Boss 的回響");
        }
        return glowing(icon(Material.ECHO_SHARD, BountyService.ECHO + "每週回響", lore));
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 100) / 100.0);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_WEEKLY -> chooseEcho();
            case SLOT_SHOP -> {
                if (service.config().shop().enabled()) {
                    new MeritShopMenu(plugin, player, service).open();
                }
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    /** 選一隻打倒過的 Boss 開回響（對話視窗的按鈕）。 */
    private void chooseEcho() {
        if (!service.config().weekly().enabled()) {
            return;
        }
        if (service.weeklyDone(player)) {
            player.sendMessage(plugin.text().prefixed("<gray>這週的回響已經完成了，" + service.untilNextWeek() + "後再來"));
            return;
        }
        final List<Zone> zones = service.echoZones(player);
        if (zones.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>這個角色還沒打倒過任何區域 Boss（先打倒潮濕海岸的溺潮之母）"));
            return;
        }
        final List<String> body = new ArrayList<>();
        body.add("<gray>" + BountyService.escape(service.config().weekly().lore()));
        body.add("");
        body.add("<gray>選一隻：牠的回響會出現在<white>地圖裝置後面的傳送門</white>（你和隊友可以進去）。");
        body.add("<gray>怪物等級＝那個區域的最高等級與你的等級，取高的。");
        if (plugin.maps() != null) {
            body.add("<gray>跟地圖一樣：傳送門能進 " + plugin.maps().config().settings().portals() + " 次，在裡面死掉會扣經驗。");
        }
        body.add("<yellow>會關掉你目前那張地圖的傳送門<gray>（裡面的人可以打完再出來）。");
        final List<Dialogs.Button> buttons = new ArrayList<>();
        for (final Zone zone : zones) {
            buttons.add(Dialogs.Button.run(BountyService.ECHO + BountyService.escape(service.config().weekly().prefix() + zone.boss().name())
                    + " <gray>Lv." + service.echoLevel(player, zone), () -> {
                        final String problem = service.openEcho(player, zone);
                        if (problem != null) {
                            player.sendMessage(plugin.text().prefixed("<red>" + BountyService.escape(problem)));
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                        } else {
                            player.sendMessage(plugin.text().prefixed(BountyService.ECHO + "回響正在成形<gray>：好了之後走進地圖裝置後面亮著（紫色）的門"));
                        }
                    }));
        }
        buttons.add(Dialogs.Button.run("<gray>回委託板", () -> service.open(player)));
        plugin.dialogs().notice(player, BountyService.ECHO + "每週回響", body, buttons, 1);
    }
}
