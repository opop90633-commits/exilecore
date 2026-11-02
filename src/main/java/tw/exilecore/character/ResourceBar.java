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

    /**
     * 2026-11-02 照站長（模組 1.4）：這條血條的資料來源（ResourceBar 與星骸輔助模組共用）。
     * others = 連擊、灌注、湧動、獸性、主要寶石的能量、熱度、三相（MiniMessage，照血條上的順序；不含怒氣、充能）；
     * charges = 充能那一段（血條上的文字，空的 = 沒有）。這次之前全部寫在 {@link #update} 裡面。
     */
    public record Sources(int rage, int rageMax, boolean decaying, List<String> others, String charges, String trinity) {

        /** 血條上的幾段（跟這次之前一樣的順序：怒氣、其他、充能、三相）。 */
        List<String> barParts() {
            final List<String> parts = new ArrayList<>();
            if (rage > 0) {
                parts.add(String.format(Locale.ROOT, "<#ff6a3d>怒氣 <white>%d<gray>/%d%s", rage, rageMax,
                        rage >= rageMax ? " <#ff3b1f>MAX" : decaying ? " <gray>消退中" : ""));
            }
            parts.addAll(others);
            if (!charges.isEmpty()) {
                parts.add(charges);
            }
            if (!trinity.isEmpty()) {
                parts.add(trinity);
            }
            return parts;
        }

        /** 模組畫的「其他」那段（不含怒氣、充能；三相接在最後）。 */
        public List<String> moddedOthers() {
            final List<String> parts = new ArrayList<>(others);
            if (!trinity.isEmpty()) {
                parts.add(trinity);
            }
            return parts;
        }
    }

    /** 2026-11-02：讀這個人現在的戰鬥資源（{@link Sources}）。 */
    public static Sources read(final ExileCore plugin, final Player player) {
        int rage = 0;
        int rageMax = 1;
        boolean decaying = false;
        final List<String> others = new ArrayList<>();
        if (plugin.skills() != null) {
            final tw.exilecore.skill.SkillResources resources = plugin.skills().resources();
            rage = resources.rage(player);
            rageMax = Math.max(1, resources.rageMax(plugin.players().sheet(player)));
            decaying = rage > 0 && resources.rageDecaying(player);
            others.addAll(resources.otherParts(player));
            final String metaEnergy = plugin.skills().metaEnergyHud(player); // 2026-10-28 照站長：主要寶石（擊中時火焰術、元祈…）的能量
            if (!metaEnergy.isEmpty()) {
                others.add(metaEnergy);
            }
            final String heat = plugin.skills().originals().heatHud(player); // 2026-10-28：弩的熱度（連射）
            if (!heat.isEmpty()) {
                others.add(heat);
            }
        }
        String charges = "";
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            charges = plugin.combat().charges().hudText(player);
        }
        String trinity = "";
        if (plugin.combat() != null && plugin.combat().trinity() != null) {
            // 2026-10-22：三相（鼎元訣等）的火、冰、電共鳴
            trinity = plugin.combat().trinity().hudText(player.getUniqueId(), plugin.getServer().getCurrentTick());
        }
        return new Sources(rage, rageMax, decaying, others, charges, trinity);
    }

    /** 照現在的資源更新（{@link PlayerStateService#display} 每次都呼叫）。 */
    void update(final Player player, final PlayerState state) {
        if (!state.hasCharacter() || player.isDead()) {
            hide(player);
            return;
        }
        // 2026-11-02 照站長（模組 1.4）：星骸輔助模組自己在魔力球旁邊畫戰鬥資源的人（招呼寫了 res）不掛這條血條；這次之前大家都掛
        if (plugin.clientMod() != null && plugin.clientMod().drawsResources(player)) {
            hide(player);
            return;
        }
        final Sources sources = read(plugin, player);
        final int rage = sources.rage();
        final int rageMax = sources.rageMax();
        final boolean decaying = sources.decaying();
        final List<String> parts = sources.barParts();
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
