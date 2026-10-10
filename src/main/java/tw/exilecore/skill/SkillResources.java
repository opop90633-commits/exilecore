package tw.exilecore.skill;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 怒氣（2026-10 PoE2）：近戰攻擊打中敵人時照 {@code rage.on_hit} 加（同一下打中好幾個只算一次），
 * 上限 {@value #RAGE_BASE_MAX} ＋ {@code rage.max}；每點增加 1% 攻擊傷害（{@code rage.effect} 可以加）。
 * {@value #RAGE_DECAY_DELAY} tick 沒拿到就開始每 {@value #RAGE_DECAY_TICKS} tick 掉 1 點。
 * <p>
 * 以前的連段、榮耀、元素灌注已經拿掉（2026-10）：要它們的技能直接就能放。
 */
public final class SkillResources implements Listener {

    public static final int RAGE_BASE_MAX = 30;
    public static final long RAGE_DECAY_DELAY = 40;
    public static final long RAGE_DECAY_TICKS = 5;

    private static final class State {
        double rage;
        long rageTick;
        long lastRageHit;
        int combo;
        long comboTick;
        long lastComboHit;
        long comboDecay = COMBO_DECAY;
        long finisherUntil;
        /** 元素灌注（2026-10）：火、冰、電的層數與最後一次拿到的 tick。 */
        final int[] infusion = new int[3];
        long infusionTick;
        /** 終式用掉的層數（那一下的「每層連擊」照這個算）。 */
        int finisherCombo;
        long enragedUntil;
    }

    private final ExileCore plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public SkillResources(final ExileCore plugin, final SkillConfig config) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private State state(final UUID id) {
        return states.computeIfAbsent(id, key -> new State());
    }

    private static void decay(final State s, final long now) {
        if (s.rage > 0 && now - s.rageTick > RAGE_DECAY_DELAY) {
            final long steps = (now - s.rageTick - RAGE_DECAY_DELAY) / RAGE_DECAY_TICKS;
            if (steps > 0) {
                s.rage = Math.max(0, s.rage - steps);
                s.rageTick += steps * RAGE_DECAY_TICKS;
            }
        }
    }

    /** 現在幾點怒氣。 */
    public int rage(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return 0;
        }
        decay(s, now());
        return (int) s.rage;
    }

    /** 怒氣上限。 */
    public int rageMax(final StatSheet sheet) {
        return (int) Math.max(0, RAGE_BASE_MAX + sheet.flat(StatKeys.RAGE_MAX, Set.of()));
    }

    /** 近戰攻擊打中：加怒氣。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.result().blocked()
                || event.skill() == null || !event.skill().isAttack() || !event.skill().tags().contains(Tags.MELEE)) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        gainCombo(player, event, sheet); // 2026-10：連擊
        final double gain = event.skill().effective(sheet).flat(StatKeys.RAGE_ON_HIT, event.skill().tags());
        if (gain <= 0) {
            return;
        }
        final long now = now();
        final State s = state(player.getUniqueId());
        decay(s, now);
        if (now - s.lastRageHit < 4) {
            return; // 同一下打中好幾個只算一次
        }
        s.lastRageHit = now;
        s.rage = Math.min(rageMax(sheet), s.rage + gain);
        s.rageTick = now;
    }

    // ---- 連擊（2026-10，照 PoE2）----

    static final int COMBO_MAX = 10;
    static final long COMBO_DECAY = 80;

    /** 現在幾層連擊（超過持續時間沒累積就歸零）。 */
    public int combo(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return 0;
        }
        if (s.combo > 0 && now() - s.comboTick > s.comboDecay) {
            s.combo = 0;
        }
        return s.combo;
    }

    /** 近戰打擊打中：累積連擊（同一下打中好幾個只算一次）。 */
    private void gainCombo(final Player player, final HitEvent event, final StatSheet sheet) {
        if (!event.skill().tags().contains(Tags.STRIKE)) {
            return;
        }
        final long now = now();
        final State s = state(player.getUniqueId());
        combo(player);
        if (now - s.lastComboHit < 4) {
            return;
        }
        s.lastComboHit = now;
        final StatSheet effective = event.skill().effective(sheet);
        int gain = 1;
        if (plugin.random().nextDouble() * 100 < effective.flat(StatKeys.COMBO_EXTRA_CHANCE, event.skill().tags())) {
            gain++;
        }
        s.combo = Math.min(COMBO_MAX, s.combo + gain);
        s.comboTick = now;
        s.comboDecay = Math.round(COMBO_DECAY * Math.max(0.2, 1 + effective.increased(StatKeys.COMBO_DURATION, event.skill().tags())));
    }

    /**
     * 放技能前（SkillService 呼叫）：掛了終式、有 3 層以上就用掉全部連擊；掛了怒鑄、有 10 點怒氣就用掉 10 點。
     * 用掉的這一下接下來 12 tick 內打中的都更痛（CombatListener 看 {@link #finisher}、{@link #enraged}）。
     */
    void beforeCast(final Player player, final StatSheet effective, final java.util.Set<String> tags) {
        final long now = now();
        if (effective.flat(StatKeys.COMBO_FINISHER, tags) > 0 && combo(player) >= 3) {
            final State s = state(player.getUniqueId());
            s.finisherCombo = s.combo;
            s.combo = 0;
            s.finisherUntil = now + 12;
        }
        if (effective.flat(StatKeys.RAGEFORGED, tags) > 0 && rage(player) >= 10) {
            final State s = state(player.getUniqueId());
            s.rage -= 10;
            s.enragedUntil = now + 12;
        }
    }

    /** 算「每層連擊更多傷害」用的層數：終式那一下照用掉的層數算（不然先清空就變 0 層）。 */
    public int comboForDamage(final Player player) {
        final int combo = combo(player);
        final State s = states.get(player.getUniqueId());
        return s != null && now() <= s.finisherUntil ? Math.max(combo, s.finisherCombo) : combo;
    }

    /** 終式那一下（用掉連擊之後 12 tick 內）。 */
    public boolean finisher(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s != null && now() <= s.finisherUntil;
    }

    /** 怒鑄那一下。 */
    public boolean enraged(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s != null && now() <= s.enragedUntil;
    }

    // ---- 元素灌注（2026-10）----

    static final int INFUSION_BASE_MAX = 2;
    static final long INFUSION_DURATION = 200;
    private static final String[] INFUSION_ELEMENTS = {"fire", "cold", "lightning"};
    private static final String[] INFUSION_NAMES = {"火", "冰", "電"};
    private static final String[] INFUSION_COLORS = {"<#ff7a3a>", "<#7fd8ff>", "<#ffe066>"};

    /** 身上的灌注（火、冰、電；超過持續時間沒拿到就全部散掉）。 */
    public int[] infusions(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return new int[3];
        }
        if (now() - s.infusionTick > INFUSION_DURATION) {
            java.util.Arrays.fill(s.infusion, 0);
        }
        return s.infusion.clone();
    }

    /** 放完技能：照 infusion.gain*（effective 是這個技能疊好輔助的屬性表）疊灌注。 */
    void gainInfusion(final Player player, final StatSheet effective, final Set<String> tags) {
        final int[] gain = new int[3];
        final int matching = (int) Math.round(effective.flat(StatKeys.INFUSION_GAIN, tags));
        for (int i = 0; i < 3; i++) {
            if (matching > 0 && tags.contains(INFUSION_ELEMENTS[i])) {
                gain[i] += matching;
            }
        }
        gain[0] += (int) Math.round(effective.flat(StatKeys.INFUSION_GAIN_FIRE, tags));
        gain[1] += (int) Math.round(effective.flat(StatKeys.INFUSION_GAIN_COLD, tags));
        gain[2] += (int) Math.round(effective.flat(StatKeys.INFUSION_GAIN_LIGHTNING, tags));
        if (gain[0] <= 0 && gain[1] <= 0 && gain[2] <= 0) {
            return;
        }
        infusions(player); // 先把過期的清掉
        final State s = state(player.getUniqueId());
        final int max = Math.max(1, INFUSION_BASE_MAX + (int) Math.round(effective.flat(StatKeys.INFUSION_MAX, Set.of())));
        for (int i = 0; i < 3; i++) {
            s.infusion[i] = Math.min(max, s.infusion[i] + Math.max(0, gain[i]));
        }
        s.infusionTick = now();
    }

    /**
     * 放技能前：有 infusion.consume 就把每種灌注各用掉一層，回傳這一下的加成（更多傷害、額外的元素傷害）；沒有回傳空的。
     */
    java.util.List<tw.exilecore.stats.Modifier> consumeInfusion(final Player player, final StatSheet effective, final Set<String> tags) {
        if (effective.flat(StatKeys.INFUSION_CONSUME, tags) <= 0) {
            return java.util.List.of();
        }
        final int[] have = infusions(player);
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return java.util.List.of();
        }
        final java.util.List<tw.exilecore.stats.Modifier> mods = new java.util.ArrayList<>();
        final double extra = effective.flat(StatKeys.INFUSION_EXTRA, tags);
        int used = 0;
        for (int i = 0; i < 3; i++) {
            if (have[i] > 0) {
                s.infusion[i]--;
                used++;
                if (extra > 0) {
                    mods.add(tw.exilecore.stats.Modifier.flat(StatKeys.damageExtra(tw.exilecore.stats.DamageType.fromTag(INFUSION_ELEMENTS[i])),
                            extra, INFUSION_NAMES[i] + "灌注"));
                }
            }
        }
        final double more = effective.flat(StatKeys.INFUSION_MORE, tags) * used;
        if (more > 0) {
            mods.add(tw.exilecore.stats.Modifier.more(StatKeys.DAMAGE, more / 100.0, "灌注"));
        }
        return mods;
    }

    /** 動作列上的一小段：怒氣、連擊、灌注（沒有回傳空字串）。 */
    public String hud(final Player player) {
        final int rage = rage(player);
        final int combo = combo(player);
        final java.util.List<String> parts = new java.util.ArrayList<>();
        if (rage > 0) {
            parts.add("<#e0533b>怒氣 " + rage);
        }
        if (combo > 0) {
            parts.add("<#f5b942>連擊 " + combo);
        }
        final int[] infusion = infusions(player);
        final StringBuilder inf = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (infusion[i] > 0) {
                inf.append(INFUSION_COLORS[i]).append(INFUSION_NAMES[i]).append(infusion[i]);
            }
        }
        if (inf.length() > 0) {
            parts.add("<gray>灌注 " + inf);
        }
        return String.join(" ", parts);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
