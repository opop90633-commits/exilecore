package tw.exilecore.quest;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.MainMenu;
import tw.exilecore.gui.Menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任務日誌（/quests、選單的「任務」）：主線一排、支線兩排，每個任務一格（完成、進行中、可以接、還不能接）。
 * 左鍵進行中的任務＝追蹤（任務追蹤排第一），右鍵＝重看說明與每一步；底下是已經拿到的永久加成。
 */
public final class QuestMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_MAIN_LABEL = 9;
    private static final int[] MAIN_SLOTS = {10, 11, 12, 13, 14, 15, 16, 17};
    private static final int SLOT_SIDE_LABEL = 18;
    private static final int[] SIDE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34, 35};
    private static final int SLOT_BACK = 45;
    private static final int SLOT_BOUNTY = 47;
    private static final int SLOT_REWARDS = 49;
    private static final int SLOT_CLOSE = 53;

    private final QuestService service;
    private final Map<Integer, QuestConfig.Quest> shown = new HashMap<>();

    public QuestMenu(final ExileCore plugin, final Player player, final QuestService service) {
        super(plugin, player, 6, "<dark_gray>任務日誌");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        if (!service.ready(player)) {
            set(22, icon(Material.BARRIER, "<red>先建立角色（/char）"));
            set(SLOT_CLOSE, closeButton());
            return;
        }
        final QuestConfig config = service.config();
        final QuestLog log = service.log(player);
        final int[] main = QuestService.counts(config, log, QuestConfig.Kind.MAIN);
        final int[] side = QuestService.counts(config, log, QuestConfig.Kind.SIDE);
        final List<QuestConfig.Quest> tracked = log.tracked(1);
        set(SLOT_HEADER, glowing(icon(Material.WRITABLE_BOOK, "<#f5b942>任務日誌",
                "<gray>主線 <white>" + main[0] + "/" + main[1] + "<gray>・支線 <white>" + side[0] + "/" + side[1],
                tracked.isEmpty() ? "<dark_gray>沒有進行中的任務" : "<gray>追蹤中：" + tracked.getFirst().kind().color()
                        + QuestText.escape(tracked.getFirst().name()),
                "",
                "<dark_gray>左鍵進行中的任務：追蹤（任務追蹤排第一）",
                "<dark_gray>右鍵任務：重看說明與每一步")));
        set(SLOT_MAIN_LABEL, icon(Material.GOLD_INGOT, "<#f5b942>主線", "<gray>營地主事沈渡的委託，一章一個區域"));
        set(SLOT_SIDE_LABEL, icon(Material.PRISMARINE_CRYSTALS, "<#7fd4ff>支線", "<gray>各區域入口的人給的委託"));
        place(log, config.quests(QuestConfig.Kind.MAIN), MAIN_SLOTS);
        place(log, config.quests(QuestConfig.Kind.SIDE), SIDE_SLOTS);

        final List<String> summary = QuestRewards.summary(config, service.progress(player));
        final List<String> lore = new ArrayList<>();
        if (summary.isEmpty()) {
            lore.add("<dark_gray>還沒有");
        } else {
            for (final String line : summary) {
                lore.add("<white>" + QuestText.escape(line));
            }
        }
        lore.add("");
        lore.add("<dark_gray>任務的獎勵都是永久的，換裝、退點都不會不見");
        set(SLOT_REWARDS, icon(Material.NETHER_STAR, "<#f5b942>任務給的永久加成", lore));
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        if (plugin.bounties() != null) {
            set(SLOT_BOUNTY, icon(Material.PAPER, "<#9fd3ff>委託板", "<gray>每日委託、每週回響、功勳兌換", "", "<yellow>左鍵：打開<dark_gray>（/bounty）"));
        }
        set(SLOT_CLOSE, closeButton());
    }

    private void place(final QuestLog log, final List<QuestConfig.Quest> quests, final int[] slots) {
        final String pinned = log.progress().get(QuestLog.TRACK);
        for (int i = 0; i < quests.size() && i < slots.length; i++) {
            final QuestConfig.Quest quest = quests.get(i);
            shown.put(slots[i], quest);
            set(slots[i], icon(log, quest, quest.id().equals(pinned)));
        }
    }

    private ItemStack icon(final QuestLog log, final QuestConfig.Quest quest, final boolean pinned) {
        final QuestLog.State state = log.state(quest);
        final QuestLog.Phase phase = log.phase(quest);
        final String name = QuestText.escape(quest.name());
        final List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + quest.kind().displayName() + "・" + zoneName(quest.zone()));
        if (!quest.summary().isBlank() && phase != QuestLog.Phase.LOCKED) {
            lore.add("<gray>" + QuestText.escape(quest.summary()));
        }
        final List<String> rewards = QuestRewards.describe(quest, state.choice());
        switch (phase) {
            case DONE -> {
                lore.add("");
                if (!rewards.isEmpty()) {
                    lore.add("<gray>拿到了：<white>" + QuestText.escape(String.join("、", rewards)));
                }
                lore.add("<dark_gray>右鍵：重看");
                return icon(Material.WRITTEN_BOOK, "<green>✔ " + name, lore);
            }
            case ACTIVE -> {
                final String progress = QuestText.progress(log, quest);
                lore.add("");
                lore.add("<yellow>現在：<white>" + QuestText.escape(quest.stage(state.stage()).text())
                        + (progress.isEmpty() ? "" : " <white>" + progress));
                if (!rewards.isEmpty()) {
                    lore.add("<gray>獎勵：<white>" + QuestText.escape(String.join("、", rewards)));
                }
                lore.add("");
                lore.add(pinned ? "<#f5b942>追蹤中" : "<yellow>左鍵：追蹤");
                lore.add("<yellow>右鍵：重看說明與每一步");
                final ItemStack stack = icon(Material.WRITABLE_BOOK, "<yellow>▶ " + quest.kind().color() + name, lore);
                return pinned ? glowing(stack) : stack;
            }
            case AVAILABLE -> {
                lore.add("");
                lore.add("<white>可以接了：去找" + QuestText.escape(service.speakerName(quest.giver())));
                if (!rewards.isEmpty()) {
                    lore.add("<gray>獎勵：<white>" + QuestText.escape(String.join("、", rewards)));
                }
                return icon(Material.PAPER, "<white>! " + name, lore);
            }
            default -> {
                final String reason = log.lockReason(quest);
                lore.add("<dark_gray>" + QuestText.escape(reason == null ? "還不能接" : reason));
                return icon(Material.GRAY_DYE, "<dark_gray>" + name, lore);
            }
        }
    }

    private String zoneName(final String zone) {
        if (QuestConfig.CAMP.equals(zone)) {
            return "營地";
        }
        if (plugin.zones() != null) {
            return plugin.zones().zone(zone).map(tw.exilecore.zone.Zone::name).orElse(zone);
        }
        return zone;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot == SLOT_BOUNTY && plugin.bounties() != null) {
            plugin.bounties().open(player);
            return;
        }
        final QuestConfig.Quest quest = shown.get(slot);
        if (quest == null) {
            return;
        }
        if (click.isRightClick()) {
            service.dialogs().recap(player, quest);
            return;
        }
        if (service.track(player, quest)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
            redraw();
        }
    }
}
