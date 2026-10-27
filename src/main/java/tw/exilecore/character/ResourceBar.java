package tw.exilecore.character;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 戰鬥資源血條（2026-10）：畫面上方一欄（跟 Boss 血條同一個位置），讓動作列不要擠成一長串。
 * <ul>
 *   <li>進度條本身是<b>怒氣</b>（分成 10 格；滿了變紅、消退中變白）</li>
 *   <li>條上的文字：怒氣多少、連擊、戰星（銳星、怒星、磐星）、元素灌注、獸性／狂暴、弩的熱度（2026-10-28）</li>
 *   <li>這些都沒有的時候整欄藏起來，平常不佔位置</li>
 * </ul>
 * 生命、護盾、魔力、等級、身上的異常、臨時提示留在動作列。
 */
public final class ResourceBar {

    private final ExileCore plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    ResourceBar(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 照現在的資源更新（{@link PlayerStateService#display} 每次都呼叫）。 */
    void update(final Player player, final PlayerState state) {
        if (!state.hasCharacter() || player.isDead()) {
            hide(player);
            return;
        }
        final List<String> parts = new ArrayList<>();
        int rage = 0;
        int rageMax = 1;
        boolean decaying = false;
        if (plugin.skills() != null) {
            final tw.exilecore.skill.SkillResources resources = plugin.skills().resources();
            rage = resources.rage(player);
            rageMax = Math.max(1, resources.rageMax(plugin.players().sheet(player)));
            decaying = rage > 0 && resources.rageDecaying(player);
            if (rage > 0) {
                parts.add(String.format(Locale.ROOT, "<#ff6a3d>怒氣 <white>%d<gray>/%d%s", rage, rageMax,
                        rage >= rageMax ? " <#ff3b1f>MAX" : decaying ? " <gray>消退中" : ""));
            }
            parts.addAll(resources.otherParts(player));
            final String heat = plugin.skills().originals().heatHud(player); // 2026-10-28：弩的熱度（連射）
            if (!heat.isEmpty()) {
                parts.add(heat);
            }
        }
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            final String charges = plugin.combat().charges().hudText(player);
            if (!charges.isEmpty()) {
                parts.add(charges);
            }
        }
        if (plugin.combat() != null && plugin.combat().trinity() != null) {
            // 2026-10-22：三相（鼎元訣等）的火、冰、電共鳴
            final String trinity = plugin.combat().trinity().hudText(player.getUniqueId(), plugin.getServer().getCurrentTick());
            if (!trinity.isEmpty()) {
                parts.add(trinity);
            }
        }
        if (parts.isEmpty()) {
            hide(player);
            return;
        }
        final net.kyori.adventure.text.Component title = plugin.text().parse(String.join("  <dark_gray>│ ", parts));
        final float progress = rage <= 0 ? 0f : (float) Math.max(0, Math.min(1, (double) rage / rageMax));
        final BossBar.Color color = rage <= 0 ? BossBar.Color.PURPLE : decaying ? BossBar.Color.WHITE
                : rage >= rageMax ? BossBar.Color.RED : BossBar.Color.PINK;
        final BossBar existing = bars.get(player.getUniqueId());
        if (existing == null) {
            final BossBar bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.NOTCHED_10);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
            return;
        }
        existing.name(title);
        existing.progress(progress);
        existing.color(color);
    }

    /** 藏起來（沒有資源、死掉、下線）。 */
    void hide(final Player player) {
        final BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 2026-10-18：插件停用時把每個人的血條收掉（熱重載後不會殘留一條沒人更新的）。 */
    void hideAll() {
        for (final Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        bars.clear();
    }
}
