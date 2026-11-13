package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-12 照站長「新增暗黑破壞神那樣的難度系統，讓玩家自己選擇難度」：地圖裝置的「難度」。
 * 列出 1～10 階（生命、傷害、物品數量、稀有度、經驗），已經解鎖的點一下就選它；下一階點一下開那一階的進階關卡
 * （災厄級的塑星者・蒼冕，照那一階的難度）。
 */
final class DifficultyMenu extends Menu {

    private static final int ROWS = 4;
    private static final int[] TIER_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    private static final int SLOT_INFO = 27;
    private static final int SLOT_BACK = 35;

    private final MapService service;

    DifficultyMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, ROWS, "<dark_gray>難度");
        this.service = service;
    }

    /** 一行說明：「難度 5：怪物生命 ×4.42、傷害 ×1.94；物品數量 +60%、稀有度 +100%、經驗 +60%」。 */
    static String describe(final Difficulty.Tier tier) {
        return "難度 " + tier.level() + "：怪物生命 " + Difficulty.times(tier.life()) + "、傷害 " + Difficulty.times(tier.damage())
                + "；物品數量 " + Difficulty.plus(tier.quantity()) + "、稀有度 " + Difficulty.plus(tier.rarity()) + "、經驗 " + Difficulty.plus(tier.experience());
    }

    @Override
    protected void draw() {
        final Difficulty.Table table = service.config().difficulty();
        final int unlocked = Difficulty.unlocked(plugin, player, table);
        final int selected = Difficulty.selected(plugin, player, table);
        for (int i = 0; i < TIER_SLOTS.length; i++) {
            final int level = i + 1;
            if (level > table.max()) {
                break;
            }
            set(TIER_SLOTS[i], tierIcon(table.tier(level), unlocked, selected, table.max()));
        }
        set(SLOT_INFO, icon(Material.BOOK, "<#ff6a3d>難度",
                "<gray>選了之後，<white>你開的</white>每一張圖都照這個難度（地圖、終局、極境、巔峰、事件、深坑…）：",
                "<gray>怪物與 Boss 的生命、傷害更多，掉落的物品數量、稀有度與經驗也更多",
                "<gray>組隊時照開圖的人選的；終局以上 Boss 的掉落也吃難度給的數量、稀有度",
                "<gray>地圖外面（劇情區域、營地、世界活動）不受影響",
                "",
                "<gray>解鎖下一階：打過那一階的<#ff6a3d>進階關卡<gray>（照那一階的難度打災厄級的塑星者）",
                "<gray>關卡裡死了不扣經驗；不掉東西、沒有經驗；通關後就不能再打",
                "",
                "<gray>現在：<white>難度 " + selected + "</white>　已解鎖到 <white>" + unlocked + "</white> / " + table.max()));
        set(SLOT_BACK, backButton("地圖裝置"));
    }

    private ItemStack tierIcon(final Difficulty.Tier tier, final int unlocked, final int selected, final int max) {
        final int level = tier.level();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>怪物生命 <white>" + Difficulty.times(tier.life()) + "</white>　傷害 <white>" + Difficulty.times(tier.damage()));
        lore.add("<gray>物品數量 <white>" + Difficulty.plus(tier.quantity()) + "</white>　稀有度 <white>" + Difficulty.plus(tier.rarity())
                + "</white>　經驗 <white>" + Difficulty.plus(tier.experience()));
        lore.add("");
        final Material material;
        final String name;
        if (level == selected) {
            material = Material.BLAZE_POWDER;
            name = "<#ff6a3d>難度 " + level + "（現在）";
            lore.add("<green>你開的圖照這個難度");
        } else if (level <= unlocked) {
            material = Material.GLOWSTONE_DUST;
            name = "<#f5b942>難度 " + level;
            lore.add("<yellow>點一下：選這個難度");
        } else if (Difficulty.canChallenge(unlocked, level, max)) {
            material = Material.NETHER_STAR;
            name = "<#ff6a3d>難度 " + level + "（還沒解鎖）";
            final MapConfig.EventBoss boss = service.config().eventBoss(Difficulty.TRIAL_KIND);
            lore.add("<gray>進階關卡：<#ff5c6a>災厄級<gray>的 " + ItemRenderer.escape(boss == null ? "塑星者" : boss.boss().name())
                    + "，照難度 " + level + "；打倒牠就解鎖");
            lore.add("<gray>裡面死了不扣經驗；不掉東西、沒有經驗");
            lore.add("<yellow>點一下：開進階關卡");
        } else {
            material = Material.GRAY_DYE;
            name = "<dark_gray>難度 " + level + "（鎖住）";
            lore.add("<dark_gray>要先解鎖難度 " + (level - 1));
        }
        final ItemStack stack = icon(material, name, lore);
        return level == selected || Difficulty.canChallenge(unlocked, level, max) ? glowing(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            goBack();
            return;
        }
        int level = 0;
        for (int i = 0; i < TIER_SLOTS.length; i++) {
            if (TIER_SLOTS[i] == slot) {
                level = i + 1;
            }
        }
        final Difficulty.Table table = service.config().difficulty();
        if (level <= 0 || level > table.max() || !acceptAction(click)) {
            return;
        }
        final int unlocked = Difficulty.unlocked(plugin, player, table);
        if (level <= unlocked) {
            Difficulty.select(plugin, player, level);
            player.sendMessage(plugin.text().prefixed("<#ff6a3d>選了" + ItemRenderer.escape(describe(table.tier(level)))));
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.0f + level * 0.05f);
            redraw();
            return;
        }
        if (!Difficulty.canChallenge(unlocked, level, table.max())) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
            return;
        }
        final String problem = service.openDifficultyTrial(player, level);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
        } else {
            player.closeInventory();
        }
    }
}
