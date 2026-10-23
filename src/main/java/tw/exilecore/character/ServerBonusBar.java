package tw.exilecore.character;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 全服加成的公告（2026-10）：經驗加倍、掉落數量、稀有度、金幣倍數有開的時候，畫面最上面一直掛一條金色的血條寫著現在的加成；
 * 都關掉就收起來。管理網頁改了、/ec reload 之後幾秒內跟上。
 */
public final class ServerBonusBar {

    private final ExileCore plugin;
    /** 在線人數加成（2026-10；寫在血條上：幾個人、下一階還差幾個）。 */
    private OnlineBonus online;
    private final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    /** 2026-10-18：記玩家物件本身（2 秒內重登是新的 Player，只記 UUID 會以為已經掛上而看不到血條）。 */
    private final java.util.Map<UUID, Player> shown = new java.util.HashMap<>();
    private String lastText;

    ServerBonusBar(final ExileCore plugin) {
        this.plugin = plugin;
    }

    void online(final OnlineBonus online) {
        this.online = online;
    }

    /** 現在的全服加成（沒有回傳 null）。 */
    String text() {
        final List<String> parts = new ArrayList<>();
        final String xp = plugin.characters() == null ? null : plugin.characters().config().experienceBonusText();
        if (xp != null) {
            parts.add("經驗 " + xp);
        }
        if (plugin.monsterModule() != null && plugin.monsterModule().drops() != null) {
            final var drops = plugin.monsterModule().drops();
            if (drops.serverQuantity() > 1e-9) {
                parts.add("掉落數量 +" + Math.round(drops.serverQuantity() * 100) + "%");
            }
            if (drops.serverRarity() > 1e-9) {
                parts.add("掉落稀有度 +" + Math.round(drops.serverRarity() * 100) + "%");
            }
            final String gold = CharacterConfig.bonusText(drops.serverGold());
            if (gold != null) {
                parts.add("金幣 " + gold);
            }
        }
        // 2026-10-23：萬燈夜全服點燈點滿的物品數量加成（掉落本來就吃得到，只是血條上沒寫）
        final var festival = plugin.festival();
        final long festivalMinutes = festival == null ? 0 : festival.bonusMinutesLeft();
        final String festivalText = festivalMinutes <= 0 || festival.quantityBonus() <= 1e-9 ? ""
                : " <#ff9a3c>（🎃 萬燈齊亮：物品數量 +" + Math.round(festival.quantityBonus() * 100) + "%，剩 " + festivalMinutes + " 分）";
        if (parts.isEmpty()) {
            if (festivalText.isEmpty()) {
                return null;
            }
            return festivalText.strip();
        }
        // 2026-10：贊助的全服加倍——剩多久、誰贊助的
        final List<String> sponsored = new ArrayList<>();
        final long now = System.currentTimeMillis();
        for (final tw.exilecore.sponsor.Boosts.Active active : tw.exilecore.sponsor.Boosts.active().values()) {
            final long left = Math.max(0, (active.until() - now) / 1000);
            sponsored.add(active.label() + String.format(java.util.Locale.ROOT, " %d:%02d", left / 60, left % 60));
        }
        final String crowd = online == null || online.current() == null ? ""
                : " <#9fd3ff>（在線 " + online.counted() + " 人加成：" + online.currentText() + "）"; // 2026-10-18：只寫現在給的，不寫下一階
        return String.join("<gray>・<white>", parts) + (sponsored.isEmpty() ? ""
                : " <#ffd27f>（贊助加倍 " + String.join("、", sponsored) + "）") + festivalText + crowd;
    }

    /** 每 2 秒：文字跟上、新上線的人掛上、加成關掉就收起來。 */
    void tick() {
        final String text = text();
        if (text == null) {
            clear();
            return;
        }
        if (!text.equals(lastText)) {
            lastText = text;
            bar.name(plugin.text().parse("<#ffd27f>✦ 全服加成中 <white>" + text + " <#ffd27f>✦"));
        }
        final Set<UUID> online = new HashSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (shown.put(player.getUniqueId(), player) != player) {
                player.showBossBar(bar);
            }
        }
        shown.keySet().retainAll(online);
    }

    void clear() {
        if (shown.isEmpty()) {
            lastText = null;
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
        shown.clear();
        lastText = null;
    }

}
