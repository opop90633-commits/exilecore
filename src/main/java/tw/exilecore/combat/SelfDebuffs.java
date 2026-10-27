package tw.exilecore.combat;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-27：玩家身上的減益（換界石的詛咒等）裡「不是擊中時才用」的數值，放進玩家的屬性表。
 * <p>
 * 減益帶的修飾器（{@link Debuffs.Debuff#mods}）原本只在擊中時疊上（CombatListener：抗性、造成的傷害、護甲這些擊中才用得到）；
 * 遲滯詛咒的「技能冷卻時間增加 35%」放技能時才用得到，擊中時疊上去沒有任何作用——這次之前遲滯詛咒對玩家的冷卻完全沒效果。
 * 現在 {@link #SHEET_STATS} 裡的屬性改成屬性表的來源（看場合的來源：網頁快照不記），身上的這些減益一變就重算屬性表。
 * 其他的照舊在擊中時疊（角色面板另外把它們疊上來顯示，見 BuffImpact）。
 */
public final class SelfDebuffs {

    /** 放進屬性表的減益屬性（放技能時才看的；擊中時疊上去沒用）。 */
    public static final Set<String> SHEET_STATS = Set.of(StatKeys.SKILL_COOLDOWN);
    /** 幾 tick 看一次有沒有變（詛咒放上、到期、被淨化）。 */
    public static final long PERIOD = 10;

    private final ExileCore plugin;
    private final Debuffs debuffs;
    /** 玩家 → 上次放進屬性表的減益（有變才重算）。 */
    private final Map<UUID, List<Modifier>> last = new HashMap<>();

    public SelfDebuffs(final ExileCore plugin, final Debuffs debuffs) {
        this.plugin = plugin;
        this.debuffs = debuffs;
    }

    /** 減益修飾器裡要放進屬性表的那些（純函式，測試用）。 */
    public static List<Modifier> sheetPart(final List<Modifier> mods) {
        List<Modifier> out = null;
        for (final Modifier mod : mods) {
            if (SHEET_STATS.contains(mod.stat())) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(mod);
            }
        }
        return out == null ? List.of() : out;
    }

    /** 減益修飾器裡擊中時才疊的那些（屬性表裡已經有的不重複；純函式，測試用）。 */
    public static List<Modifier> hitPart(final List<Modifier> mods) {
        List<Modifier> out = null;
        for (final Modifier mod : mods) {
            if (!SHEET_STATS.contains(mod.stat())) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(mod);
            }
        }
        return out == null ? List.of() : out;
    }

    /** 屬性表的來源：身上減益的冷卻時間這類（只對施加者有效的減益不算）。 */
    public List<Modifier> modifiers(final Player player) {
        return sheetPart(debuffs.modifiers(player.getUniqueId(), null, plugin.getServer().getCurrentTick()));
    }

    /** 定期：身上這些減益變了（放上、到期、被淨化）就重算屬性表。 */
    public void tick() {
        final long now = plugin.getServer().getCurrentTick();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final List<Modifier> current = sheetPart(debuffs.modifiers(player.getUniqueId(), null, now));
            final List<Modifier> before = last.getOrDefault(player.getUniqueId(), List.of());
            if (!current.equals(before)) {
                if (current.isEmpty()) {
                    last.remove(player.getUniqueId());
                } else {
                    last.put(player.getUniqueId(), current);
                }
                plugin.players().markDirty(player);
            }
        }
        last.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }
}
