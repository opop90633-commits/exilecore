package tw.exilecore.skill;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26：照 PoE2 只給自己的常駐增益（精魂技能），效果寫在程式裡：
 * <ul>
 *   <li><b>護時</b>（Time of Need）：每隔 10→8.1 秒（逐級；冷卻回復加快）一次祝福：回復固定生命（17→628→2763），解除身上的元素異常。</li>
 *   <li><b>幻步</b>（Ghost Dance）：每隔 12→10.1 秒（逐級；冷卻回復加快）獲得一層幽魂護罩（最多 3 層）；被擊中時失去一層，
 *       最近（4 秒內）失去過的話每秒再生閃避值 2% 的能量護盾。</li>
 *   <li><b>木甲</b>（Barkskin）：被敵人擊中、能量護盾被打掉時，獲得失去的能量護盾 43%→62%（逐級）的護甲 6 秒（可以疊，
 *       總共不超過身上原本的護甲）。</li>
 *   <li><b>不熄怒</b>（Eternal Rage）：每秒回復 0.25→5→10 點怒氣（屬性 {@link SkillResources#RAGE_REGEN}，寫在光環的 effects）。</li>
 * </ul>
 * 逐級的間隔寫在 skills.yml 那幾個技能的 level_modifiers（blessing.interval、shroud.interval）；
 * 幅度寫在光環的 effects（blessing.life、shroud.es_regen、barkskin.armour，吃光環效果）。
 */
final class PersistentBuffs implements Listener {

    static final String TIME_OF_NEED = "time_of_need";
    static final String GHOST_DANCE = "ghost_dance";
    static final String BARKSKIN = "barkskin";

    static final String BLESSING_INTERVAL = "blessing.interval";
    static final String BLESSING_LIFE = "blessing.life";
    static final String SHROUD_INTERVAL = "shroud.interval";
    static final String SHROUD_ES_REGEN = "shroud.es_regen";
    static final String BARKSKIN_ARMOUR = "barkskin.armour";

    /** 幽魂護罩最多幾層。 */
    static final int SHROUD_MAX = 3;
    /** 「最近」＝4 秒。 */
    static final long RECENTLY = 80;
    /** 木甲的護甲持續 6 秒。 */
    static final long BARKSKIN_TICKS = 120;
    /** 每幾 tick 跑一次（SkillService 每 10 tick 呼叫）。 */
    static final long PERIOD = 10;

    private record Stack(double armour, long until) {
    }

    private static final class State {
        long nextBlessing = -1;
        long nextShroud = -1;
        int shrouds;
        long shroudLost = Long.MIN_VALUE / 2;
        double lastEs = -1;
        /** 2026-10-27：上次結算到現在被敵人擊中打掉的能量護盾（木甲照這個算；這次之前 10 tick 內被打過，任何能量護盾減少都算）。 */
        double hitEs;
        final List<Stack> bark = new ArrayList<>();
        double barkCap;
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, State> states = new HashMap<>();

    PersistentBuffs(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個常駐技能開著嗎；開著的話它的等級（沒開回傳 0）。 */
    private int level(final Player player, final String skillId) {
        if (!service.auras().isActive(player.getUniqueId(), skillId)) {
            return 0;
        }
        final tw.exilecore.character.SkillLoadout loadout = plugin.players().get(player).skills();
        final int position = loadout.find(skillId);
        return position < 0 ? 0 : Math.max(1, loadout.skill(position).level());
    }

    private double levelValue(final String skillId, final String stat, final int level, final double fallback) {
        for (final ScaledMod mod : service.config().levelModifiers(skillId)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return fallback;
    }

    /** 冷卻回復速度（祝福、護罩的間隔照這個縮短）。 */
    private static double recovery(final StatSheet sheet) {
        return Math.max(0.1, sheet.apply(StatKeys.COOLDOWN_RECOVERY, 1.0, Set.of()));
    }

    // ---- 每 10 tick ----

    void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                service.resources().regenerate(player, PERIOD); // 不熄怒
                final int need = level(player, TIME_OF_NEED);
                final int ghost = level(player, GHOST_DANCE);
                final int bark = level(player, BARKSKIN);
                if (need <= 0 && ghost <= 0 && bark <= 0) {
                    final State old = states.remove(player.getUniqueId());
                    if (old != null && !old.bark.isEmpty()) {
                        plugin.players().markDirty(player);
                    }
                    continue;
                }
                final State s = states.computeIfAbsent(player.getUniqueId(), k -> new State());
                final PlayerState state = plugin.players().get(player);
                final StatSheet sheet = plugin.players().sheet(player);
                blessing(player, s, state, sheet, need, now);
                shrouds(player, s, state, sheet, ghost, now);
                barkskin(player, s, state, sheet, bark, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{} 的常駐增益（護時、幻步、木甲、不熄怒）出錯", player.getName(), e);
            }
        }
    }

    private void blessing(final Player player, final State s, final PlayerState state, final StatSheet sheet, final int level, final long now) {
        if (level <= 0) {
            s.nextBlessing = -1;
            return;
        }
        final long every = Math.max(20, Math.round(levelValue(TIME_OF_NEED, BLESSING_INTERVAL, level, 10) * 20 / recovery(sheet)));
        if (s.nextBlessing < 0) {
            s.nextBlessing = now + every; // 剛開：等一個間隔
            return;
        }
        if (now < s.nextBlessing || state.isDead()) {
            return;
        }
        s.nextBlessing = now + every;
        final double heal = sheet.flat(BLESSING_LIFE, Set.of());
        if (heal > 0) {
            state.setLife(state.life() + heal);
        }
        if (plugin.ailments() != null) {
            for (final AilmentType type : new AilmentType[] {AilmentType.IGNITE, AilmentType.CHILL, AilmentType.FREEZE,
                    AilmentType.SHOCK, AilmentType.ELECTROCUTE}) {
                plugin.ailments().cure(player, type);
            }
        }
        Fx.spawn(player.getWorld(), Particle.END_ROD, player.getLocation().add(0, 1.2, 0), 12, 0.4, 0.6, 0.4, 0.02);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.4f);
    }

    private void shrouds(final Player player, final State s, final PlayerState state, final StatSheet sheet, final int level, final long now) {
        if (level <= 0) {
            s.nextShroud = -1;
            s.shrouds = 0;
            return;
        }
        final long every = Math.max(20, Math.round(levelValue(GHOST_DANCE, SHROUD_INTERVAL, level, 12) * 20 / recovery(sheet)));
        if (s.nextShroud < 0 || s.shrouds >= SHROUD_MAX) {
            s.nextShroud = now + every;
        } else if (now >= s.nextShroud) {
            s.shrouds++;
            s.nextShroud = now + every;
        }
        if (now - s.shroudLost <= RECENTLY && !state.isDead()) {
            final double percent = sheet.flat(SHROUD_ES_REGEN, Set.of());
            final double regen = Math.max(0, sheet.get(StatKeys.EVASION)) * percent / 100.0 * PERIOD / 20.0;
            if (regen > 0 && state.energyShield() < state.maxEnergyShield()) {
                state.setEnergyShield(state.energyShield() + regen);
            }
        }
    }

    private void barkskin(final Player player, final State s, final PlayerState state, final StatSheet sheet, final int level, final long now) {
        boolean changed = s.bark.removeIf(stack -> stack.until() <= now);
        final double es = state.energyShield();
        final double lost = barkskinLoss(s.hitEs, s.lastEs);
        s.hitEs = 0;
        if (level > 0 && lost > 0) {
            final double percent = sheet.flat(BARKSKIN_ARMOUR, Set.of());
            final double gain = lost * percent / 100.0;
            if (gain > 0) {
                // 上限：身上原本的護甲（扣掉木甲自己給的）
                s.barkCap = Math.max(0, sheet.get(StatKeys.ARMOUR) - bonus(s));
                s.bark.add(new Stack(gain, now + BARKSKIN_TICKS));
                changed = true;
            }
        }
        if (level <= 0 && !s.bark.isEmpty()) {
            s.bark.clear();
            changed = true;
        }
        s.lastEs = es;
        if (changed) {
            plugin.players().markDirty(player);
        }
    }

    /**
     * 2026-10-27：木甲算的「被擊中失去的能量護盾」：擊中打掉的能量護盾（hitEs），最多到上次結算時身上有的（lastEs；不知道時照擊中的）。
     * 這次之前是被打後 10 tick 內能量護盾的任何減少（花能量護盾、別的扣法也算）。
     */
    static double barkskinLoss(final double hitEs, final double lastEs) {
        if (hitEs <= 0) {
            return 0;
        }
        return lastEs >= 0 ? Math.min(hitEs, lastEs) : hitEs;
    }

    private static double bonus(final State s) {
        double total = 0;
        for (final Stack stack : s.bark) {
            total += stack.armour();
        }
        return Math.min(total, s.barkCap);
    }

    /** 木甲給的護甲（PlayerStateService 的增益來源）。 */
    List<Modifier> modifiers(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null || s.bark.isEmpty()) {
            return List.of();
        }
        final double armour = bonus(s);
        return armour <= 0 ? List.of() : List.of(Modifier.flat(StatKeys.ARMOUR, armour, "木甲"));
    }

    /** 幽魂護罩現在幾層（說明、測試用）。 */
    int shrouds(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.shrouds;
    }

    // ---- 被擊中：幻步失去一層護罩、木甲記下來 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitTaken(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || event.attacker() instanceof Player || !event.result().hit()) {
            return;
        }
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        final long now = now();
        s.hitEs += Math.max(0, event.result().esDamage()); // 2026-10-27：木甲照擊中打掉的能量護盾
        if (s.shrouds > 0) {
            s.shrouds--;
            s.shroudLost = now;
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
