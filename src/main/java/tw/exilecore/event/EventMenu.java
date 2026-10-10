package tw.exilecore.event;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 每月活動（/event）：這個月的主題、對地圖的影響、每月挑戰的進度條、限定稱號。
 */
public final class EventMenu extends Menu {

    private static final int SLOT_THEME = 4;
    private static final int[] CHALLENGE_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    private static final int SLOT_TITLE = 31;
    private static final int SLOT_MENU = 48;
    private static final int SLOT_CLOSE = 50;

    private final MonthlyEventService service;

    public EventMenu(final ExileCore plugin, final Player player, final MonthlyEventService service) {
        super(plugin, player, 6, "<dark_gray>每月活動");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        final EventConfig.Theme theme = service.theme();
        if (theme == null) {
            set(SLOT_THEME, icon(Material.BARRIER, "<gray>這個月沒有活動", "<dark_gray>events.yml 的 settings.enabled"));
            set(SLOT_CLOSE, closeButton());
            return;
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray><i>" + ItemRenderer.escape(theme.lore()));
        lore.add("");
        lore.add("<white>這個月的地圖：");
        for (final String line : service.effectLines(theme)) {
            lore.add("<gray>・" + ItemRenderer.escape(line));
        }
        lore.add("");
        lore.add("<gray>剩 <white>" + service.daysLeft() + "<gray> 天，下個月換主題（挑戰也重新開始）");
        lore.add("<dark_gray>整個帳號共用：哪個角色做都算");
        final int minLevel = service.minLevel(player);
        lore.add("");
        lore.add("<white>怎樣才算：");
        lore.add("<gray>・你現在要打 <white>" + EventText.atLeast(minLevel) + "<gray> 的怪（地圖）才算");
        if (plugin.bounties() != null) {
            lore.add("<dark_gray>　" + ItemRenderer.escape(plugin.bounties().levelGapText()));
        }
        lore.add("<gray>・每日委託也是同樣的規則");
        final EventText.Note note = service.lastNote(player);
        lore.add(note == null ? "<dark_gray>最近一次擊殺：這次登入後還沒有" : "<gray>最近一次擊殺：" + EventText.describe(note, System.currentTimeMillis()));
        set(SLOT_THEME, glowing(icon(Material.NETHER_STAR, MonthlyEventService.themeName(theme), lore)));

        final EventProgress progress = service.progress(player);
        int index = 0;
        for (final EventConfig.Challenge challenge : theme.challenges()) {
            if (index >= CHALLENGE_SLOTS.length) {
                break;
            }
            set(CHALLENGE_SLOTS[index++], challengeIcon(challenge, progress, minLevel));
        }
        final int done = progress == null ? 0 : progress.doneCount();
        final List<String> titleLore = new ArrayList<>();
        if (theme.title() != null) {
            titleLore.add("<gray>全部挑戰完成：限定稱號 <" + theme.title().color() + ">【" + ItemRenderer.escape(theme.title().name()) + "】</" + theme.title().color() + ">");
            titleLore.add("<gray>只有這個月拿得到，拿到就是永久的（選單的「稱號」換著戴）");
            if (!theme.title().stats().isEmpty()) {
                titleLore.add("<#7fe07f>稱號的永久加成：<white>" + ItemRenderer.escape(String.join("、",
                        tw.exilecore.ascendancy.StatLines.describeAll(theme.title().stats()))) + " <dark_gray>（擁有就有，不用戴）");
            }
        }
        if (theme.bonusMerits() > 0) {
            titleLore.add("<gray>另外功勳 <#f5b942>+" + theme.bonusMerits());
        }
        titleLore.add("");
        titleLore.add("<gray>進度 <white>" + done + "/" + theme.challenges().size()
                + (progress != null && progress.titleGiven() ? "　<green>✔ 已完成" : ""));
        final ItemStack title = icon(Material.NAME_TAG, "<#f5b942>全部完成的獎勵", titleLore);
        set(SLOT_TITLE, progress != null && progress.titleGiven() ? highlight(title) : title);
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack challengeIcon(final EventConfig.Challenge challenge, final EventProgress progress, final int minLevel) {
        final int count = progress == null ? 0 : progress.count(challenge.id());
        final boolean done = progress != null && progress.done(challenge.id());
        final List<String> lore = new ArrayList<>();
        lore.add(bar(count, challenge.count()) + " <white>" + count + "<gray>/" + challenge.count());
        lore.add("<gray>完成給功勳 <#f5b942>+" + challenge.merits());
        lore.add("<dark_gray>" + hint(challenge.kind()));
        lore.add("<dark_gray>怪物（地圖）要 " + EventText.atLeast(minLevel) + "才算");
        if (progress == null) {
            lore.add("<red>帳號資料還沒載入好，進度先顯示 0（稍等幾秒再開一次）");
        }
        final Material material = switch (challenge.kind()) {
            case KILL -> Material.IRON_SWORD;
            case RARE -> Material.GOLDEN_SWORD;
            case BOSS -> Material.DRAGON_HEAD;
            case MAPS -> Material.FILLED_MAP;
            case EVENTS -> Material.CHEST;
            case THEMED -> Material.BLAZE_POWDER;
        };
        final ItemStack icon = icon(material, (done ? "<green>✔ " : "<white>") + ItemRenderer.escape(challenge.rendered()), lore);
        return done ? glowing(icon) : icon;
    }

    private static String hint(final EventConfig.Kind kind) {
        return switch (kind) {
            case KILL -> "任何地方的怪物都算";
            case RARE -> "稀有以上的怪物";
            case BOSS -> "區域 Boss、地圖 Boss、回響、試煉都算";
            case MAPS -> "地圖 Boss 倒下時在地圖裡";
            case EVENTS -> "開寶箱、碰神龕、打完裂隙、殺流寇、做完祭壇、幻霧結束";
            case THEMED -> "地圖裡帶著本月詞綴的稀有怪";
        };
    }

    /** 進度條：10 格。 */
    private static String bar(final int count, final int total) {
        final int filled = total <= 0 ? 10 : (int) Math.round(Math.min(1.0, (double) count / total) * 10);
        return "<green>" + "■".repeat(filled) + "<dark_gray>" + "■".repeat(10 - filled);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
        }
    }
}
