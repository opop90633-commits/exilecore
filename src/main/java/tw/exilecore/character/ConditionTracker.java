package tw.exilecore.character;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.TreeConditions;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * 2026-10-26：天賦樹條件（{@link TreeConditions}）裡「自己的狀態」現在成不成立。
 * <p>
 * 屬性表重算時（{@link PlayerStateService} 的 resolveCondition）與每 10 tick 檢查條件有沒有變時呼叫 {@link #test}。
 * 生命、魔力、護盾的比例與移動在 {@link #sample}（每次 tick 一開始）記下來：重算屬性表時那張表正在重建，不能拿它算上限。
 * 變身、夥伴這種還沒有系統的條件由那些系統用 {@link #register} 接上；沒接的一律不成立。
 * <p>
 * 2026-10-26：變身（SkillService 的形態增益）、夥伴（MinionService）、衝刺、招架、舉盾、先鋒、圖騰、護符、自己的異常都在 {@link #test} 接上；
 * 「最近…」的新事件與「最近幾次／幾種」的計數由各系統呼叫 {@code on…}／{@link #note}（變回人形在 {@link #sample} 看形態增益沒了），
 * 事件的效果（消耗戰星回復、變回人形的法術傷害、被重暈回生命、放混沌技能的代價）也在這裡（屬性鍵見 {@link tw.exilecore.stats.ConditionKeys}）。
 */
public final class ConditionTracker {

    /** 主手、副手在 {@link PlayerState#JEWELLERY_SLOTS} 的位置。 */
    static final int MAIN_HAND = slot(tw.exilecore.item.ItemSlot.MAIN_HAND);
    static final int OFF_HAND = slot(tw.exilecore.item.ItemSlot.OFF_HAND);

    private static int slot(final tw.exilecore.item.ItemSlot slot) {
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            if (PlayerState.JEWELLERY_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** 移動多快以上算「移動中」（格／秒）。 */
    static final double MOVING_SPEED = 0.5;
    /** 舉盾增益的代號開頭（"guard" 或 "guard.技能"）。 */
    static final String GUARD_BUFF = "guard";
    /** 護符增益的代號開頭。 */
    static final String CHARM_BUFF = "charm.";
    /** 「3 格內沒有敵人」。 */
    static final double NO_ENEMY_RADIUS = 3;
    /** 「過去 10 秒內用過生命藥劑」。 */
    static final long LIFE_FLASK_WINDOW = 200;
    /** 「過去 8 秒」的計數事件。 */
    static final long EIGHT_SECONDS = 160;
    /** 2026-10-27：「過去 10 秒」的計數事件；計數紀錄留這麼久（最長的那種）。 */
    static final long TEN_SECONDS = 200;
    /** 計數事件：代號 → 多久之內算（tick）。 */
    static final Map<String, Long> WINDOWS = Map.ofEntries(
            Map.entry(TreeConditions.ENDURANCE_SPENT, StatKeys.RECENTLY_TICKS), Map.entry(TreeConditions.WARCRIES, StatKeys.RECENTLY_TICKS),
            Map.entry(TreeConditions.HITS_TAKEN, StatKeys.RECENTLY_TICKS),
            Map.entry(TreeConditions.WARCRY_TYPES, StatKeys.RECENTLY_TICKS), Map.entry(TreeConditions.STUNS, StatKeys.RECENTLY_TICKS),
            Map.entry(TreeConditions.SPELL_TYPES, EIGHT_SECONDS), Map.entry(TreeConditions.SLOW_SPELL_TYPES, EIGHT_SECONDS),
            Map.entry(TreeConditions.SLOW_ATTACK_TYPES, EIGHT_SECONDS), Map.entry(TreeConditions.IGNITED_ENEMIES, EIGHT_SECONDS),
            Map.entry(TreeConditions.FROZEN_ENEMIES, EIGHT_SECONDS),
            // 2026-10-27 照 PoE2：法術回響、危害、中毒（最近）、榴彈種類（8 秒）、彈藥種類（10 秒）
            Map.entry(TreeConditions.ECHOES, StatKeys.RECENTLY_TICKS), Map.entry(TreeConditions.HAZARDS, StatKeys.RECENTLY_TICKS),
            Map.entry(TreeConditions.POISONS, StatKeys.RECENTLY_TICKS), Map.entry(TreeConditions.GRENADE_TYPES, EIGHT_SECONDS),
            Map.entry(TreeConditions.AMMO_TYPES, TEN_SECONDS));
    /** 只算不同種類的計數事件（同一個代號只算一次）。 */
    static final Set<String> DISTINCT = Set.of(TreeConditions.WARCRY_TYPES, TreeConditions.SPELL_TYPES, TreeConditions.SLOW_SPELL_TYPES,
            TreeConditions.SLOW_ATTACK_TYPES, TreeConditions.IGNITED_ENEMIES, TreeConditions.FROZEN_ENEMIES,
            TreeConditions.AMMO_TYPES, TreeConditions.GRENADE_TYPES, TreeConditions.HAZARDS); // 2026-10-27

    /** 一次計數事件：哪一種、哪個代號（技能、敵人）、哪個 tick。 */
    record Noted(String kind, String id, long tick) {
    }

    /** 玩家 → 最近的計數事件（舊的在 {@link #note} 時清掉）。 */
    private final Map<UUID, java.util.ArrayDeque<Noted>> noted = new HashMap<>();

    private final ExileCore plugin;
    private final Map<UUID, Sample> samples = new HashMap<>();
    private final Map<String, BiPredicate<Player, PlayerState>> providers = new HashMap<>();
    /** 屬性表裡有沒有「對某種敵人」的修飾器（戰鬥那邊才去看目標的狀態）。 */
    private final Map<UUID, Boolean> targetTags = new HashMap<>();

    /** 一個玩家上一次記下來的狀態。 */
    static final class Sample {
        String world;
        double x;
        double z;
        long tick = Long.MIN_VALUE;
        boolean moving;
        double lifeFraction = 1;
        double manaFraction = 1;
        double es;
        double maxEs;
        double presenceRadius = tw.exilecore.combat.Presence.BASE_RADIUS;
        /** 2026-10-27 照 PoE2 天賦樹：判定被包圍的範圍（增加 x% 被包圍的範圍）。 */
        double surroundedRadius = TreeConditions.SURROUNDED_RADIUS;
        /** 2026-10-26：生命藥劑一次都喝不了。 */
        boolean noLifeFlask;
        /** 2026-10-26：上次看是不是獸形、從哪個 tick 開始（變回人形的效果用）。 */
        boolean shapeshifted;
        long formSince;
    }

    public ConditionTracker(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 別的系統接上條件（變身中、夥伴在臨在範圍內…）：之後那個條件照這個判斷。 */
    public void register(final String condition, final BiPredicate<Player, PlayerState> provider) {
        providers.put(condition, provider);
    }

    /** 屬性表重算完：記下這個玩家有沒有「對某種敵人」的修飾器。 */
    public void setTargetTags(final UUID player, final boolean any) {
        if (any) {
            targetTags.put(player, true);
        } else {
            targetTags.remove(player);
        }
    }

    /** 擊中時要不要看目標的狀態（屬性表裡有 vs_ 標籤的修飾器）。 */
    public boolean wantsTargetTags(final UUID player) {
        return targetTags.containsKey(player);
    }

    public void forget(final UUID player) {
        samples.remove(player);
        targetTags.remove(player);
        noted.remove(player);
    }

    /** 每次 tick 一開始：記下位置（算移動）與生命、魔力、護盾的比例。 */
    public void sample(final Player player, final PlayerState state, final long now) {
        final Sample sample = samples.computeIfAbsent(player.getUniqueId(), uuid -> new Sample());
        final Location at = player.getLocation();
        final String world = at.getWorld() == null ? "" : at.getWorld().getName();
        if (sample.tick != Long.MIN_VALUE && world.equals(sample.world) && now > sample.tick) {
            final double dx = at.getX() - sample.x;
            final double dz = at.getZ() - sample.z;
            sample.moving = isMoving(Math.sqrt(dx * dx + dz * dz), now - sample.tick);
        } else {
            sample.moving = false;
        }
        sample.world = world;
        sample.x = at.getX();
        sample.z = at.getZ();
        sample.tick = now;
        sample.lifeFraction = fraction(state.life(), state.maxLife());
        sample.manaFraction = fraction(state.mana(), state.maxMana());
        sample.es = state.energyShield();
        sample.maxEs = state.maxEnergyShield();
        sample.presenceRadius = tw.exilecore.combat.Presence.BASE_RADIUS
                * Math.sqrt(Math.max(0.1, 1 + state.sheet().increased(StatKeys.PRESENCE_AREA, Set.of())));
        sample.surroundedRadius = TreeConditions.SURROUNDED_RADIUS
                * Math.sqrt(Math.max(0.1, 1 + state.sheet().increased(tw.exilecore.stats.TreeLastKeys.SURROUNDED_AREA, Set.of()))); // 2026-10-27
        // 2026-10-26：生命藥劑還能不能喝（屬性表重算時不能去讀藥劑的全域詞綴，所以在這裡先看好）
        sample.noLifeFlask = plugin.flasks() != null && !plugin.flasks().hasUse(player, tw.exilecore.flask.FlaskKind.LIFE, state.sheet());
        // 2026-10-26：變身 → 變回人形（形態增益沒了）：記「最近變回人形」、照變身多久給法術傷害、用掉怒氣回生命
        final boolean shifted = plugin.skills() != null && plugin.skills().shapeshifted(player);
        if (shifted && !sample.shapeshifted) {
            sample.formSince = now;
        } else if (!shifted && sample.shapeshifted) {
            reverted(player, state, (now - sample.formSince) / 20.0, now);
        }
        sample.shapeshifted = shifted;
    }

    /** 變回人形（PoE2 Shapeshift to Human form）。 */
    private void reverted(final Player player, final PlayerState state, final double seconds, final long now) {
        state.markRecent(TreeConditions.REVERT_EVENT, now);
        if (plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
            plugin.skills().treeLeftovers().reverted(player, state.sheet(), seconds); // 2026-10-27 照 PoE2：變身夠久，變回人形時秘術增強
        }
        final tw.exilecore.stats.StatSheet sheet = state.sheet();
        final double perSecond = sheet.flat(tw.exilecore.stats.ConditionKeys.REVERT_SPELL_PER_SECOND, Set.of());
        if (perSecond > 0 && plugin.skills() != null) {
            final double total = revertSpellDamage(perSecond, seconds);
            if (total > 0) {
                plugin.skills().buff(player, "revert_spell", "變回人形", tw.exilecore.stats.ConditionKeys.REVERT_SPELL_TICKS,
                        java.util.List.of(new tw.exilecore.stats.Modifier(StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, total / 100.0,
                                Set.of(tw.exilecore.stats.Tags.SPELL), "變回人形")));
            }
        }
        final double perRage = sheet.flat(tw.exilecore.stats.ConditionKeys.REVERT_RAGE_LIFE, Set.of());
        if (perRage > 0 && plugin.skills() != null && plugin.skills().resources() != null) {
            final int rage = plugin.skills().resources().spendRage(player, plugin.skills().resources().rage(player));
            if (rage > 0) {
                state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * perRage * rage / 100.0));
            }
        }
    }

    /** 變回人形的法術傷害（%）：每秒 perSecond，最多 8 倍（PoE2：每秒 10%、最多 80%）。 */
    static double revertSpellDamage(final double perSecond, final double seconds) {
        return Math.min(perSecond * tw.exilecore.stats.ConditionKeys.REVERT_SPELL_CAP_FACTOR, perSecond * Math.floor(Math.max(0, seconds)));
    }

    // ---- 2026-10-26：事件（各系統發生時呼叫） ----

    /** 記一次計數事件（也記「最近…」）。 */
    public void note(final Player player, final String kind, final String id, final long now) {
        append(noted.computeIfAbsent(player.getUniqueId(), uuid -> new java.util.ArrayDeque<>()), new Noted(kind, id, now));
    }

    /** 一個玩家最多記幾筆事件。 */
    static final int NOTE_CAP = 256;

    /**
     * 加一筆事件：先丟掉 10 秒前的；滿了就丟掉記最多筆的那種事件裡最舊的一筆。
     * 2026-10-27：這次之前滿了新的直接不記，被打、中毒這種一秒好幾次的事件會把放法術、戰吼、彈藥的計數擠掉。
     */
    static void append(final java.util.ArrayDeque<Noted> log, final Noted entry) {
        while (!log.isEmpty() && entry.tick() - log.peekFirst().tick() > TEN_SECONDS) { // 2026-10-27：留 10 秒（彈藥種類）；這次之前 8 秒
            log.pollFirst();
        }
        if (log.size() >= NOTE_CAP) {
            final Map<String, Integer> counts = new HashMap<>();
            String most = entry.kind();
            for (final Noted one : log) {
                if (counts.merge(one.kind(), 1, Integer::sum) > counts.getOrDefault(most, 0)) {
                    most = one.kind();
                }
            }
            for (final java.util.Iterator<Noted> it = log.iterator(); it.hasNext(); ) {
                if (it.next().kind().equals(most)) {
                    it.remove();
                    break;
                }
            }
        }
        log.addLast(entry);
    }

    /** 這段時間內這種事件幾次（或幾種）。 */
    static int counted(final java.util.Collection<Noted> log, final String kind, final long window, final boolean distinct, final long now) {
        final Set<String> seen = new java.util.HashSet<>();
        int count = 0;
        for (final Noted one : log) {
            if (one.kind().equals(kind) && now - one.tick() <= window && (!distinct || seen.add(one.id()))) {
                count++;
            }
        }
        return count;
    }

    /** 門檻條件的數量。 */
    private int count(final Player player, final Sample sample, final String base, final long now) {
        final tw.exilecore.skill.SkillService skills = plugin.skills();
        final double presence = sample == null ? tw.exilecore.combat.Presence.BASE_RADIUS : sample.presenceRadius;
        return switch (base) {
            case TreeConditions.RAGE_AT_LEAST -> skills == null || skills.resources() == null ? 0 : skills.resources().rage(player);
            case TreeConditions.TOTEMS_AT_LEAST -> skills == null ? 0 : skills.totemCount(player);
            case TreeConditions.MINIONS_AT_LEAST -> skills == null || skills.minions() == null ? 0 : skills.minions().countNear(player, presence);
            case TreeConditions.CLOSE_ENEMIES_AT_LEAST -> enemiesNear(player, StatKeys.CLOSE_RANGE, false);
            default -> {
                final java.util.ArrayDeque<Noted> log = noted.get(player.getUniqueId());
                yield log == null ? 0 : counted(log, base, WINDOWS.getOrDefault(base, StatKeys.RECENTLY_TICKS), DISTINCT.contains(base), now);
            }
        };
    }

    private PlayerState stateOf(final Player player) {
        return plugin.players().get(player);
    }

    /** 放了一個技能（SkillService 放完時）：最近攻擊過、法術種類、印記、混沌技能的代價。 */
    public void onSkillUsed(final Player player, final String skillId, final Set<String> tags, final boolean attack, final boolean instant,
                            final long now) {
        final PlayerState state = stateOf(player);
        if (attack) {
            state.markRecent(TreeConditions.ATTACK_EVENT, now);
            if (!instant) {
                note(player, TreeConditions.SLOW_ATTACK_TYPES, skillId, now);
            }
        } else if (tags.contains(tw.exilecore.stats.Tags.SPELL)) {
            note(player, TreeConditions.SPELL_TYPES, skillId, now);
            if (!instant) {
                note(player, TreeConditions.SLOW_SPELL_TYPES, skillId, now);
            }
        }
        if (tags.contains(tw.exilecore.stats.Tags.MARK)) {
            state.markRecent(TreeConditions.MARK_EVENT, now);
        }
        if (tags.contains(tw.exilecore.stats.Tags.GRENADE)) {
            // 2026-10-27 照 PoE2 天賦樹：過去 8 秒丟過幾種榴彈、過去 10 秒射過幾種彈藥（榴彈也是弩的彈藥）
            note(player, TreeConditions.GRENADE_TYPES, skillId, now);
            note(player, TreeConditions.AMMO_TYPES, skillId, now);
        }
        if (tags.contains(tw.exilecore.stats.Tags.CHAOS)) {
            final double lose = state.sheet().flat(tw.exilecore.stats.ConditionKeys.CHAOS_SKILL_SELF_DAMAGE, Set.of());
            if (lose > 0) {
                state.setLife(Math.max(1, state.life() - state.maxLife() * lose / 100.0));
                state.setEnergyShield(Math.max(0, state.energyShield() - state.maxEnergyShield() * lose / 100.0));
            }
        }
    }

    /** 戰吼。 */
    public void onWarcry(final Player player, final String skillId, final long now) {
        note(player, TreeConditions.WARCRIES, skillId, now);
        note(player, TreeConditions.WARCRY_TYPES, skillId, now);
    }

    /** 變成獸形。 */
    public void onShapeshift(final Player player, final long now) {
        stateOf(player).markRecent(TreeConditions.SHAPESHIFT_EVENT, now);
    }

    /** 觸發了一個技能（主要寶石、輔助的觸發）。 */
    public void onTriggered(final Player player, final long now) {
        stateOf(player).markRecent(TreeConditions.TRIGGERED_EVENT, now);
    }

    /** 自己的召喚物死掉。 */
    public void onMinionDied(final Player owner, final long now) {
        stateOf(owner).markRecent(TreeConditions.MINION_DIED, now);
    }

    /** 讓敵人中了元素異常（冰緩、冰凍、感電、點燃）。 */
    public void onAilment(final Player player, final org.bukkit.entity.LivingEntity target, final tw.exilecore.ailment.AilmentType type,
                          final long now) {
        final PlayerState state = stateOf(player);
        switch (type) {
            case CHILL -> state.markRecent(TreeConditions.CHILLED_ENEMY, now);
            case FREEZE -> {
                state.markRecent(TreeConditions.CHILLED_ENEMY, now); // 冰凍的也算冰緩過
                note(player, TreeConditions.FROZEN_ENEMIES, target.getUniqueId().toString(), now);
            }
            case SHOCK -> state.markRecent(TreeConditions.SHOCKED_ENEMY, now);
            case IGNITE -> {
                state.markRecent(TreeConditions.IGNITED_ENEMY, now);
                note(player, TreeConditions.IGNITED_ENEMIES, target.getUniqueId().toString(), now);
            }
            default -> {
            }
        }
    }

    /** 釘住了敵人。 */
    public void onPinned(final Player player, final long now) {
        stateOf(player).markRecent(TreeConditions.PINNED_ENEMY, now);
    }

    /** 消耗了一顆戰星（銳星、怒星、磐星）：計數與回復。 */
    public void onChargeSpent(final Player player, final String chargeId, final long now) {
        final PlayerState state = stateOf(player);
        if (plugin.skills() != null) {
            plugin.skills().resources().onChargeConsumed(player); // 2026-10-26 照 PoE2 亞馬遜：消耗戰星時觸發元素湧動
        }
        final tw.exilecore.stats.StatSheet sheet = state.sheet();
        if ("power".equals(chargeId)) {
            final double mana = sheet.flat(tw.exilecore.stats.ConditionKeys.MANA_ON_POWER_SPENT, Set.of());
            if (mana > 0) {
                state.setMana(Math.min(state.maxMana(), state.mana() + state.maxMana() * mana / 100.0));
            }
        } else if ("endurance".equals(chargeId)) {
            note(player, TreeConditions.ENDURANCE_SPENT, chargeId, now);
            final double life = sheet.flat(tw.exilecore.stats.ConditionKeys.LIFE_PER_ENDURANCE_SPENT, Set.of());
            if (life > 0) {
                state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * life / 100.0));
            }
        }
    }

    /** 獲得（或刷新）一顆戰星。 */
    public void onChargeGained(final Player player, final String chargeId, final long now) {
        if ("power".equals(chargeId)) {
            stateOf(player).markRecent(TreeConditions.POWER_GAIN, now);
        }
    }

    /** 消耗了元素灌注：最近消耗過灌注；有天賦的給一顆銳星。 */
    public void onInfusionConsumed(final Player player, final int count, final long now) {
        final PlayerState state = stateOf(player);
        state.markRecent(TreeConditions.INFUSION_EVENT, now);
        if (count > 0 && StatKeys.flag(state.sheet(), tw.exilecore.stats.ConditionKeys.POWER_ON_INFUSION) && plugin.combat() != null
                && plugin.combat().charges() != null) {
            plugin.combat().charges().grant(player, "power", count); // PoE2：每消耗一個灌注一顆
        }
    }

    /** 被重暈：計數；有天賦的 1 秒內回復生命。 */
    public void onStunned(final Player player, final long now) {
        note(player, TreeConditions.STUNS, "stun", now);
        final PlayerState state = stateOf(player);
        final double life = state.sheet().flat(tw.exilecore.stats.ConditionKeys.LIFE_ON_STUNNED, Set.of());
        if (life > 0 && plugin.skills() != null) {
            plugin.skills().buff(player, "stun_regen", "重暈回復", 20,
                    java.util.List.of(tw.exilecore.stats.Modifier.flat(StatKeys.LIFE_REGEN_PERCENT, life, "重暈回復")));
        }
    }

    /** 格擋了一擊：舉盾中算「舉盾擋下」，招架中算「成功招架」。 */
    public void onBlocked(final Player player, final boolean raised, final boolean parried, final long now) {
        final PlayerState state = stateOf(player);
        if (raised) {
            state.markRecent(TreeConditions.ACTIVE_BLOCK_EVENT, now);
        }
        if (parried) {
            state.markRecent(TreeConditions.PARRIED_EVENT, now);
        }
    }

    /** 喝了藥劑。 */
    public void onFlask(final Player player, final tw.exilecore.flask.FlaskKind kind, final long now) {
        stateOf(player).markRecent(kind == tw.exilecore.flask.FlaskKind.LIFE ? TreeConditions.LIFE_FLASK_USED : TreeConditions.MANA_FLASK_USED, now);
    }

    /** 這段時間走了這麼遠算不算移動中。 */
    static boolean isMoving(final double distance, final long ticks) {
        return ticks > 0 && distance / ticks * 20 >= MOVING_SPEED;
    }

    private static double fraction(final double current, final double max) {
        return max <= 0 ? 1 : current / max;
    }

    /** 這個狀態條件（不含 no 前綴）現在成不成立。 */
    public boolean test(final Player player, final PlayerState state, final String condition, final long now) {
        final BiPredicate<Player, PlayerState> provider = providers.get(condition);
        if (provider != null) {
            return provider.test(player, state);
        }
        if (TreeConditions.RESERVED.contains(condition)) {
            return false;
        }
        final Sample sample = samples.get(player.getUniqueId());
        final TreeConditions.Counted counted = TreeConditions.counted(condition);
        if (counted != null) {
            return count(player, sample, counted.base(), now) >= counted.at();
        }
        final double presence = sample == null ? tw.exilecore.combat.Presence.BASE_RADIUS : sample.presenceRadius;
        final tw.exilecore.skill.SkillService skills = plugin.skills();
        return switch (condition) {
            // 2026-10-26：變身、夥伴、衝刺、招架、舉盾、先鋒、圖騰…
            case TreeConditions.SHAPESHIFTED -> skills != null && skills.shapeshifted(player);
            case TreeConditions.COMPANION -> skills != null && skills.minions() != null && skills.minions().companionNear(player, presence);
            case TreeConditions.SPRINTING -> plugin.combat() != null && plugin.combat().dodge() != null && plugin.combat().dodge().sprinting(player)
                    && sample != null && sample.moving;
            case TreeConditions.ROLLING -> plugin.combat() != null && plugin.combat().dodge() != null && plugin.combat().dodge().rolling(player);
            case TreeConditions.RAISING_SHIELD -> skills != null && skills.buffActivePrefix(player, GUARD_BUFF);
            case TreeConditions.PARRYING -> skills != null && skills.buffHasStat(player, GUARD_BUFF, StatKeys.FLAG_PARRY);
            case TreeConditions.CHARM_ACTIVE -> skills != null && skills.buffActivePrefix(player, CHARM_BUFF);
            case TreeConditions.HERALD -> skills != null && (skills.auraActive(player, "herald_of_ash") || skills.auraActive(player, "herald_of_ice")
                    || skills.auraActive(player, "herald_of_thunder") || skills.auraActive(player, "herald_of_plague")
                    || skills.auraActive(player, "herald_of_blood"));
            case TreeConditions.HERALD_ASH -> skills != null && skills.auraActive(player, "herald_of_ash");
            case TreeConditions.HERALD_ICE -> skills != null && skills.auraActive(player, "herald_of_ice");
            case TreeConditions.HERALD_THUNDER -> skills != null && skills.auraActive(player, "herald_of_thunder");
            case TreeConditions.HERALD_PLAGUE -> skills != null && skills.auraActive(player, "herald_of_plague");
            case TreeConditions.HERALD_BLOOD -> skills != null && skills.auraActive(player, "herald_of_blood");
            case TreeConditions.TOTEM -> skills != null && skills.totemCount(player) > 0;
            case TreeConditions.IGNITED -> plugin.ailments() != null && plugin.ailments().has(player, tw.exilecore.ailment.AilmentType.IGNITE);
            case TreeConditions.CHILLED -> plugin.ailments() != null && plugin.ailments().has(player, tw.exilecore.ailment.AilmentType.CHILL);
            case TreeConditions.DAMAGING_AILMENT -> plugin.ailments() != null && (plugin.ailments().has(player, tw.exilecore.ailment.AilmentType.IGNITE)
                    || plugin.ailments().has(player, tw.exilecore.ailment.AilmentType.BLEED)
                    || plugin.ailments().has(player, tw.exilecore.ailment.AilmentType.POISON));
            case TreeConditions.FULL_MANA -> fullLife(sample == null ? 1 : sample.manaFraction);
            case TreeConditions.LIFE_LEECHING -> state.leechPool() > 0;
            case TreeConditions.MANA_LEECHING -> state.manaLeechPool() > 0;
            case TreeConditions.NO_LIFE_FLASK -> sample != null && sample.noLifeFlask;
            case TreeConditions.WIELD_AXE -> hasTag(state, MAIN_HAND, tw.exilecore.stats.Tags.AXE);
            case TreeConditions.WIELD_QUARTERSTAFF -> hasTag(state, MAIN_HAND, tw.exilecore.stats.Tags.QUARTERSTAFF);
            case TreeConditions.NO_ENEMY_NEAR -> enemiesNear(player, NO_ENEMY_RADIUS, false) == 0;
            case TreeConditions.LIFE_FLASK_10S -> now - state.recentTick(TreeConditions.LIFE_FLASK_USED) <= LIFE_FLASK_WINDOW;
            case TreeConditions.MOVING -> sample != null && sample.moving;
            case TreeConditions.STATIONARY -> sample == null || !sample.moving;
            case TreeConditions.LOW_LIFE -> lowLife(sample == null ? 1 : sample.lifeFraction);
            case TreeConditions.FULL_LIFE -> fullLife(sample == null ? 1 : sample.lifeFraction);
            case TreeConditions.LOW_MANA -> lowLife(sample == null ? 1 : sample.manaFraction);
            case TreeConditions.FULL_ES -> sample != null && sample.maxEs > 0 && sample.es >= sample.maxEs - 1e-6;
            case TreeConditions.HAS_ES -> sample != null && sample.es > 0;
            // 2026-10-27 照 PoE2：低能量護盾（35% 以下；沒有護盾也算）
            case TreeConditions.LOW_ES -> sample == null || sample.maxEs <= 0 || sample.es <= sample.maxEs * TreeConditions.LOW_FRACTION + 1e-9;
            case TreeConditions.LEECHING -> state.leechPool() > 0 || state.manaLeechPool() > 0;
            case TreeConditions.CHANNELLING -> now - state.recentTick(TreeConditions.CHANNEL_EVENT) <= TreeConditions.CHANNEL_GRACE;
            case TreeConditions.BLEEDING -> plugin.ailments() != null && plugin.ailments().has(player, tw.exilecore.ailment.AilmentType.BLEED);
            case TreeConditions.FROZEN -> plugin.ailments() != null && plugin.ailments().isFrozen(player);
            case TreeConditions.ANY_FLASK -> plugin.flasks() != null && (plugin.flasks().recovering(player, tw.exilecore.flask.FlaskKind.LIFE)
                    || plugin.flasks().recovering(player, tw.exilecore.flask.FlaskKind.MANA));
            case TreeConditions.DUAL_WIELD -> weapon(state, MAIN_HAND) && weapon(state, OFF_HAND)
                    && !hasTag(state, OFF_HAND, "sceptre"); // 2026-10-27：荒野領主副手的權杖不算雙持（權杖不能攻擊）
            case TreeConditions.SHIELD -> hasTag(state, OFF_HAND, "shield");
            case TreeConditions.MELEE_WEAPON -> weapon(state, MAIN_HAND) && !hasTag(state, MAIN_HAND, "ranged");
            case TreeConditions.SURROUNDED -> enemiesNear(player, sample == null ? TreeConditions.SURROUNDED_RADIUS : sample.surroundedRadius, false)
                    >= TreeConditions.SURROUNDED_COUNT; // 2026-10-27：範圍吃「增加被包圍的範圍」
            case TreeConditions.RARE_NEARBY -> enemiesNear(player, sample == null ? tw.exilecore.combat.Presence.BASE_RADIUS : sample.presenceRadius, true) > 0;
            case TreeConditions.ALLY -> allyNear(player, sample == null ? tw.exilecore.combat.Presence.BASE_RADIUS : sample.presenceRadius);
            default -> state.recently(condition, now);
        };
    }

    static boolean lowLife(final double fraction) {
        return fraction <= TreeConditions.LOW_FRACTION + 1e-9;
    }

    static boolean fullLife(final double fraction) {
        return fraction >= 0.999;
    }

    private tw.exilecore.item.ItemBase base(final PlayerState state, final int index) {
        final ItemStack stack = state.jewellery(index);
        if (stack == null || stack.isEmpty() || plugin.items() == null) {
            return null;
        }
        return plugin.items().base(stack); // 2026-10-27 效能：只讀基底代號；這次之前每次判斷都整份解 JSON、做詞綴換算
    }

    private boolean weapon(final PlayerState state, final int index) {
        final tw.exilecore.item.ItemBase base = base(state, index);
        return base != null && base.isWeapon();
    }

    private boolean hasTag(final PlayerState state, final int index, final String tag) {
        final tw.exilecore.item.ItemBase base = base(state, index);
        return base != null && base.tags().contains(tag);
    }

    /** 附近的敵人（怪物）數；rareOnly 只算稀有、傳奇、Boss。 */
    private int enemiesNear(final Player player, final double radius, final boolean rareOnly) {
        if (plugin.monsters() == null) {
            return 0;
        }
        int count = 0;
        final Location at = player.getLocation();
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(at, radius)) {
            if (entity instanceof Player || entity.isDead() || !entity.isValid() || entity.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            final tw.exilecore.monster.MonsterData data = plugin.monsters().data(entity).orElse(null);
            if (data == null || plugin.combat() != null && plugin.combat().minions() != null && plugin.combat().minions().owner(entity) != null) {
                continue;
            }
            if (rareOnly && !(data.boss() || data.rarity() == tw.exilecore.monster.MonsterRarity.RARE
                    || data.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** 臨在範圍裡有沒有盟友：自己的召喚物、同隊的玩家。 */
    private boolean allyNear(final Player player, final double radius) {
        final Location at = player.getLocation();
        // 2026-10-27 照 PoE2：你的圖騰也是你的友軍（這次之前只看隊友、召喚物）
        if (plugin.skills() != null) {
            for (final tw.exilecore.skill.SkillService.TotemView totem : plugin.skills().totems(player.getUniqueId())) {
                if (totem.feet().getWorld() == at.getWorld() && totem.feet().distanceSquared(at) <= radius * radius) {
                    return true;
                }
            }
        }
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(at, radius)) {
            if (entity == player || entity.isDead() || !entity.isValid() || entity.getLocation().distanceSquared(at) > radius * radius) {
                continue;
            }
            if (entity instanceof Player other) {
                if (plugin.party() != null && plugin.party().sameGroup(player.getUniqueId(), other.getUniqueId())) {
                    return true;
                }
                continue;
            }
            if (plugin.combat() != null && plugin.combat().minions() != null) {
                final Player owner = plugin.combat().minions().owner(entity);
                if (owner != null && owner.getUniqueId().equals(player.getUniqueId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
