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
        long lastRageTaken;
        int combo;
        long comboTick;
        long lastComboHit;
        long comboDecay = COMBO_DECAY;
        long finisherUntil;
        /** 元素灌注（2026-10）：火、冰、電的層數與最後一次拿到的 tick。 */
        final int[] infusion = new int[3];
        long infusionTick;
        /** 2026-10-26 照 PoE2 亞馬遜「元素湧動」：火、冰、電湧動的層數與最後一次拿到的 tick。 */
        final int[] surge = new int[3];
        long surgeTick;
        /** 2026-10-27：這次施放用掉的湧動（每一發投射物一種元素的索引），投射物飛行結束時照順序拿出來爆炸。 */
        final java.util.ArrayDeque<Integer> surgeBlasts = new java.util.ArrayDeque<>();
        /** 終式用掉的層數（那一下的「每層連擊」照這個算）。 */
        int finisherCombo;
        long enragedUntil;
        /** 2026-10-26 原初飢餓：最近（4 秒）消耗的怒氣：{tick, 點數}。 */
        final java.util.ArrayDeque<long[]> rageSpent = new java.util.ArrayDeque<>();
        /** 2026-10-26 照 PoE2 天賦樹：怒氣開始流失前等幾 tick、每掉 1 點隔幾 tick（拿到怒氣時照屬性表更新）。 */
        long decayDelay = RAGE_DECAY_DELAY;
        double decayTicks = RAGE_DECAY_TICKS;
        /** 2026-10-28 照 PoE2 頂點：最近的近戰攻擊擊中 {tick, 技能代號}（最新的在後面，最多 {@link #CULMINATION_HISTORY} 筆）。 */
        final java.util.ArrayDeque<MeleeHit> meleeHits = new java.util.ArrayDeque<>();
        /** 2026-10-28 照 PoE2 頂點：被輔助的技能上一次用掉連擊的 tick（技能代號 → tick）。 */
        final Map<String, Long> culminated = new HashMap<>();
    }

    /** 2026-10-28：一次近戰攻擊擊中（同一下打中好幾個只記一筆）。 */
    record MeleeHit(long tick, String skill) {
    }

    static final int CULMINATION_HISTORY = 64;

    /** 2026-10-26 照 PoE2 天賦樹：「怒氣晚 x 秒才開始流失」「怒氣流失慢 x%」。 */
    private static void tune(final State s, final StatSheet sheet) {
        // 2026-10-26：負的也算（觀星專屬天賦「固有怒氣流失開始速度加快」），最少 0
        s.decayDelay = Math.max(0, RAGE_DECAY_DELAY + Math.round(sheet.flat(tw.exilecore.stats.TreeEffectKeys.RAGE_LOSS_DELAY, Set.of()) / 50.0));
        final double decay = 1 + sheet.increased(tw.exilecore.stats.TreeEffectKeys.RAGE_DECAY, Set.of());
        // 2026-10-27（骸節鐵手「怒氣不會自己流失」＝減少 100%）：不流失；這次之前最少照一成的速度流失
        s.decayTicks = decay <= 1e-9 ? Double.POSITIVE_INFINITY : RAGE_DECAY_TICKS / Math.max(0.1, decay);
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
        if (s.rage > 0 && now - s.rageTick > s.decayDelay) {
            final long steps = (long) Math.floor((now - s.rageTick - s.decayDelay) / s.decayTicks);
            if (steps > 0) {
                s.rage = Math.max(0, s.rage - steps);
                s.rageTick += Math.round(steps * s.decayTicks);
            }
        }
    }

    /** 用掉怒氣（最多 amount 點）；回傳實際用掉幾點。怒嘯（2026-10）用。 */
    public int spendRage(final Player player, final int amount) {
        final State s = states.get(player.getUniqueId());
        if (s == null || amount <= 0) {
            return 0;
        }
        decay(s, now());
        final int spent = (int) Math.min(s.rage, amount);
        s.rage -= spent;
        if (spent > 0) {
            final long tick = now();
            s.rageSpent.removeIf(entry -> tick - entry[0] > tw.exilecore.stats.StatKeys.RECENTLY_TICKS);
            s.rageSpent.addLast(new long[] {tick, spent}); // 2026-10-26 原初飢餓：最近消耗的怒氣
            if (plugin.skills() != null) {
                plugin.skills().supportMechanics().extras().rageSpent(player, spent); // 2026-10-27 照 PoE2 Relentless Rage
            }
        }
        return spent;
    }

    /** 2026-10-26（PoE2 的裂甲熊咆、聚怒嘯：照怪物強度給怒氣）：直接獲得 amount 點怒氣（不超過上限）；回傳實際加了幾點。 */
    public int gainRage(final Player player, final double amount) {
        if (amount <= 0 || blocked(player)) { // 2026-10-27 照 PoE2：引導蠻衝這類技能時不會獲得怒氣（這次之前只有打中時擋）
            return 0;
        }
        final long now = now();
        final State s = state(player.getUniqueId());
        decay(s, now);
        final StatSheet sheet = plugin.players().sheet(player);
        final double before = s.rage;
        overflowGlory(player, s.rage + amount - rageMax(sheet));
        s.rage = Math.min(rageMax(sheet), s.rage + amount);
        s.rageTick = now;
        rageGained(player, now); // 2026-10-27
        tune(s, sheet); // 2026-10-26：怒氣流失的延遲與速度
        reachedMax(player, s, sheet);
        return (int) Math.round(Math.max(0, s.rage - before));
    }

    /** 2026-10-27：這個玩家正在引導不能獲得怒氣的技能（{@link SkillService#channelBlocksRage}）。 */
    private boolean blocked(final Player player) {
        return plugin.skills() != null && plugin.skills().channelBlocksRage(player);
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

    /**
     * 怒氣現在是不是在消退（有怒氣，而且已經 {@value #RAGE_DECAY_DELAY} tick 沒拿到）。
     * 2026-10：赤怒的代價只在「沒有在消退」的時候扣生命（照 PoE2 的 Berserk）。
     */
    public boolean rageDecaying(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s != null && s.rage > 0 && now() - s.rageTick >= s.decayDelay;
    }

    static boolean rageDecaying(final double rage, final long rageTick, final long now) {
        return rage > 0 && now - rageTick >= RAGE_DECAY_DELAY;
    }

    /** 2026-10-26（照 PoE2 的不熄怒 Eternal Rage）：FLAT：每秒回復幾點怒氣（屬性代號寫在這裡，不另外加到 StatKeys）。 */
    public static final String RAGE_REGEN = "rage.regen";

    /**
     * 2026-10-26：怒氣回復（每 {@code ticks} tick 呼叫一次，PersistentBuffs）：照 {@link #RAGE_REGEN} 加怒氣；
     * 有回到怒氣就算「剛拿到怒氣」（不會開始消退）。沒有回復的玩家什麼都不做。
     */
    public void regenerate(final Player player, final long ticks) {
        if (blocked(player)) {
            return; // 2026-10-27 照 PoE2：引導時不會獲得怒氣——怒氣回復、原初飢餓也一樣（這次之前照回，原初飢餓讓蠻衝、噴焰能一直引導下去）
        }
        final StatSheet sheet = plugin.players().sheet(player);
        double perSecond = sheet.flat(RAGE_REGEN, Set.of());
        final long now = now();
        if (tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_PRIMAL_HUNGER)) {
            // 2026-10-26 照 PoE2 基石：原初飢餓——最近（4 秒）每消耗 4 點怒氣，每秒再生 1 點怒氣
            final State hunger = states.get(player.getUniqueId());
            if (hunger != null) {
                hunger.rageSpent.removeIf(entry -> now - entry[0] > tw.exilecore.stats.StatKeys.RECENTLY_TICKS);
                long spent = 0;
                for (final long[] entry : hunger.rageSpent) {
                    spent += entry[1];
                }
                perSecond += Math.floor(spent / 4.0);
            }
        }
        if (perSecond <= 0 || ticks <= 0) {
            return;
        }
        final State s = state(player.getUniqueId());
        decay(s, now);
        final int max = rageMax(sheet);
        if (s.rage >= max) {
            s.rageTick = now; // 滿的時候也不消退
            overflowGlory(player, perSecond * ticks / 20.0); // 2026-10-26：滿了還在回的怒氣算榮耀（墜星熊吼）
            tune(s, sheet); // 2026-10-26：怒氣流失的延遲與速度
            return;
        }
        s.rage = Math.min(max, s.rage + perSecond * ticks / 20.0);
        s.rageTick = now;
        rageGained(player, now); // 2026-10-27
        tune(s, sheet); // 2026-10-26：怒氣流失的延遲與速度
        reachedMax(player, s, sheet);
    }

    /** 怒氣上限。 */
    public int rageMax(final StatSheet sheet) {
        // 2026-10-26：「更多最大怒氣」（原初飢餓更多 100%）也算
        return (int) Math.max(0, (RAGE_BASE_MAX + sheet.flat(StatKeys.RAGE_MAX, Set.of())) * Math.max(0, sheet.more(StatKeys.RAGE_MAX, Set.of())));
    }

    /** 近戰攻擊打中：加怒氣。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.result().blocked()
                || event.skill() == null || !event.skill().isAttack() || !event.skill().tags().contains(Tags.MELEE)
                || event.skill().tags().contains(Tags.TOTEM_HIT)) { // 2026-10-21：圖騰、英靈打中的不給你怒氣、連擊（照 PoE）
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        recordMeleeHit(state(player.getUniqueId()).meleeHits, SupportProcs.baseId(event.skill().id()), now()); // 2026-10-28 照 PoE2 頂點
        gainCombo(player, event, sheet); // 2026-10：連擊
        final double gain = event.skill().effective(sheet).flat(StatKeys.RAGE_ON_HIT, event.skill().tags());
        if (gain <= 0 || plugin.skills() != null && plugin.skills().channelBlocksRage(player)) { // 2026-10-26 照 PoE2：引導蠻衝時不會獲得怒氣
            return;
        }
        final long now = now();
        final State s = state(player.getUniqueId());
        decay(s, now);
        if (now - s.lastRageHit < 4) {
            return; // 同一下打中好幾個只算一次
        }
        s.lastRageHit = now;
        overflowGlory(player, s.rage + gain - rageMax(sheet));
        // 2026-10-26 照 PoE2 天賦：x% 機率改成直接補到最大怒氣
        final double fill = sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.RAGE_FILL_CHANCE, Set.of());
        s.rage = fill > 0 && plugin.random().nextDouble() * 100 < fill ? rageMax(sheet) : Math.min(rageMax(sheet), s.rage + gain);
        s.rageTick = now;
        rageGained(player, now); // 2026-10-27
        tune(s, sheet); // 2026-10-26：怒氣流失的延遲與速度
        reachedMax(player, s, sheet);
    }

    /** 2026-10-27 照 PoE2 天賦樹（「最近獲得過怒氣時」）：記下剛拿到怒氣。 */
    private void rageGained(final Player player, final long now) {
        plugin.players().get(player).markRecent(tw.exilecore.stats.TreeConditions.RAGE_GAINED, now);
    }

    /** 2026-10-26 照 PoE2（墜星熊吼）：怒氣滿了還拿到的怒氣算成榮耀（glory_from: rage_at_max）。 */
    private void overflowGlory(final Player player, final double overflow) {
        if (overflow > 0 && plugin.skills() != null) {
            plugin.skills().supportMechanics().gainGlory(player, "rage_at_max", Math.min(overflow, RAGE_BASE_MAX));
        }
    }

    /** 憎鑄（2026-10-24，PoE2 Hateforge）：怒氣滿時「失去全部怒氣」。 */
    public static final String LOSE_RAGE_AT_MAX = StatKeys.FLAG_LOSE_RAGE_AT_MAX;

    /** 怒氣剛加完：滿了的話給戰星（charge.on_max_rage）、失去全部怒氣（flag.lose_rage_at_max）。 */
    private void reachedMax(final Player player, final State s, final StatSheet sheet) {
        final int max = rageMax(sheet);
        if (max <= 0 || s.rage < max) {
            return;
        }
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            plugin.combat().charges().onMaxRage(player, sheet);
        }
        if (StatKeys.flag(sheet, LOSE_RAGE_AT_MAX)) {
            s.rage = 0;
        }
    }

    /** 2026-10-21（PoE2 珠寶）：被敵人擊中時獲得怒氣（rage.when_hit；被格擋、閃掉的不算；同一 tick 只算一次）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitTaken(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || event.attacker() instanceof Player || !event.result().hit() || event.result().blocked()) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final double gain = sheet.flat(StatKeys.RAGE_WHEN_HIT, Set.of());
        if (gain <= 0) {
            return;
        }
        final long now = now();
        final State s = state(player.getUniqueId());
        decay(s, now);
        if (now - s.lastRageTaken < 4) {
            return;
        }
        s.lastRageTaken = now;
        overflowGlory(player, s.rage + gain - rageMax(sheet));
        s.rage = Math.min(rageMax(sheet), s.rage + gain);
        s.rageTick = now;
        rageGained(player, now); // 2026-10-27
        tune(s, sheet); // 2026-10-26：怒氣流失的延遲與速度
        reachedMax(player, s, sheet);
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

    /** 2026-10-26 照 PoE2（驚雷之鐘要 4 點連擊）：用掉 amount 點連擊；回傳實際用掉幾點。 */
    public int spendCombo(final Player player, final int amount) {
        final int have = combo(player);
        final State s = states.get(player.getUniqueId());
        if (s == null || amount <= 0 || have <= 0) {
            return 0;
        }
        final int spent = Math.min(have, amount);
        s.combo -= spent;
        tw.exilecore.ascendancy.AscendancyEffects.comboSpent(player, spent); // 2026-10-27 照 PoE2（昇華・武術精通）
        return spent;
    }

    /**
     * 2026-10-26 照 PoE2（skills.yml 的 resource.infusion）：照順序找第一種身上有的灌注、用掉一層；回傳用掉的是哪一種（0 火、1 冰、2 電），沒有回傳 -1。
     */
    int consumeOne(final Player player, final java.util.List<String> elements) {
        return consumeOne(player, elements, null, Set.of());
    }

    /**
     * 同上；2026-10-26 照 PoE2：掛了抑星（cannot_consume_infusions）不用；「有機率不拿掉灌注，但照樣算用掉」（effective 是這個技能疊好輔助的屬性表，可以是 null）。
     */
    int consumeOne(final Player player, final java.util.List<String> elements, final StatSheet effective, final Set<String> tags) {
        if (effective != null && effective.flat(tw.exilecore.charge.ChargeService.SUP_INHIBIT, tags) > 0) {
            return -1;
        }
        final int[] have = infusions(player);
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return -1;
        }
        final double keep = effective == null ? 0 : effective.flat(tw.exilecore.stats.TreeMechanicKeys.INFUSION_KEEP_CHANCE, tags);
        for (final String element : elements) {
            for (int i = 0; i < 3; i++) {
                if (INFUSION_ELEMENTS[i].equals(element) && have[i] > 0) {
                    if (keep <= 0 || plugin.random().nextDouble() * 100 >= keep) {
                        s.infusion[i]--;
                    }
                    plugin.players().conditions().onInfusionConsumed(player, 1, now()); // 2026-10-26：天賦樹
                    return i;
                }
            }
        }
        return -1;
    }

    /** 近戰打擊打中：累積連擊（同一下打中好幾個只算一次）。 */
    private void gainCombo(final Player player, final HitEvent event, final StatSheet sheet) {
        if (!event.skill().tags().contains(Tags.STRIKE) && !(event.skill().tags().contains(Tags.ATTACK)
                && tw.exilecore.ascendancy.AscendancyEffects.comboFromAnyAttack(player))) { // 2026-10-27 照 PoE2（昇華・武術宗師）：所有攻擊擊中都累積
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
            tw.exilecore.ascendancy.AscendancyEffects.comboSpent(player, s.finisherCombo); // 2026-10-27 照 PoE2（昇華・武術精通）
            s.finisherUntil = now + 12;
            // 2026-10-24（鐘星，PoE2 Ailith's Chimes）：每用掉一層連擊 25% 機率（超過 100% 的部分每 100% 必給一顆）給銳星
            final double chime = effective.flat(tw.exilecore.charge.ChargeService.SUP_COMBO_POWER, tags) * s.finisherCombo;
            if (chime > 0 && plugin.combat() != null && plugin.combat().charges() != null) {
                final int count = (int) (chime / 100) + (plugin.random().nextDouble() * 100 < chime % 100 ? 1 : 0);
                plugin.combat().charges().grant(player, "power", count);
            }
        }
        if (effective.flat(StatKeys.RAGEFORGED, tags) > 0 && rage(player) >= 10) {
            spendRage(player, 10); // 2026-10-26：走 spendRage（原初飢餓記得最近消耗的怒氣）；這次之前直接扣
            final State s = state(player.getUniqueId());
            s.enragedUntil = now + 12;
        }
    }

    // ---- 頂點（2026-10-28 照 PoE2 Culmination II）----

    /** 記下一次近戰攻擊擊中（同一個技能 4 tick 內打中好幾個只算一次）。 */
    static void recordMeleeHit(final java.util.ArrayDeque<MeleeHit> hits, final String skill, final long now) {
        final MeleeHit last = hits.peekLast();
        if (last != null && last.skill().equals(skill) && now - last.tick() < 4) {
            return;
        }
        hits.addLast(new MeleeHit(now, skill));
        while (hits.size() > CULMINATION_HISTORY) {
            hits.pollFirst();
        }
    }

    /** 這一下是不是被輔助的技能自己（含它衍生的 .aftershock 這種）。 */
    private static boolean sameSkill(final String hit, final String skill) {
        return hit.equals(skill) || hit.startsWith(skill + ".");
    }

    /**
     * 被輔助的技能現在有幾層連擊：上一次用掉之後，你用「其他」近戰攻擊擊中敵人的次數（同一下打中好幾個算一次），最多 max；
     * 超過 decayTicks 沒有獲得連擊就失去全部（從最新的往回數，中間斷超過 decayTicks 的之前那些不算）。
     */
    static int culminationCombo(final java.util.ArrayDeque<MeleeHit> hits, final long since, final String skill, final long now,
                                final long decayTicks, final int max) {
        if (hits == null || max <= 0) {
            return 0;
        }
        int count = 0;
        long previous = now;
        for (final java.util.Iterator<MeleeHit> it = hits.descendingIterator(); it.hasNext() && count < max; ) {
            final MeleeHit hit = it.next();
            if (hit.tick() <= since) {
                break;
            }
            if (sameSkill(hit.skill(), skill)) {
                continue; // 自己的擊中不給自己連擊
            }
            if (previous - hit.tick() > decayTicks) {
                break; // 這之前斷掉過：失去了
            }
            count++;
            previous = hit.tick();
        }
        return count;
    }

    static long culminationDecay(final StatSheet effective, final Set<String> tags) {
        final double seconds = effective.flat(tw.exilecore.stats.SupportExtraKeys.CULMINATION_DECAY, tags);
        return Math.max(1, Math.round((seconds > 0 ? seconds : 6) * 20));
    }

    static int culminationMax(final StatSheet effective, final Set<String> tags) {
        final double max = effective.flat(tw.exilecore.stats.SupportExtraKeys.CULMINATION_MAX, tags);
        return max > 0 ? (int) Math.floor(max + 1e-9) : COMBO_MAX;
    }

    /**
     * 放技能前（SkillService 呼叫）：掛了頂點的技能用掉它自己的全部連擊，這一下每用掉 1 層更多 x% 傷害（照 PoE2：用了就重置，0 層也算用過）。
     * 回傳加上「頂點」更多傷害的組合；沒掛頂點回傳原本的。
     */
    SkillBuild culminate(final Player player, final SkillBuild build, final StatSheet sheet) {
        final StatSheet effective = build.profile().effective(sheet);
        final Set<String> tags = build.profile().tags();
        final double per = effective.flat(tw.exilecore.stats.SupportExtraKeys.CULMINATION, tags);
        if (per <= 0) {
            return build;
        }
        final long now = now();
        final State s = state(player.getUniqueId());
        final String skill = build.definition().id();
        final int combo = culminationCombo(s.meleeHits, s.culminated.getOrDefault(skill, Long.MIN_VALUE), skill, now,
                culminationDecay(effective, tags), culminationMax(effective, tags));
        s.culminated.put(skill, now);
        if (combo <= 0) {
            return build;
        }
        tw.exilecore.ascendancy.AscendancyEffects.comboSpent(player, combo); // 照 PoE2：用掉的是連擊（昇華・武術精通）
        return build.withProfile(build.profile().withLocal(java.util.List.of(
                tw.exilecore.stats.Modifier.more(StatKeys.DAMAGE, per * combo / 100.0, "頂點（" + combo + " 層連擊）"))));
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

    /** 2026-10-26 照 PoE2（關鍵字 Elemental Infusions：每種預設最多 3 層、撐 20 秒）；這次之前 2 層、10 秒。 */
    static final int INFUSION_BASE_MAX = 3;
    static final long INFUSION_DURATION = 400;
    static final String[] INFUSION_ELEMENTS = {"fire", "cold", "lightning"};
    private static final String[] INFUSION_NAMES = {"火", "冰", "電"};
    private static final String[] INFUSION_COLORS = {"<#ff7a3a>", "<#7fd8ff>", "<#ffe066>"};

    /** 2026-10-26：灌注撐幾 tick（20 秒 × (1 + 灌注持續時間)）。 */
    static long infusionTicks(final StatSheet sheet) {
        return sheet == null ? INFUSION_DURATION
                : Math.max(20, Math.round(INFUSION_DURATION * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.TreeMechanicKeys.INFUSION_DURATION, Set.of()))));
    }

    /** 2026-10-26：某一種灌注最多幾層（基礎 3 ＋ infusion.max ＋ infusion.max.元素）。 */
    static int infusionMax(final StatSheet sheet, final int element) {
        if (sheet == null) {
            return INFUSION_BASE_MAX;
        }
        return Math.max(1, INFUSION_BASE_MAX + (int) Math.round(sheet.flat(StatKeys.INFUSION_MAX, Set.of())
                + sheet.flat(tw.exilecore.stats.TreeMechanicKeys.INFUSION_MAX_ELEMENT + INFUSION_ELEMENTS[element], Set.of())));
    }

    /** 身上的灌注（火、冰、電；超過持續時間沒拿到就全部散掉）。 */
    public int[] infusions(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return new int[3];
        }
        if (now() - s.infusionTick > infusionTicks(plugin.players().sheet(player))) { // 2026-10-26：吃天賦的灌注持續時間
            java.util.Arrays.fill(s.infusion, 0);
        }
        return s.infusion.clone();
    }

    /** 2026-10-26：直接給一層灌注（0 火、1 冰、2 電；雷遁成功時給閃電灌注）。 */
    void addInfusion(final Player player, final int rawElement, final int amount) {
        // 2026-10-26 照 PoE2 基石：元素之相——火→電、電→冰、冰→火
        final int element = tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneKeys.FLAG_ELEMENTAL_EQUILIBRIUM)
                ? tw.exilecore.stats.KeystoneKeys.equilibrium(rawElement) : rawElement;
        infusions(player);
        final State s = state(player.getUniqueId());
        s.infusion[element] = Math.min(infusionMax(plugin.players().sheet(player), element), s.infusion[element] + Math.max(0, amount));
        s.infusionTick = now();
    }

    /** 2026-10-26 照 PoE2「拆射灌注」（Refracted Infusion）：撿到元素灌注時，再獲得另一種不同的元素灌注。 */
    public static final String FLAG_REFRACTED = "flag.infusion_refracted";

    /**
     * 2026-10-26 照 PoE2：撿到灌注殘骸（{@link ChargeEffects#dropInfusionRemnant}）——獲得那種灌注 times 層；
     * 天賦「撿到灌注時有機率多一層同種的」、昇華「拆射灌注：另外再給一層不同的」。元素之相在產生殘骸時就換過了。
     */
    void collectInfusion(final Player player, final int element, final int times) {
        final StatSheet sheet = plugin.players().sheet(player);
        int amount = Math.max(1, times);
        final double extraChance = sheet.flat(tw.exilecore.stats.TreeMechanicKeys.INFUSION_EXTRA_CHANCE, Set.of());
        if (extraChance > 0 && plugin.random().nextDouble() * 100 < extraChance) {
            amount++;
        }
        infusions(player);
        final State s = state(player.getUniqueId());
        s.infusion[element] = Math.min(infusionMax(sheet, element), s.infusion[element] + amount);
        if (StatKeys.flag(sheet, FLAG_REFRACTED)) {
            final int other = (element + 1 + plugin.random().nextInt(2)) % 3;
            s.infusion[other] = Math.min(infusionMax(sheet, other), s.infusion[other] + 1);
        }
        s.infusionTick = now();
    }

    /** 灌注元素代號（fire／cold／lightning）→ 0／1／2；不認得回傳 -1。 */
    static int infusionIndex(final String element) {
        for (int i = 0; i < 3; i++) {
            if (INFUSION_ELEMENTS[i].equals(element)) {
                return i;
            }
        }
        return -1;
    }

    // ---- 2026-10-26 照 PoE2 亞馬遜：元素湧動（Elemental Surge）----
    // 消耗戰星時觸發元素湧動，獲得幾層湧動（surge.fire／surge.cold／surge.lightning，昇華給）；每種最多 6 層、15 秒沒拿到就散掉。
    // 非近戰的投射物攻擊每一發投射物用掉一層，投射物在飛行終點爆開 1.8 格、造成那種元素的傷害（PoE2 Elemental Surge：攻擊傷害 50%～181%，
    // 物理全轉成那種元素）。2026-10-27 照 PoE2 做成另外一發爆炸（湧動爆炸 asc_surge_*，ProjectileEngine 在投射物飛行結束時觸發）；
    // 這次之前做不到：用掉湧動的那一下投射物自己在終點爆開 1.8 格，並獲得 100% 傷害為額外的那種元素傷害（平衡等效）。

    /** 消耗一顆戰星時各種湧動獲得幾層（FLAT）。 */
    public static final String SURGE_PREFIX = "surge.";
    static final int SURGE_MAX = 6;
    static final long SURGE_DURATION = 300;
    /** 2026-10-27：用掉湧動的投射物的標記（技能描述的 local，FLAT 1；ProjectileEngine 看到就在飛行結束時觸發湧動爆炸）。 */
    public static final String SURGE_BLAST = "surge.blast";
    /** 湧動爆炸的隱藏技能（skills.yml，tools/gen_asc_skills.py）：asc_surge_fire／cold／lightning。 */
    static final String SURGE_SKILL = "asc_surge_";

    /** 身上的湧動（火、冰、電；超過 15 秒沒拿到就全部散掉）。 */
    public int[] surges(final Player player) {
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return new int[3];
        }
        if (now() - s.surgeTick > SURGE_DURATION) {
            java.util.Arrays.fill(s.surge, 0);
        }
        return s.surge.clone();
    }

    /** 消耗了一顆戰星（ConditionTracker 呼叫）：有昇華「元素湧動」的獲得湧動。 */
    public void onChargeConsumed(final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        final int[] gain = new int[3];
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            gain[i] = (int) Math.round(Math.max(0, sheet.flat(SURGE_PREFIX + INFUSION_ELEMENTS[i], Set.of())));
            any |= gain[i] > 0;
        }
        if (!any) {
            return;
        }
        surges(player);
        final State s = state(player.getUniqueId());
        for (int i = 0; i < 3; i++) {
            s.surge[i] = Math.min(SURGE_MAX, s.surge[i] + gain[i]);
        }
        s.surgeTick = now();
    }

    /**
     * 非近戰的投射物攻擊放出去前（SkillService 呼叫）：每一發投射物用掉一層湧動（層數最多的先用），回傳各用掉幾層（火、冰、電）；沒有回傳 null。
     */
    int[] consumeSurges(final Player player, final int projectiles) {
        final int[] have = surges(player);
        final State s = states.get(player.getUniqueId());
        if (s == null || have[0] + have[1] + have[2] <= 0) {
            return null;
        }
        final int[] used = new int[3];
        for (int n = 0; n < Math.max(1, projectiles); n++) {
            int best = -1;
            for (int i = 0; i < 3; i++) {
                if (s.surge[i] > 0 && (best < 0 || s.surge[i] > s.surge[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            s.surge[best]--;
            used[best]++;
        }
        return used;
    }

    /**
     * 2026-10-27 照 PoE2：記下這次施放用掉的湧動（每一發投射物一種），投射物飛行結束時照順序爆炸（{@link #surgeBlast}）。
     * 上一次施放還沒爆完的不留（投射物已經不在了）。
     */
    void queueSurgeBlasts(final Player player, final int[] used) {
        final State s = state(player.getUniqueId());
        s.surgeBlasts.clear();
        s.surgeBlasts.addAll(surgeOrder(used));
    }

    /** 用掉的湧動排成每一發投射物的元素索引（火、冰、電照順序）。純函式（測試用）。 */
    static java.util.List<Integer> surgeOrder(final int[] used) {
        final java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int n = 0; n < used[i]; n++) {
                order.add(i);
            }
        }
        return order;
    }

    /** 用掉湧動的投射物的標記（加在技能描述上）。 */
    static java.util.List<tw.exilecore.stats.Modifier> surgeMarker() {
        return java.util.List.of(tw.exilecore.stats.Modifier.flat(SURGE_BLAST, 1, "元素湧動"));
    }

    /**
     * 2026-10-27 照 PoE2：一發用掉湧動的投射物飛行結束（打中停下、飛到盡頭）：在那裡觸發那種元素的湧動爆炸（1.8 格、攻擊傷害 50%～181%、
     * 物理全部轉成那種元素）。這次施放的湧動用完了就不爆。回傳爆的是哪一種（0 火、1 冰、2 電；沒有 -1）。
     */
    public int surgeBlast(final Player player, final org.bukkit.Location at) {
        final State s = states.get(player.getUniqueId());
        if (s == null || s.surgeBlasts.isEmpty()) {
            return -1;
        }
        final int element = s.surgeBlasts.pollFirst();
        if (plugin.skills() != null) {
            plugin.skills().triggerSkill(player, SURGE_SKILL + INFUSION_ELEMENTS[element], at);
        }
        return element;
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
        if (tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneKeys.FLAG_ELEMENTAL_EQUILIBRIUM)) {
            // 2026-10-26 照 PoE2 基石：元素之相——產生的灌注換一種（火→電、電→冰、冰→火）
            final int[] rotated = new int[3];
            for (int i = 0; i < 3; i++) {
                rotated[tw.exilecore.stats.KeystoneKeys.equilibrium(i)] += gain[i];
            }
            System.arraycopy(rotated, 0, gain, 0, 3);
        }
        infusions(player); // 先把過期的清掉
        final State s = state(player.getUniqueId());
        // 2026-10-26 照 PoE2：每種灌注的上限可以各自加（天賦「+1 最大火焰灌注」）；獲得時有機率多一層同種的
        final double extraChance = effective.flat(tw.exilecore.stats.TreeMechanicKeys.INFUSION_EXTRA_CHANCE, Set.of());
        for (int i = 0; i < 3; i++) {
            if (gain[i] > 0 && extraChance > 0 && plugin.random().nextDouble() * 100 < extraChance) {
                gain[i]++;
            }
            s.infusion[i] = Math.min(infusionMax(effective, i), s.infusion[i] + Math.max(0, gain[i]));
        }
        s.infusionTick = now();
    }

    /**
     * 放技能前：有 infusion.consume 就把每種灌注各用掉一層，回傳這一下的加成（更多傷害、額外的元素傷害）；沒有回傳空的。
     */
    java.util.List<tw.exilecore.stats.Modifier> consumeInfusion(final Player player, final StatSheet effective, final Set<String> tags) {
        return consumeInfusion(player, effective, tags, null);
    }

    /**
     * 同上；{@code usedOut}（長度 3：火、冰、電）記下這一次各用掉幾層（2026-10-15：消耗提示用）。
     */
    java.util.List<tw.exilecore.stats.Modifier> consumeInfusion(final Player player, final StatSheet effective, final Set<String> tags,
                                                               final int[] usedOut) {
        if (effective.flat(StatKeys.INFUSION_CONSUME, tags) <= 0
                || effective.flat(tw.exilecore.charge.ChargeService.SUP_INHIBIT, tags) > 0) { // 2026-10-24：抑星不消耗灌注
            return java.util.List.of();
        }
        final int[] have = infusions(player);
        final State s = states.get(player.getUniqueId());
        if (s == null) {
            return java.util.List.of();
        }
        final java.util.List<tw.exilecore.stats.Modifier> mods = new java.util.ArrayList<>();
        final double extra = effective.flat(StatKeys.INFUSION_EXTRA, tags);
        final double keep = effective.flat(tw.exilecore.stats.TreeMechanicKeys.INFUSION_KEEP_CHANCE, tags);
        int used = 0;
        for (int i = 0; i < 3; i++) {
            if (have[i] > 0) {
                if (keep <= 0 || plugin.random().nextDouble() * 100 >= keep) { // 2026-10-26 照 PoE2：有機率不拿掉，但照樣算用掉
                    s.infusion[i]--;
                }
                used++;
                if (usedOut != null) {
                    usedOut[i]++;
                }
                if (extra > 0) {
                    mods.add(tw.exilecore.stats.Modifier.flat(StatKeys.damageExtra(tw.exilecore.stats.DamageType.fromTag(INFUSION_ELEMENTS[i])),
                            extra, INFUSION_NAMES[i] + "灌注"));
                }
            }
        }
        if (used > 0) {
            plugin.players().conditions().onInfusionConsumed(player, used, now()); // 2026-10-26：天賦樹「最近消耗過灌注」「消耗灌注時獲得銳星」
        }
        final double more = effective.flat(StatKeys.INFUSION_MORE, tags) * used;
        if (more > 0) {
            mods.add(tw.exilecore.stats.Modifier.more(StatKeys.DAMAGE, more / 100.0, "灌注"));
        }
        return mods;
    }


    /**
     * 用掉灌注的提示（2026-10-15）：動作列寫「技能 用掉灌注：火 ×1、冰 ×1」（照元素上色），叮一聲，身上冒對應顏色的粒子。
     * 沒用掉回傳 null。
     */
    String infusionUsed(final Player player, final String skillName, final int[] used) {
        final java.util.List<String> parts = new java.util.ArrayList<>();
        final org.bukkit.Location at = player.getLocation().add(0, 1.0, 0);
        for (int i = 0; i < 3; i++) {
            if (used[i] <= 0) {
                continue;
            }
            parts.add(INFUSION_COLORS[i] + INFUSION_NAMES[i] + " ×" + used[i]);
            final int rgb = Integer.parseInt(INFUSION_COLORS[i].substring(2, 8), 16);
            player.getWorld().spawnParticle(org.bukkit.Particle.DUST, at, 10, 0.35, 0.5, 0.35, 0,
                    new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(rgb), 1.3f));
        }
        if (parts.isEmpty()) {
            return null;
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.4f);
        return "<#ffd27f>" + skillName + " 用掉灌注：" + String.join("<gray>、", parts);
    }

    /** 動作列上的一小段：怒氣、連擊、灌注（沒有回傳空字串）。 */
    public String hud(final Player player) {
        final int rage = rage(player);
        final java.util.List<String> parts = new java.util.ArrayList<>();
        if (rage > 0) {
            parts.add(rageBar(player, rage));
        }
        parts.addAll(otherParts(player));
        return String.join(" ", parts);
    }

    /** 戰鬥資源血條（2026-10，{@link tw.exilecore.character.ResourceBar}）的文字：連擊、灌注、獸性／狂暴（怒氣另外畫成進度條）。 */
    public java.util.List<String> otherParts(final Player player) {
        final int combo = combo(player);
        final java.util.List<String> parts = new java.util.ArrayList<>();
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
        final int[] surge = surges(player); // 2026-10-26 照 PoE2 亞馬遜：元素湧動
        final StringBuilder sur = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (surge[i] > 0) {
                sur.append(INFUSION_COLORS[i]).append(INFUSION_NAMES[i]).append(surge[i]);
            }
        }
        if (sur.length() > 0) {
            parts.add("<gray>湧動 " + sur);
        }
        // 2026-10：獸血沸的獸性／狂暴
        final String fury = plugin.skills() == null || plugin.skills().auras() == null ? "" : plugin.skills().auras().furyHud(player.getUniqueId());
        if (!fury.isEmpty()) {
            parts.add(fury);
        }
        return parts;
    }

    /** 怒氣條的格數。 */
    static final int RAGE_BAR_SEGMENTS = 10;

    /**
     * 快捷欄上方的怒氣條（2026-10）：「怒氣 ▰▰▰▰▱▱▱▱▱▱ 12/30」。滿的格子照怒氣多寡從橙到紅；
     * 在消退（一陣子沒打中）時變灰，提醒要繼續打；滿了寫 MAX。
     */
    String rageBar(final Player player, final int rage) {
        final int max = Math.max(1, rageMax(plugin.players().sheet(player)));
        final boolean decaying = rageDecaying(player);
        final boolean full = rage >= max;
        return rageBar(rage, max, decaying, full);
    }

    static String rageBar(final int rage, final int max, final boolean decaying, final boolean full) {
        final int filled = (int) Math.max(rage > 0 ? 1 : 0, Math.min(RAGE_BAR_SEGMENTS, Math.round((double) rage * RAGE_BAR_SEGMENTS / Math.max(1, max))));
        final String color = decaying ? "<#9a9a9a>" : full ? "<#ff3b1f>" : rage * 2 >= max ? "<#ff6a3d>" : "<#e0533b>";
        return "<#e0533b>怒氣 " + color + "▰".repeat(filled) + "<dark_gray>" + "▱".repeat(RAGE_BAR_SEGMENTS - filled)
                + " <white>" + rage + "<gray>/" + max + (full ? " <#ff3b1f>MAX" : "") + (decaying ? " <dark_gray>消退中" : "");
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** 2026-10-27：清掉這個玩家的怒氣、連擊、灌注、湧能…（離線、換角色）。 */
    void forget(final UUID player) {
        states.remove(player);
    }
}
