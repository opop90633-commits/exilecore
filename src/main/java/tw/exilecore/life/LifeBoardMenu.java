package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 2026-10-23：釣魚大賽（本週排名）與木筏人氣榜（本週的讚）。 */
final class LifeBoardMenu extends Menu {

    private final LifeService service;

    LifeBoardMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 4, "<dark_gray>釣魚大賽・人氣榜");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 36; i++) {
            set(i, filler());
        }
        final LifeConfig config = service.config();
        final LifeExtras extras = service.extras();
        // 釣魚大賽
        final List<String> about = new ArrayList<>();
        about.add("<gray>本週（" + extras.tournamentWeek() + "）：釣到的魚佔牠最大長度的比例最高的贏");
        about.add("<gray>週一半夜結算，上線時領獎；第一名多拿稱號「釣王」（下一週有人贏就換人）");
        about.add("<gray>平手：比例一樣比公分數，再一樣就先釣到的贏");
        final List<LifeConfig.TournamentReward> rewards = config.tournamentRewards();
        for (int i = 0; i < rewards.size(); i++) {
            final StringBuilder text = new StringBuilder("生活經驗 +" + rewards.get(i).xp());
            for (final Map.Entry<String, Integer> entry : rewards.get(i).materials().entrySet()) {
                text.append("、").append(service.materialName(entry.getKey())).append(" ×").append(entry.getValue());
            }
            about.add("<#ffd27f>第 " + (i + 1) + " 名：<gray>" + ItemRenderer.escape(text.toString()));
        }
        if (!extras.championName().isEmpty()) {
            about.add("<gray>上一週的釣王：<white>" + ItemRenderer.escape(extras.championName()));
        }
        set(0, rowLabel(Material.FISHING_ROD, "<#ffd27f>釣魚大賽", about.toArray(new String[0])));
        final List<LifeExtras.Entry> ranked = extras.ranking();
        for (int i = 0; i < Math.min(8, ranked.size()); i++) {
            final LifeExtras.Entry entry = ranked.get(i);
            final boolean me = entry.id().equals(player.getUniqueId());
            final LifeConfig.Material material = config.materials().get(entry.fish());
            set(1 + i, icon(material == null ? Material.COD : LifeService.material(material.icon(), Material.COD),
                    (me ? "<green>" : "<white>") + "第 " + (i + 1) + " 名：" + ItemRenderer.escape(entry.name()),
                    "<gray>" + ItemRenderer.escape(service.materialName(entry.fish())) + " <white>" + String.format(Locale.ROOT, "%.1f", entry.sizeTenths() / 10.0)
                            + " 公分<gray>（" + entry.ratio() / 100 + "% 的最大長度）"));
        }
        final LifeExtras.Entry mine = extras.myEntry(player.getUniqueId());
        if (mine != null) {
            int rank = 0;
            for (int i = 0; i < ranked.size(); i++) {
                if (ranked.get(i).id().equals(player.getUniqueId())) {
                    rank = i + 1;
                }
            }
            set(9, icon(Material.NAME_TAG, "<green>你：第 " + rank + " 名", "<gray>" + ItemRenderer.escape(service.materialName(mine.fish())) + " "
                    + String.format(Locale.ROOT, "%.1f", mine.sizeTenths() / 10.0) + " 公分（" + mine.ratio() / 100 + "%）"));
        } else {
            set(9, icon(Material.NAME_TAG, "<gray>你這週還沒有紀錄", "<gray>去釣一條魚吧"));
        }
        // 人氣榜
        set(18, rowLabel(Material.RED_DYE, "<#ff9be0>木筏人氣榜（本週）", "拜訪別人的筏時 /sea like 按讚：每天 " + config.likesPerDay() + " 個、同一塊筏一天一次",
                "/sea visit 名字 去隊友、共用、邀請你的人的筏"));
        final List<int[]> likes = extras.likeRanking();
        for (int i = 0; i < Math.min(8, likes.size()); i++) {
            final String owner = service.ownerName(likes.get(i)[0]);
            set(19 + i, icon(Material.PLAYER_HEAD, "<white>第 " + (i + 1) + " 名：" + ItemRenderer.escape(owner), "<#ff9be0>❤ " + likes.get(i)[1]));
        }
        final LifeService.Profile profile = service.profile(player);
        if (profile != null && profile.plot >= 0) {
            set(27, icon(Material.NAME_TAG, "<#ff9be0>你的木筏：❤ " + extras.likesOf(profile.plot), "<gray>邀朋友來看看（/sea invite 名字）"));
        }
        set(35, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == 35) {
            goBack();
        }
    }
}
