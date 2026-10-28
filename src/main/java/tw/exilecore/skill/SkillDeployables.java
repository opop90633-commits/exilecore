package tw.exilecore.skill;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 放在地上的東西（圖騰、陷阱、鐘）：一個盔甲座當身體（頭上戴個東西、名字寫著是誰的），不會被怪物打、不會被玩家拿走。
 * 沒有血量：只照持續時間存在（圖騰、鐘的名字後面寫著還剩幾秒）。
 * <ul>
 *   <li>圖騰：站著，每隔一段時間對範圍內最近的敵人放一次技能（從圖騰的位置、朝那隻），時間到了消失；在的時候可以給施放者增益。</li>
 *   <li>陷阱：丟到準心指的地方，過一下子才會觸發；敵人靠近就從陷阱的位置朝那隻放一次技能，然後消失。</li>
 *   <li>鐘：放在面前；施放者在鐘旁邊打中敵人，鐘就響一次，打周圍一圈（用放鐘那個技能的傷害），響夠次數或時間到了消失。</li>
 * </ul>
 * 傷害都算施放者的。施放者下線、換世界就收掉；每個技能同時最多幾個照設定（多的收最舊的）。
 */
final class SkillDeployables implements Listener {

    /** 鐘：排隊等著響的（打中事件裡不直接打，下一 tick 再打，免得傷害事件套傷害事件）。 */
    private record Ring(Deployed bell, long atTick) {
    }

    /** 一個放在地上的東西。 */
    static final class Deployed {
        final UUID owner;
        final SkillBuild build;
        final DeploySpec spec;
        final ArmorStand stand;
        /** 頭上的名字與剩幾秒（陷阱沒有）：盔甲座沒有碰撞箱，它自己的名字會畫在腳邊，所以另外放一個文字顯示在頭上。 */
        TextDisplay tag;
        final Location feet;
        final long placedTick;
        final long untilTick;
        /** 名字（技能名稱與施放者），後面接剩幾秒。 */
        final String label;
        /** 名字上現在寫的秒數（變了才改名字）。 */
        int shownSeconds = -1;
        long nextTick;
        int ringsLeft;
        boolean armed;
        // 2026-10：圖騰的生命（替身圖騰引來的怪會打它）、特殊圖騰（替身、焚鏈、引爆）、怨燼
        double maxLife;
        double life;
        boolean taunt;
        boolean bond;
        boolean detonate;
        double vengeful;
        /** 2026-10-26：圖騰的元素、混沌抗性（%）。 */
        double resistElemental;
        double resistChaos;
        long vengefulReady;
        /** 2026-10-26（昇華）：生命降到 0 的 tick（無名心木：之後幾秒才死）、看過幾次「發射攻擊投射物」（砲彈準備好了！）。 */
        long deadAt;
        long cannonsSeen = -1;
        final Map<UUID, Long> attacks = new HashMap<>();
        /** 2026-10-26 照 PoE2 先祖魂約：這根圖騰保留精魂（放的時候有那顆基石）。 */
        boolean spiritBond;
        /** 2026-10-26 照 PoE2 天賦：圖騰每秒回復最大生命的 x%。 */
        double regen;
        /** 2026-10-26 照 PoE2「砲彈準備好了！」：這根圖騰只在你發射攻擊投射物時用技能；上一次用掉的是哪一次發射（tick）。 */
        boolean onProjectile;
        long usedFire;
        /** 2026-10-26 照 PoE2：圖騰的護甲（召喚物表的護甲 +100%）。 */
        double armour;
        /** 2026-10-27：圖騰的物理傷害減免 %（天賦「圖騰物理傷害減免」，physical.reduction 帶 totem 標籤）；以前沒作用。 */
        double physReduction;
        /** 怨燼的基礎傷害照的生命（舊的算法：你最大生命的 30%～60%；圖騰生命改成照原作之後怨燼不跟著變強）。 */
        double vengefulLife;
        /** 2026-10-28 照 PoE2 Tempest Bell：鐘身上的元素異常（哪一種元素 → 到哪個 tick）、凍結累積、下一次震波的範圍倍率（擊退）。 */
        final Map<tw.exilecore.stats.DamageType, Long> ailed = new java.util.EnumMap<>(tw.exilecore.stats.DamageType.class);
        double freezeBuild;
        double ringArea = 1;

        Deployed(final UUID owner, final SkillBuild build, final DeploySpec spec, final ArmorStand stand, final Location feet,
                 final long now, final long untilTick, final String label) {
            this.owner = owner;
            this.build = build;
            this.spec = spec;
            this.stand = stand;
            this.feet = feet;
            this.placedTick = now;
            this.untilTick = untilTick;
            this.label = label;
            this.nextTick = now + (spec.kind() == DeploySpec.Kind.TOTEM ? 10 : 0);
            this.ringsLeft = spec.rings();
            this.armed = spec.kind() != DeploySpec.Kind.TRAP;
        }

        String skillId() {
            return build.definition().id();
        }

        /** 英靈圖騰（2026-10）：英靈用的錘子技能與幾 tick 叫一次；null = 照圖騰自己的技能放。 */
        SkillBuild linked;
        long linkedTicks;
        /**
         * 2026-10-27 照 PoE2（Spell Totem「uses socketed Spells」、Ancestral Warrior Totem「Each warrior uses a socketed Mace Skill」）：
         * 孔裡第 2 個起的技能（星咒圖騰的法術、英靈圖騰的錘子技能），跟 build／linked 照孔的順序輪流用；這次之前只用第 1 孔。
         */
        List<SkillBuild> moreCasts = List.of();
        List<SkillBuild> moreLinked = List.of();
        /** 2026-10-27：輪到第幾個（每用一次加 1）。 */
        int turn;
        /**
         * 2026-10-27 照 PoE2 Splinter Totem：這根分裂過幾次、最多幾次；分裂前的技能（照它算每次更少）、沒分裂時的最大生命、
         * 每分裂一次更少幾 %（傷害、生命、範圍、技能速度）。
         */
        int splinters;
        int maxSplinters;
        SkillBuild unsplit;
        SkillBuild unsplitLinked;
        List<SkillBuild> unsplitCasts = List.of();
        List<SkillBuild> unsplitCalls = List.of();
        double unsplitLife;
        double[] splinterLess = new double[4];
    }

    /** 2026-10-27：輪流用的第 turn 個（0 是 first，之後照 more 的順序）。 */
    static <T> T rotate(final T first, final List<T> more, final int turn) {
        final int size = 1 + more.size();
        final int index = Math.floorMod(turn, size);
        return index == 0 ? first : more.get(index - 1);
    }

    /** 圖騰攻擊間隔的下限（tick）：攻速再高也不會比這個快。 */
    static final int TOTEM_MIN_TICKS = 4;

    /**
     * 圖騰下一次攻擊隔幾 tick（2026-10-20，照 PoE）：技能的 interval ÷ 放圖騰的人的速度倍率——攻擊技能看攻擊速度、
     * 法術看施法速度（增加 × 更多，含這個技能的輔助寶石）；不含武器本身的攻速（interval 已經是圖騰自己的節奏）。
     * 英靈圖騰另外算（{@link #ancestorTicks}，2026-10-21）。
     */
    static long totemTicks(final double intervalSeconds, final double speedMultiplier) {
        final double speed = Math.max(0.1, speedMultiplier);
        return Math.max(TOTEM_MIN_TICKS, Math.round(intervalSeconds * 20 / speed));
    }

    /**
     * 2026-10-26 照 PoE2：一般圖騰（含星咒圖騰）下一次放技能隔幾 tick。totemsUp：你現在召喚中的圖騰數（星咒圖騰品質「每根圖騰增加施法速度」用）。
     * 崩震圖騰（{@link DeploySpec#weaponSpeed}）照你的武器攻速 × 技能的攻擊速度（50%）÷ 攻速加成，不照 interval。
     */
    static long totemTicks(final SkillBuild build, final tw.exilecore.stats.StatSheet sheet, final int totemsUp) {
        final DeploySpec spec = build.deploy();
        final double speed = totemSpeed(build, sheet, false, totemsUp);
        if (spec.weaponSpeed() && build.isAttack()) {
            final double weaponAps = build.profile().effective(sheet).flat(tw.exilecore.stats.StatKeys.WEAPON_APS, build.profile().tags());
            return weaponTotemTicks(weaponAps, build.definition().attackSpeed(), speed);
        }
        return totemTicks(spec.interval(), speed);
    }

    /** 2026-10-26 照 PoE2（崩震圖騰）：間隔 = 1 ÷（武器攻速 × 技能的攻擊速度）÷ 攻速加成；沒有武器攻速當 1 次／秒。 */
    static long weaponTotemTicks(final double weaponAps, final double skillAttackSpeed, final double totemSpeed) {
        final double aps = (weaponAps <= 0 ? 1.0 : weaponAps) * Math.max(0.05, skillAttackSpeed);
        return totemTicks(1.0 / aps, totemSpeed);
    }

    /** 英靈（2026-10-21）：叫一次英靈要等那個錘子技能攻擊時間的幾倍。 */
    static final double ANCESTOR_ATTACK_TIMES = 1.5;

    /**
     * 英靈圖騰叫英靈的間隔（2026-10-21）：那個錘子技能的攻擊時間（武器基礎攻速 × 技能自己的攻擊速度，例如千鈞砸 45%）× 1.5，
     * 再除以放圖騰的人的攻速（{@link #totemSpeed}：英靈圖騰的輔助算，錘子技能自己的輔助不算）。越慢越重的技能叫得越慢。
     */
    static long ancestorTicks(final double weaponAps, final double skillAttackSpeed, final double totemSpeed) {
        final double aps = (weaponAps <= 0 ? 1.0 : weaponAps) * Math.max(0.05, skillAttackSpeed);
        return totemTicks(ANCESTOR_ATTACK_TIMES / aps, totemSpeed);
    }

    static long ancestorTicks(final SkillBuild totem, final SkillBuild linked, final tw.exilecore.stats.StatSheet sheet) {
        final double weaponAps = linked.profile().effective(sheet).flat(tw.exilecore.stats.StatKeys.WEAPON_APS, linked.profile().tags());
        return ancestorTicks(weaponAps, linked.definition().attackSpeed(), totemSpeed(totem, sheet, true));
    }

    private double totemSpeed(final Deployed item, final Player owner) {
        return totemSpeed(item.build, plugin.players().sheet(owner), item.linked != null);
    }

    /** 圖騰的速度倍率：攻擊（含英靈圖騰）看攻擊速度、法術看施法速度；用圖騰那個技能自己的輔助。 */
    static double totemSpeed(final SkillBuild totem, final tw.exilecore.stats.StatSheet sheet, final boolean linked) {
        return totemSpeed(totem, sheet, linked, 0);
    }

    /**
     * 同上；totemsUp：你現在召喚中的圖騰數——2026-10-26 照 PoE2 Spell Totem 的品質「每根召喚中的圖騰，圖騰施放的法術增加 x% 施法速度」
     * （{@link tw.exilecore.stats.TotemKeys#CAST_SPEED_PER_TOTEM}，跟其他增加的施法速度相加）。
     */
    static double totemSpeed(final SkillBuild totem, final tw.exilecore.stats.StatSheet sheet, final boolean linked, final int totemsUp) {
        tw.exilecore.stats.StatSheet effective = totem.profile().effective(sheet);
        final Set<String> tags = totem.profile().tags();
        final double perTotem = effective.flat(tw.exilecore.stats.TotemKeys.CAST_SPEED_PER_TOTEM, tags);
        if (!linked && !totem.isAttack() && perTotem > 0 && totemsUp > 0) {
            effective = effective.overlay(List.of(tw.exilecore.stats.Modifier.increased(tw.exilecore.stats.StatKeys.CAST_SPEED,
                    perTotem * totemsUp / 100.0, "品質")));
        }
        return effective.apply(linked || totem.isAttack() ? tw.exilecore.stats.StatKeys.ATTACK_SPEED : tw.exilecore.stats.StatKeys.CAST_SPEED,
                1.0, tags);
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillExecutor executor;
    private final SkillDamage damage;
    private final NamespacedKey key;
    private final List<Deployed> deployed = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    /** 被替身圖騰引過去的怪 → 那根圖騰（2026-10）。 */
    private final Map<UUID, Deployed> decoyed = new HashMap<>();
    /** Boss 被替身圖騰嘲諷（2026-10）：開始的 tick（嘲諷 BOSS_TAUNT_TICKS 後放掉）、免疫到哪個 tick。 */
    private final Map<UUID, Long> bossTauntStart = new HashMap<>();
    private final Map<UUID, Long> bossImmuneUntil = new HashMap<>();
    /** Boss 每次最多被嘲諷 4 秒，之後 8 秒不會再被嘲諷；Boss 打圖騰的傷害 ×3。 */
    static final long BOSS_TAUNT_TICKS = 80;
    static final long BOSS_IMMUNE_TICKS = 160;
    /**
     * 2026-10-26 照 PoE2：Boss 打圖騰不再另外 ×3（原作沒有；圖騰生命改成照原作的召喚物表之後也不需要）。這次之前 3.0。
     */
    static final double BOSS_TOTEM_DAMAGE = 1.0;
    /** 圖騰種類的生命倍率（PoE2 SkillTotemVariations：英靈圖騰 120%，其他 100%）。 */
    static final double ANCESTRAL_TOTEM_LIFE = 1.2;

    /**
     * 2026-10-26 照 PoE2（Path of Building：TotemLife = monsterAllyLifeTable[寶石等級 × 2] × 圖騰種類倍率 × 圖騰生命加成；
     * 圖騰護甲 +100%）：{生命, 護甲}，用召喚物同一套換算成我們的單位（{@link MinionSheets#poe2Factors}）。
     * 這次之前圖騰生命 = 你最大生命 × {@link #totemLifeFactor}，沒有護甲。
     */
    static double[] totemBase(final SkillConfig config, final int skillLevel, final int ownerLevel,
                              final java.util.function.IntToDoubleFunction ourLife, final java.util.function.IntFunction<double[]> ourDamage,
                              final double lifeMultiplier) {
        final double[] factors = MinionSheets.poe2Factors(config, skillLevel, ownerLevel, ourLife, ourDamage);
        final int level = MinionSheets.allyLevel(skillLevel);
        final double scale = MinionSheets.extraScale(config, skillLevel);
        return new double[] {MinionSheets.poe2AllyLife(level) * factors[1] * lifeMultiplier,
                2 * MinionSheets.poe2AllyArmour(level) * factors[1] / Math.max(1e-9, scale)};
    }

    /**
     * 舊的圖騰生命倍率（只剩怨燼的傷害用）：施放者最大生命 × 這個。
     * 2026-10-23：照技能等級（裝備加的等級也算）：Lv.1 30%、Lv.20 60%，之後照樣往上，最多 100%（以前固定 50%）。
     */
    static double totemLifeFactor(final int skillLevel) {
        final double t = (Math.max(1, skillLevel) - 1) / 19.0;
        return Math.min(TOTEM_LIFE_MAX, TOTEM_LIFE_AT_1 + (TOTEM_LIFE_AT_20 - TOTEM_LIFE_AT_1) * t);
    }

    static final double TOTEM_LIFE_AT_1 = 0.3;
    static final double TOTEM_LIFE_AT_20 = 0.6;
    static final double TOTEM_LIFE_MAX = 1.0;

    SkillDeployables(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillExecutor executor,
                     final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.executor = executor;
        this.damage = damage;
        this.key = new NamespacedKey(plugin, "deployable");
        targets.addStrikeable(this::struck); // 2026-10-25：近戰攻擊直接打到鐘也會響
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個玩家有沒有放著某個技能的東西（說明用）。 */
    int count(final UUID owner, final String skillId) {
        int count = 0;
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner) && item.skillId().equals(skillId)) {
                count++;
            }
        }
        return count;
    }

    /** 2026-10-26：這個玩家放著的圖騰在哪裡（天賦「每根圖騰讓存在範圍內的敵人受到的傷害增加」）。 */
    List<Location> totemSpots(final UUID owner) {
        final List<Location> spots = new ArrayList<>();
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner) && item.spec.kind() == DeploySpec.Kind.TOTEM) {
                spots.add(item.feet);
            }
        }
        return spots;
    }

    /** 2026-10-26（天賦樹「每個圖騰」）：這個玩家放著幾個圖騰（任何技能）。 */
    int totems(final UUID owner) {
        int count = 0;
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner) && item.spec.kind() == DeploySpec.Kind.TOTEM) {
                count++;
            }
        }
        return count;
    }

    /** 圖騰給施放者的增益（屬性表的來源）。 */
    List<Modifier> modifiers(final Player player) {
        List<Modifier> list = null;
        final java.util.Set<String> counted = new java.util.HashSet<>();
        int bonded = 0;
        for (final Deployed item : deployed) {
            if (item.owner.equals(player.getUniqueId()) && item.spiritBond) {
                bonded++;
            }
        }
        if (bonded > 0) {
            // 2026-10-26 照 PoE2 先祖魂約：每個圖騰保留 75 精魂（這次之前沒有保留，反而讓圖騰技能不花魔力）
            list = new ArrayList<>();
            list.add(Modifier.flat(tw.exilecore.stats.StatKeys.SPIRIT_RESERVED, bonded * tw.exilecore.stats.TotemKeys.ANCESTRAL_BOND_SPIRIT, "先祖魂約"));
        }
        for (final Deployed item : deployed) {
            if (item.owner.equals(player.getUniqueId()) && item.spec.kind() == DeploySpec.Kind.TOTEM && !item.spec.buff().isEmpty()
                    && counted.add(item.skillId())) { // 同一個技能放兩根也只算一次
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.addAll(item.spec.buff());
            }
        }
        return list == null ? List.of() : list;
    }

    /** 放下去（圖騰放面前 1.5 格、鐘放面前 2 格、陷阱丟到準心指的地方）。 */
    void place(final Player player, final SkillBuild build) {
        place(player, build, null, 0);
    }

    /** 2026-10-26（昇華：堅木之壘、堤防光環）：這個玩家放著的圖騰。 */
    List<SkillService.TotemView> totemViews(final UUID owner) {
        final List<SkillService.TotemView> out = new ArrayList<>();
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner) && item.spec.kind() == DeploySpec.Kind.TOTEM && item.life > 0) {
                out.add(new SkillService.TotemView(item.feet.clone(), item.skillId(), item.life, item.maxLife));
            }
        }
        return out;
    }

    /** 2026-10-26（堅木之壘）：離 near 最近的圖騰扣 amount 生命，回傳扣了多少。 */
    double drainNearest(final UUID owner, final Location near, final double amount) {
        Deployed best = null;
        double distance = Double.MAX_VALUE;
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner) && item.spec.kind() == DeploySpec.Kind.TOTEM && item.life > 0
                    && item.feet.getWorld() == near.getWorld()) {
                final double d = item.feet.distanceSquared(near);
                if (d < distance) {
                    distance = d;
                    best = item;
                }
            }
        }
        if (best == null || amount <= 0) {
            return 0;
        }
        final double taken = Math.min(best.life, amount);
        best.life -= taken;
        return taken;
    }

    /** 英靈圖騰（2026-10）：圖騰叫出的英靈用 linked 這個技能，每 linkedTicks 一次。 */
    void place(final Player player, final SkillBuild build, final SkillBuild linked, final long linkedTicks) {
        place(player, build, List.of(), linked, List.of(), linkedTicks);
    }

    /**
     * 2026-10-27 照 PoE2：圖騰輪流用孔裡的每個技能——星咒圖騰：build 之後輪到 moreCasts（其他插槽法術）；
     * 英靈圖騰：linked 之後輪到 moreLinked（其他錘子技能）。
     */
    void place(final Player player, final SkillBuild build, final List<SkillBuild> moreCasts, final SkillBuild linked,
               final List<SkillBuild> moreLinked, final long linkedTicks) {
        final DeploySpec spec = build.deploy();
        final Origin origin = Origin.of(player);
        final Location at;
        switch (spec.kind()) {
            case TRAP -> at = targets.aimPoint(origin, 12);
            case BELL -> at = ground(player.getLocation().add(origin.facing().multiply(2.0)));
            default -> at = ground(player.getLocation().add(origin.facing().multiply(1.5)));
        }
        if (at == null) {
            return;
        }
        final long now = now();
        // 同一個技能同時最多幾個：多的收最舊的（2026-10-27 照 PoE2 Splinter Totem：同時只能有一根；分裂出來的一起收掉）
        final boolean onlyOne = spec.kind() == DeploySpec.Kind.TOTEM && tw.exilecore.stats.StatKeys.flag(
                build.profile().effective(plugin.players().sheet(player)), tw.exilecore.stats.SupportExtraKeys.TOTEM_ONLY_ONE);
        // 2026-10-28 照 PoE2 Tempest Bell：同時最多幾口鐘隨等級（1 → 3（16 級）→ 4（24 級）；skills.yml 的 stages.impact.count）；這次之前一律 1 口
        final SkillStages.Stage bellImpact = spec.kind() == DeploySpec.Kind.BELL ? SkillStages.get(build.definition().id(), "impact") : null;
        final int limit = spec.kind() == DeploySpec.Kind.TOTEM ? (onlyOne ? 1 : Math.max(1, build.totems()))
                : (bellImpact != null && bellImpact.count().intAt(build.level()) > 0 ? bellImpact.count().intAt(build.level()) : Math.max(1, spec.max()))
                        + (spec.kind() == DeploySpec.Kind.BELL ? SkillLimits.bonus(plugin.players().sheet(player), build) : 0); // 2026-10-28 照 PoE2：鐘的「上限」吃 +x 上限（過剩）；這次之前不吃
        final List<Deployed> mine = new ArrayList<>();
        for (final Deployed item : deployed) {
            if (item.owner.equals(player.getUniqueId()) && item.skillId().equals(build.definition().id())) {
                mine.add(item);
            }
        }
        while (mine.size() >= limit) {
            final Deployed oldest = mine.removeFirst();
            remove(oldest, false);
        }
        at.setDirection(origin.facing());
        final String ownerName = plugin.players().get(player).character() == null ? player.getName()
                : plugin.players().get(player).character().name();
        final String label = "<#f5b942>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name())
                + " <gray>（" + tw.exilecore.item.ItemRenderer.escape(ownerName) + "）";
        final ArmorStand stand = spawnStand(player, spec, at, label);
        if (stand == null) {
            return;
        }
        final boolean totem = spec.kind() == DeploySpec.Kind.TOTEM;
        final Deployed item = new Deployed(player.getUniqueId(), totem ? totemHits(build) : build, spec, stand, at, now,
                now + build.durationTicks(spec.duration()), label);
        item.linked = totem ? ancestorHits(linked) : linked;
        item.linkedTicks = Math.max(4, linkedTicks);
        if (totem) { // 2026-10-27：其他孔的技能一樣吃圖騰傷害
            final List<SkillBuild> casts = new ArrayList<>();
            for (final SkillBuild more : moreCasts) {
                if (more != null && more.deploy() != null) {
                    casts.add(totemHits(more));
                }
            }
            item.moreCasts = List.copyOf(casts);
            final List<SkillBuild> calls = new ArrayList<>();
            for (final SkillBuild more : moreLinked) {
                if (more != null && linked != null) {
                    calls.add(ancestorHits(more));
                }
            }
            item.moreLinked = List.copyOf(calls);
        }
        if (spec.kind() == DeploySpec.Kind.TOTEM) {
            final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(player));
            final Set<String> tags = build.profile().tags();
            item.taunt = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_TOTEM_TAUNT);
            item.bond = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_SEARING_BOND);
            item.detonate = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_TOTEM_DETONATE);
            item.vengeful = Math.max(0, effective.flat(tw.exilecore.stats.StatKeys.TOTEM_VENGEFUL, tags));
            // 2026-10-26 照 PoE2 天賦樹：「圖騰 +x% 所有元素抗性上限」（加在 75 上）
            item.resistElemental = Math.max(0, Math.min(75 + Math.max(0, effective.flat(tw.exilecore.stats.TreeEffectKeys.TOTEM_RESIST_MAX, tags)),
                    effective.flat(tw.exilecore.stats.StatKeys.TOTEM_RESIST_ELEMENTAL, tags)));
            item.resistChaos = Math.max(0, Math.min(75, effective.flat(tw.exilecore.stats.StatKeys.TOTEM_RESIST_CHAOS, tags)));
            final double lifeScale = effective.apply(tw.exilecore.stats.StatKeys.TOTEM_LIFE, 1.0, tags);
            final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
            final double[] base = totemBase(plugin.skills().config(), build.level(), plugin.players().get(player).level(),
                    monsters::lifeAt, monsters::damageAt, linked != null ? ANCESTRAL_TOTEM_LIFE : 1.0);
            item.maxLife = Math.max(1, base[0] * lifeScale); // 2026-10-26 照 PoE2 召喚物表（這次之前你最大生命的 30%～60%）
            item.armour = Math.max(0, base[1]);
            // 2026-10-27：只算帶 totem 標籤的（不帶標籤的是你自己的物理減免，不給圖騰）
            final tw.exilecore.stats.StatSheet ownerSheet = plugin.players().sheet(player);
            item.physReduction = Math.max(0, Math.min(90, ownerSheet.flat(tw.exilecore.stats.StatKeys.PHYS_REDUCTION, Set.of(tw.exilecore.stats.Tags.TOTEM))
                    - ownerSheet.flat(tw.exilecore.stats.StatKeys.PHYS_REDUCTION, Set.of())));
            item.vengefulLife = Math.max(1, plugin.players().get(player).maxLife() * totemLifeFactor(build.level()) * lifeScale);
            item.life = item.maxLife;
            item.spiritBond = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_ANCESTRAL_BOND);
            item.regen = Math.max(0, effective.flat(tw.exilecore.stats.TotemKeys.REGEN, tags));
            item.onProjectile = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.TotemKeys.FLAG_ON_ATTACK_PROJECTILE);
            item.usedFire = lastFire.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
            // 2026-10-27 照 PoE2 Splinter Totem：用一次技能後分裂（splinter）；這次之前平衡等效成同時多 2 根
            item.maxSplinters = (int) Math.round(Math.max(0, effective.flat(tw.exilecore.stats.SupportExtraKeys.SPLINTER, tags)));
            if (item.maxSplinters > 0) {
                item.unsplit = build;
                item.unsplitLinked = linked;
                item.unsplitCasts = List.copyOf(moreCasts);
                item.unsplitCalls = List.copyOf(moreLinked);
                item.unsplitLife = item.maxLife;
                item.splinterLess = new double[] {effective.flat(tw.exilecore.stats.SupportExtraKeys.SPLINTER_DAMAGE, tags),
                        effective.flat(tw.exilecore.stats.SupportExtraKeys.SPLINTER_LIFE, tags),
                        effective.flat(tw.exilecore.stats.SupportExtraKeys.SPLINTER_AREA, tags),
                        effective.flat(tw.exilecore.stats.SupportExtraKeys.SPLINTER_SPEED, tags)};
            }
        }
        if (spec.kind() != DeploySpec.Kind.TRAP) {
            item.tag = spawnTag(player, at);
        }
        for (final Player viewer : plugin.getServer().getOnlinePlayers()) {
            visibility(viewer, item); // 2026-10：關掉別人技能特效的人看不到別人的圖騰、陷阱
        }
        showTimeLeft(item, now);
        deployed.add(item);
        if (bellImpact != null) {
            // 2026-10-28 照 PoE2 Tempest Bell：鐘砸下來的那一下打周圍一圈（砸鐘自己的傷害表、2.1 公尺）；這次之前放鐘不造成傷害
            final double radius = SkillStages.radius(build, "impact", build.radius());
            final SkillProfile slam = SkillStages.profile(build, build.profile(), "impact")
                    .derive(build.definition().id() + ".slam", Set.of(tw.exilecore.stats.Tags.AREA), Set.of());
            for (final LivingEntity target : targets.around(at, radius, player)) {
                damage.hit(player, target, slam, null);
            }
            visuals.bellRing(build.definition().visual(), at, radius, item.ringsLeft);
        }
        if (totem) {
            item.cannonsSeen = tw.exilecore.ascendancy.AscendancyEffects.cannonsFired(player);
            tw.exilecore.ascendancy.AscendancyEffects.totemPlaced(player, at); // 2026-10-26（昇華・先祖之引：召喚圖騰時觸發先祖之魂）
        }
        visuals.deploy(build.definition().visual(), spec.kind(), at);
        if (spec.kind() == DeploySpec.Kind.TOTEM && (!spec.buff().isEmpty() || item.spiritBond)) {
            plugin.players().markDirty(player);
        }
        if (item.detonate) {
            // 異常・焚鏈圖騰：放到上限就全部引爆
            final List<Deployed> all = new ArrayList<>();
            for (final Deployed other : deployed) {
                if (other.owner.equals(player.getUniqueId()) && other.skillId().equals(build.definition().id())) {
                    all.add(other);
                }
            }
            if (all.size() >= limit) {
                detonate(all, player);
            }
        }
    }

    /**
     * 2026-10-18：圖騰（與英靈）放的技能標上 {@link tw.exilecore.stats.Tags#TOTEM_HIT}：照 PoE，圖騰打中的擊中回復生命、偷取不回給玩家。
     * 引爆、焚鏈都從 item.build 的技能描述衍生，一起帶著。
     */
    /**
     * 英靈（2026-10-21）：英靈用的錘子技能算「圖騰使用的技能」（照 PoE2）：加上圖騰標籤，吃得到全身的「圖騰傷害」；
     * 裝在英靈圖騰上的輔助照舊不套（只照那個技能與它自己的輔助）。也標上 {@link tw.exilecore.stats.Tags#TOTEM_HIT}。
     */
    static SkillBuild ancestorHits(final SkillBuild build) {
        if (build == null) {
            return null;
        }
        final Set<String> tags = build.profile().tags();
        if (tags.contains(tw.exilecore.stats.Tags.TOTEM) && tags.contains(tw.exilecore.stats.Tags.TOTEM_HIT)
                && tags.contains(tw.exilecore.stats.TreeEffectKeys.ANCESTRAL_BOOST)) {
            return build;
        }
        // 2026-10-26 照 PoE2：英靈打的那一下是「先祖強化」的攻擊（天賦樹「先祖強化攻擊增加 x% 傷害」用）
        return build.withProfile(build.profile().derive(build.profile().id(),
                Set.of(tw.exilecore.stats.Tags.TOTEM, tw.exilecore.stats.Tags.TOTEM_HIT, tw.exilecore.stats.TreeEffectKeys.ANCESTRAL_BOOST), Set.of()));
    }

    private static SkillBuild totemHits(final SkillBuild build) {
        if (build == null || build.profile().tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
            return build;
        }
        return build.withProfile(build.profile().derive(build.profile().id(), Set.of(tw.exilecore.stats.Tags.TOTEM_HIT), Set.of()));
    }

    /** 引爆（2026-10）：每根圖騰在原地炸一圈；炸幾根，每一下更多 30%（第一根不加）。 */
    private void detonate(final List<Deployed> totems, final Player owner) {
        final int count = totems.size();
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
        try {
            for (final Deployed totem : totems) {
                final double radius = 2.5 * totem.build.areaScale();
                final SkillProfile profile = totem.build.profile().derive(totem.skillId() + ".detonate", Set.of(tw.exilecore.stats.Tags.AREA), Set.of())
                        .scaled(DETONATE_MULTIPLIER * (1 + 0.3 * (count - 1)), "引爆");
                for (final LivingEntity target : targets.around(totem.feet, radius, owner)) {
                    damage.hit(owner, target, profile, null);
                }
                final World world = totem.feet.getWorld();
                Fx.spawn(world, org.bukkit.Particle.EXPLOSION, totem.feet.clone().add(0, 1, 0), 1);
                Fx.spawn(world, org.bukkit.Particle.FLAME, totem.feet.clone().add(0, 1, 0), 40, radius * 0.4, 0.6, radius * 0.4, 0.05);
                world.playSound(totem.feet, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.2f);
                remove(totem, false);
            }
        } finally {
            Fx.restore(previousFx);
        }
    }

    /** 引爆的一下 = 火鏈一跳（0.5 秒）的幾倍。 */
    static final double DETONATE_MULTIPLIER = 6.0;

    /** 這個人看不看得到這個圖騰（關掉「別人的技能特效」的人看不到別人的）。 */
    private void visibility(final Player viewer, final Deployed item) {
        final boolean hide = !viewer.getUniqueId().equals(item.owner) && Fx.hides(viewer);
        for (final org.bukkit.entity.Entity entity : new org.bukkit.entity.Entity[] {item.stand, item.tag}) {
            if (entity == null) {
                continue;
            }
            if (hide) {
                viewer.hideEntity(plugin, entity);
            } else {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    /** 設定改了：這個人看到的圖騰、陷阱跟上。 */
    void refresh(final Player viewer) {
        for (final Deployed item : deployed) {
            visibility(viewer, item);
        }
    }

    private ArmorStand spawnStand(final Player player, final DeploySpec spec, final Location at, final String label) {
        final World world = at.getWorld();
        final Material head = Material.matchMaterial(spec.head());
        final boolean small = spec.kind() == DeploySpec.Kind.TRAP;
        try {
            return world.spawn(at, ArmorStand.class, stand -> {
                stand.setInvulnerable(true);
                stand.setGravity(false);
                stand.setBasePlate(false);
                stand.setArms(!small);
                stand.setSmall(small);
                // 圖騰、鐘、陷阱都沒有碰撞箱（2026-10）：準心、箭矢、法術直接穿過去打到後面的怪，
                // 也點不到、打不壞（以前圖騰擋在面前，左鍵出招時會打到圖騰）
                stand.setMarker(true);
                stand.setCanPickupItems(false);
                stand.setPersistent(false);
                stand.setSilent(true);
                stand.setCollidable(false);
                stand.setInvisible(spec.kind() == DeploySpec.Kind.TRAP && head == null);
                for (final EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.FEET,
                        EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
                    stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                    stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
                }
                if (head != null) {
                    stand.getEquipment().setHelmet(new ItemStack(head));
                }
                stand.customName(plugin.text().parse(label));
                stand.setCustomNameVisible(false); // 名字在頭上的文字顯示（{@link #spawnTag}）
                stand.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
            });
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("放不出{}（{}）", spec.kind().displayName(), e.toString());
            return null;
        }
    }

    /** 頭上的名字（跟原版的名牌一樣：面向玩家、半透明的底）。 */
    private TextDisplay spawnTag(final Player player, final Location feet) {
        try {
            return feet.getWorld().spawn(feet.clone().add(0, TAG_HEIGHT, 0), TextDisplay.class, created -> {
                created.setPersistent(false);
                created.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                created.setDefaultBackground(true);
                created.setShadowed(false);
                created.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
            });
        } catch (final RuntimeException e) {
            return null; // 沒有名字照樣能用
        }
    }

    /** 名字離地多高（盔甲座的頭上）。 */
    private static final double TAG_HEIGHT = 2.25;

    /** 這個點往下找地面（最多 4 格）。 */
    private static Location ground(final Location point) {
        final World world = point.getWorld();
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 4; y--) {
            if (y - 1 >= world.getMinHeight() && world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5, point.getYaw(), 0);
            }
        }
        return null;
    }

    /** 每 tick：圖騰施放、陷阱觸發、鐘響、時間到了收掉。 */
    void tick() {
        final long now = now();
        if (!rings.isEmpty()) {
            final List<Ring> due = new ArrayList<>();
            rings.removeIf(ring -> {
                if (now >= ring.atTick()) {
                    due.add(ring);
                    return true;
                }
                return false;
            });
            for (final Ring ring : due) {
                ring(ring.bell(), now);
            }
        }
        if (deployed.isEmpty()) {
            return;
        }
        for (final Deployed item : new ArrayList<>(deployed)) { // 照快照跑：觸發、收掉會改名單
            if (!deployed.contains(item)) {
                continue;
            }
            final Player owner = plugin.getServer().getPlayer(item.owner);
            if (now >= item.untilTick || !item.stand.isValid() || owner == null || !owner.getWorld().equals(item.feet.getWorld())
                    || (item.spec.kind() == DeploySpec.Kind.BELL && item.ringsLeft <= 0)) {
                remove(item, true);
                continue;
            }
            showTimeLeft(item, now);
            if (item.spec.kind() == DeploySpec.Kind.TOTEM && item.life <= 0) {
                // 2026-10-26（昇華・無名心木，PoE2）：生命降到 0 之後 x 秒才死；這次之前降到 0 就死
                final long linger = tw.exilecore.ascendancy.AscendancyEffects.totemLingerTicks(owner);
                if (item.deadAt == 0) {
                    item.deadAt = now;
                }
                if (now - item.deadAt >= linger) {
                    remove(item, false); // 被打倒了
                    continue;
                }
            }
            if (owner.isDead()) {
                continue; // 2026-10-27：主人死了（還沒重生）圖騰、陷阱暫停（不搶擊殺、不幫死掉的你補生命）；這次之前照打
            }
            switch (item.spec.kind()) {
                case TOTEM -> tickTotem(item, owner, now);
                case TRAP -> tickTrap(item, owner, now);
                case BELL -> {
                    if (now % 10 == 0) {
                        visuals.deployIdle(item.build.definition().visual(), item.spec.kind(), item.feet, item.spec.range());
                    }
                }
            }
        }
        if (now % 5 == 0) {
            bonds(now);
        }
        engageAll(now); // 2026-10-26：鎖定圖騰的怪走過去打圖騰
    }

    /** 焚鏈圖騰（2026-10）：你與你的焚鏈圖騰之間、圖騰與圖騰之間連起火鏈；每 0.5 秒燒碰到火鏈或站在兩端附近的敵人。 */
    private void bonds(final long now) {
        final Map<UUID, List<Deployed>> byOwner = new HashMap<>();
        for (final Deployed item : deployed) {
            if (item.bond && deployed.contains(item)) {
                byOwner.computeIfAbsent(item.owner, key -> new ArrayList<>()).add(item);
            }
        }
        for (final Map.Entry<UUID, List<Deployed>> entry : byOwner.entrySet()) {
            final Player owner = plugin.getServer().getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            final List<Deployed> totems = entry.getValue();
            final List<Location[]> segments = new ArrayList<>();
            final Location self = owner.getLocation().add(0, 1, 0);
            for (int i = 0; i < totems.size(); i++) {
                final Location at = totems.get(i).feet.clone().add(0, 1, 0);
                if (self.getWorld().equals(at.getWorld()) && self.distanceSquared(at) <= BOND_MAX * BOND_MAX) {
                    segments.add(new Location[] {self, at});
                }
                if (i + 1 < totems.size()) {
                    segments.add(new Location[] {at, totems.get(i + 1).feet.clone().add(0, 1, 0)});
                }
            }
            final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
            try {
                for (final Location[] segment : segments) {
                    final Vector step = segment[1].toVector().subtract(segment[0].toVector());
                    final double length = step.length();
                    final int points = (int) Math.max(2, length / 0.7);
                    for (int i = 0; i <= points; i++) {
                        final Location at = segment[0].clone().add(step.clone().multiply(i / (double) points));
                        Fx.spawn(at.getWorld(), org.bukkit.Particle.FLAME, at, 1, 0.05, 0.05, 0.05, 0.0);
                    }
                }
                if (now % 10 != 0) {
                    continue;
                }
                final Deployed first = totems.getFirst();
                final SkillProfile profile = first.build.profile().derive(first.skillId() + ".bond", Set.of(tw.exilecore.stats.Tags.AREA), Set.of());
                final Set<UUID> burned = new java.util.HashSet<>();
                for (final Location[] segment : segments) {
                    final Location mid = segment[0].clone().add(segment[1]).multiply(0.5);
                    final double half = segment[0].distance(segment[1]) / 2;
                    for (final LivingEntity target : targets.around(mid, half + BOND_END, owner)) {
                        final Location body = target.getLocation().add(0, target.getHeight() / 2, 0);
                        final boolean near = distanceToSegment(body, segment[0], segment[1]) <= BOND_WIDTH
                                || body.distance(segment[0]) <= BOND_END || body.distance(segment[1]) <= BOND_END;
                        if (near && burned.add(target.getUniqueId())) {
                            damage.hit(owner, target, profile, null);
                        }
                    }
                }
            } finally {
                Fx.restore(previousFx);
            }
        }
    }

    /** 火鏈：你離圖騰超過這麼遠就連不上。 */
    static final double BOND_MAX = 24;
    /** 火鏈：離鏈子多近算碰到。 */
    static final double BOND_WIDTH = 1.0;
    /** 火鏈：兩端（你、圖騰）多近也會燒。 */
    static final double BOND_END = 1.8;

    private static double distanceToSegment(final Location point, final Location a, final Location b) {
        final Vector ab = b.toVector().subtract(a.toVector());
        final Vector ap = point.toVector().subtract(a.toVector());
        final double lengthSquared = ab.lengthSquared();
        final double t = lengthSquared <= 1e-9 ? 0 : Math.max(0, Math.min(1, ap.dot(ab) / lengthSquared));
        return point.toVector().distance(a.toVector().add(ab.multiply(t)));
    }

    /** 花魔力之前：放不放得下去（圖騰、鐘要面前有地）。 */
    String precheck(final Player player, final SkillBuild build) {
        final DeploySpec spec = build.deploy();
        if (spec == null || spec.kind() == DeploySpec.Kind.TRAP) {
            return null;
        }
        final Vector facing = SkillTargets.facing(player);
        final Location at = ground(player.getLocation().add(facing.multiply(spec.kind() == DeploySpec.Kind.BELL ? 2.0 : 1.5)));
        if (at == null) {
            return "面前沒有地方放" + spec.kind().displayName();
        }
        if (spec.kind() == DeploySpec.Kind.TOTEM && plugin.players() != null) {
            // 2026-10-26 照 PoE2 先祖魂約：每個圖騰保留 75 精魂，剩的精魂不夠就放不下（放到上限會收掉最舊的，那根的精魂先還回來）
            final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(player));
            if (tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_ANCESTRAL_BOND)) {
                int mine = 0;
                boolean freesBonded = false;
                for (final Deployed item : deployed) {
                    if (item.owner.equals(player.getUniqueId()) && item.skillId().equals(build.definition().id())) {
                        if (mine == 0) {
                            freesBonded = item.spiritBond;
                        }
                        mine++;
                    }
                }
                final boolean replacing = mine >= Math.max(1, build.totems()) && freesBonded;
                final tw.exilecore.character.PlayerState state = plugin.players().get(player);
                final double free = state.maxSpirit() - state.reservedSpirit();
                if (!replacing && free + 1e-9 < tw.exilecore.stats.TotemKeys.ANCESTRAL_BOND_SPIRIT) {
                    return String.format(java.util.Locale.ROOT, "精魂不足：先祖魂約每個圖騰要保留 %.0f 精魂（剩 %.0f）",
                            tw.exilecore.stats.TotemKeys.ANCESTRAL_BOND_SPIRIT, Math.max(0, free));
                }
            }
        }
        return null;
    }

    /** 2026-10-26 照 PoE2「砲彈準備好了！」：每個玩家最後一次發射攻擊投射物的 tick。 */
    private final Map<UUID, Long> lastFire = new HashMap<>();

    /** 玩家發射了攻擊投射物（只在「圖騰只在你發射攻擊投射物時使用技能」時有用）。 */
    void firedAttackProjectile(final Player player) {
        lastFire.put(player.getUniqueId(), now());
    }

    /**
     * 2026-10-26 照 PoE2「堅木之壘」：擊中的傷害有一部分先由你最近的圖騰的生命承受。回傳圖騰實際吃掉多少（圖騰最多吃到剩 0，倒了就收掉）。
     */
    double absorbHit(final Player owner, final double amount) {
        if (amount <= 0) {
            return 0;
        }
        Deployed nearest = null;
        double best = Double.MAX_VALUE;
        final Location at = owner.getLocation();
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner.getUniqueId()) && item.spec.kind() == DeploySpec.Kind.TOTEM && item.life > 0
                    && item.feet.getWorld().equals(at.getWorld())) {
                final double distance = item.feet.distanceSquared(at);
                if (distance < best) {
                    best = distance;
                    nearest = item;
                }
            }
        }
        if (nearest == null) {
            return 0;
        }
        final double taken = Math.min(amount, nearest.life);
        hurtTotem(nearest, owner, null, taken, now());
        return taken;
    }

    /** 圖騰、鐘的名字後面寫還剩幾秒（秒數變了才改，一秒最多改一次）；陷阱不顯示名字。 */
    private void showTimeLeft(final Deployed item, final long now) {
        if (item.spec.kind() == DeploySpec.Kind.TRAP) {
            return;
        }
        final int seconds = (int) Math.max(0, (item.untilTick - now + 19) / 20);
        if (seconds != item.shownSeconds) {
            item.shownSeconds = seconds;
            final String life = item.spec.kind() == DeploySpec.Kind.TOTEM && (item.taunt || item.vengeful > 0 || item.life < item.maxLife)
                    ? " <red>❤ " + Math.max(0, Math.round(item.life)) : "";
            final Component text = plugin.text().parse(item.label + " <white>" + seconds + "</white><gray> 秒" + life);
            item.stand.customName(text);
            if (item.tag != null && item.tag.isValid()) {
                item.tag.text(text);
            }
        }
    }

    private void tickTotem(final Deployed item, final Player owner, final long now) {
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            tickTotemInner(item, owner, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickTotemInner(final Deployed item, final Player owner, final long now) {
        if (now % 10 == 0) {
            visuals.deployIdle(item.build.definition().visual(), item.spec.kind(), item.feet, item.spec.range());
        }
        if (item.taunt) {
            taunt(item, owner, now);
            return;
        }
        final double regen = now % 20 == 0 && item.life < item.maxLife && plugin.skills() != null
                ? item.regen + plugin.skills().supportMechanics().lineage().totemRegen(owner, item.build, item.feet) : 0; // 2026-10-27 照 PoE2：林樁
        if (regen > 0) {
            item.life = Math.min(item.maxLife, item.life + item.maxLife * regen / 100.0); // 2026-10-26 照 PoE2 天賦：圖騰每秒回復
            item.shownSeconds = -1;
        }
        if (item.bond) {
            return; // 焚鏈圖騰不施法（火鏈在 bonds 裡）
        }
        if (now < item.nextTick) {
            return;
        }
        // 2026-10-26（昇華・砲彈準備好了！，PoE2）：圖騰只在你發射攻擊投射物時使用技能
        final long fired = tw.exilecore.ascendancy.AscendancyEffects.cannonsFired(owner);
        if (fired >= 0) {
            if (fired <= item.cannonsSeen) {
                return;
            }
            item.cannonsSeen = fired;
        }
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        final Origin look = Origin.at(owner, item.feet, item.feet.getDirection());
        for (final LivingEntity candidate : targets.around(item.feet, item.spec.range(), owner)) {
            final double distance = candidate.getLocation().distanceSquared(item.feet);
            if (distance < best && look.canSee(candidate)) {
                best = distance;
                target = candidate;
            }
        }
        if (target == null) {
            item.nextTick = now + 5; // 沒目標：等一下再看
            return;
        }
        if (item.linked != null) {
            // 英靈圖騰：在敵人旁邊（往圖騰那邊 1.5 格）叫出英靈，用連著的錘子技能打一下
            final SkillBuild call = rotate(item.linked, item.moreLinked, item.turn++); // 2026-10-27：輪流用孔裡的錘子技能
            item.nextTick = now + ancestorTicks(item.build, call, plugin.players().sheet(owner)); // 2026-10-21：錘子技能攻擊時間 ×1.5 ÷ 攻速
            final org.bukkit.util.Vector back = item.feet.toVector().subtract(target.getLocation().toVector()).setY(0);
            final Location spot = target.getLocation().clone();
            if (back.lengthSquared() > 1e-4) {
                spot.add(back.normalize().multiply(Math.min(1.5, Math.sqrt(best))));
            }
            item.usedFire = fired;
            visuals.deployCast(item.build.definition().visual(), spot);
            tw.exilecore.skill.Fx.spawn(spot.getWorld(), org.bukkit.Particle.SOUL_FIRE_FLAME, spot.clone().add(0, 1, 0), 12, 0.25, 0.5, 0.25, 0.02);
            executor.executeFrom(Origin.towards(owner, spot, target), call, target);
            splinter(item, owner, now); // 2026-10-27 照 PoE2 Splinter Totem
            return;
        }
        // 2026-10-20：吃攻速／施法速度；2026-10-26 照 PoE2：崩震圖騰照武器攻速的一半、星咒圖騰品質照召喚中的圖騰數加施法速度
        final SkillBuild cast = rotate(item.build, item.moreCasts, item.turn++); // 2026-10-27 照 PoE2 星咒圖騰：輪流放孔裡的法術
        item.nextTick = now + totemTicks(cast, plugin.players().sheet(owner), totemsOf(owner.getUniqueId()));
        item.usedFire = fired;
        final Origin origin = Origin.towards(owner, item.feet, target);
        visuals.deployCast(cast.definition().visual(), item.feet);
        executor.executeFrom(origin, plugin.skills() == null ? cast
                : plugin.skills().supportMechanics().lineage().totemCast(owner, item.feet, cast), target); // 2026-10-27 照 PoE2：林樁
        jaggedEruption(item, owner);
        splinter(item, owner, now); // 2026-10-27 照 PoE2 Splinter Totem
    }

    // ---- 2026-10-27 照 PoE2 Splinter Totem（裂樁）----

    /** 玩家:技能 → 上一次有圖騰分裂的 tick（同時只有一根在分裂）。 */
    private final Map<String, Long> splintering = new HashMap<>();

    /**
     * 圖騰用完一次技能：還能分裂、而且同一個技能沒有別根正在分裂，就分裂成兩根更小的圖騰（照分裂次數更少傷害、生命、範圍、技能速度；
     * 剩下的時間、受傷的比例照舊）；分裂出來的還能再分裂，最多 maxSplinters 次。
     */
    private void splinter(final Deployed item, final Player owner, final long now) {
        if (item.maxSplinters <= item.splinters || item.unsplit == null || !deployed.contains(item)) {
            return;
        }
        final String key = owner.getUniqueId() + ":" + item.skillId();
        final Long last = splintering.get(key);
        if (last != null && now - last < tw.exilecore.stats.SupportExtraKeys.SPLINTER_GAP) {
            return; // 別根正在分裂：下一次用完技能再分
        }
        splintering.put(key, now);
        final double lifeFraction = item.maxLife > 0 ? Math.max(0.01, item.life / item.maxLife) : 1;
        remove(item, true);
        final org.bukkit.util.Vector facing = item.feet.getDirection().setY(0);
        final org.bukkit.util.Vector side = facing.lengthSquared() < 1e-6 ? new org.bukkit.util.Vector(1, 0, 0)
                : new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX()).normalize();
        for (final int sign : new int[] {-1, 1}) {
            final Location spot = ground(item.feet.clone().add(side.clone().multiply(0.9 * sign)));
            splinterCopy(item, owner, spot == null ? item.feet.clone() : spot, item.splinters + 1, lifeFraction, now);
        }
        Fx.spawn(item.feet.getWorld(), org.bukkit.Particle.BLOCK, item.feet.clone().add(0, 1, 0), 16, 0.3, 0.5, 0.3, 0,
                Material.OAK_LOG.createBlockData());
    }

    /** 分裂出來的一根（times = 這根分裂過幾次）。 */
    private void splinterCopy(final Deployed item, final Player owner, final Location at, final int times, final double lifeFraction, final long now) {
        at.setDirection(item.feet.getDirection());
        final ArmorStand stand = spawnStand(owner, item.spec, at, item.label);
        if (stand == null) {
            return;
        }
        final Deployed copy = new Deployed(item.owner, totemHits(splinterBuild(item.unsplit, times, item.splinterLess)), item.spec, stand, at,
                now, item.untilTick, item.label);
        copy.linked = item.unsplitLinked == null ? null : ancestorHits(splinterBuild(item.unsplitLinked, times, item.splinterLess));
        copy.linkedTicks = item.linkedTicks;
        final List<SkillBuild> casts = new ArrayList<>();
        for (final SkillBuild more : item.unsplitCasts) {
            if (more != null && more.deploy() != null) {
                casts.add(totemHits(splinterBuild(more, times, item.splinterLess)));
            }
        }
        copy.moreCasts = List.copyOf(casts);
        final List<SkillBuild> calls = new ArrayList<>();
        for (final SkillBuild more : item.unsplitCalls) {
            if (more != null && copy.linked != null) {
                calls.add(ancestorHits(splinterBuild(more, times, item.splinterLess)));
            }
        }
        copy.moreLinked = List.copyOf(calls);
        copy.taunt = item.taunt;
        copy.bond = item.bond;
        copy.detonate = item.detonate;
        copy.vengeful = item.vengeful;
        copy.resistElemental = item.resistElemental;
        copy.resistChaos = item.resistChaos;
        final double lifeScale = tw.exilecore.stats.SupportExtraKeys.splinterScale(item.splinterLess[1], times);
        copy.maxLife = Math.max(1, item.unsplitLife * lifeScale);
        copy.life = copy.maxLife * lifeFraction;
        copy.armour = item.armour;
        copy.physReduction = item.physReduction;
        copy.vengefulLife = item.vengefulLife * lifeScale;
        copy.regen = item.regen;
        copy.onProjectile = item.onProjectile;
        copy.usedFire = item.usedFire;
        copy.cannonsSeen = item.cannonsSeen;
        copy.splinters = times;
        copy.maxSplinters = item.maxSplinters;
        copy.unsplit = item.unsplit;
        copy.unsplitLinked = item.unsplitLinked;
        copy.unsplitCasts = item.unsplitCasts;
        copy.unsplitCalls = item.unsplitCalls;
        copy.unsplitLife = item.unsplitLife;
        copy.splinterLess = item.splinterLess;
        copy.tag = spawnTag(owner, at);
        for (final Player viewer : plugin.getServer().getOnlinePlayers()) {
            visibility(viewer, copy);
        }
        showTimeLeft(copy, now);
        deployed.add(copy);
        visuals.deploy(copy.build.definition().visual(), item.spec.kind(), at);
    }

    /** 分裂了 times 次的技能：傷害、技能速度更少（每次 less[0]%、less[3]%），範圍更少（less[2]%）。 */
    static SkillBuild splinterBuild(final SkillBuild build, final int times, final double[] less) {
        if (build == null || times <= 0) {
            return build;
        }
        final double damage = tw.exilecore.stats.SupportExtraKeys.splinterScale(less[0], times);
        final double speed = tw.exilecore.stats.SupportExtraKeys.splinterScale(less[3], times);
        final double area = tw.exilecore.stats.SupportExtraKeys.splinterScale(less[2], times);
        final List<tw.exilecore.stats.Modifier> mods = List.of(
                tw.exilecore.stats.Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, damage - 1, "裂樁（分裂 " + times + " 次）"),
                tw.exilecore.stats.Modifier.more(tw.exilecore.stats.StatKeys.ATTACK_SPEED, speed - 1, "裂樁（分裂 " + times + " 次）"),
                tw.exilecore.stats.Modifier.more(tw.exilecore.stats.StatKeys.CAST_SPEED, speed - 1, "裂樁（分裂 " + times + " 次）"));
        return build.withProfile(build.profile().withLocal(mods)).withAreaAndProjectiles(build.areaScale() * Math.sqrt(area), build.projectiles());
    }

    /** 2026-10-26：這個玩家現在召喚中的圖騰數（星咒圖騰品質「每根圖騰增加施法速度」）。 */
    private int totemsOf(final UUID owner) {
        int count = 0;
        for (final Deployed other : deployed) {
            if (other.owner.equals(owner) && other.spec.kind() == DeploySpec.Kind.TOTEM && other.life > 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 2026-10-26 照 PoE2 Shockwave Totem：圖騰的猛擊打到你造的碎地（碎地輔助留下的）時碎地引爆（用掉那塊碎地），
     * 傷害站在上面的敵人（3 格 × 範圍倍率，傷害效益照技能等級 90%→237%，跟猛擊本身的效益分開算）。圖騰自己不會造碎地。
     */
    private void jaggedEruption(final Deployed item, final Player owner) {
        final tw.exilecore.stats.StatSheet effective = item.build.profile().effective(plugin.players().sheet(owner));
        final double eruption = effective.flat(tw.exilecore.stats.TotemKeys.JAGGED_ERUPTION, item.build.profile().tags());
        if (eruption <= 0 || plugin.skills() == null || plugin.skills().chargeEffects() == null) {
            return;
        }
        final double slamRadius = item.build.scaledRadius(item.build.definition().params().radius());
        final List<Location> grounds = plugin.skills().chargeEffects().eruptJagged(owner, item.feet, slamRadius);
        if (grounds.isEmpty()) {
            return;
        }
        final tw.exilecore.stats.SkillProfile base = item.build.profile();
        final tw.exilecore.stats.SkillProfile profile = new tw.exilecore.stats.SkillProfile(base.id() + ".eruption", base.tags(),
                eruption / 100.0, base.spellDamage(), base.spellDamageMax(), base.conversion(), base.local())
                .derive(base.id() + ".eruption", Set.of(tw.exilecore.stats.Tags.AREA), Set.of("nova"));
        final double radius = tw.exilecore.stats.TotemKeys.JAGGED_ERUPTION_RADIUS * item.build.areaScale();
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
        try {
            for (final Location at : grounds) {
                Fx.spawn(at.getWorld(), org.bukkit.Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 3, radius * 0.4, 0.2, radius * 0.4, 0);
                Fx.spawn(at.getWorld(), org.bukkit.Particle.CRIT, at.clone().add(0, 0.3, 0), 30, radius * 0.5, 0.3, radius * 0.5, 0.2);
                for (final LivingEntity target : targets.around(at, radius, owner)) {
                    damage.hit(owner, target, profile, null);
                }
            }
        } finally {
            Fx.source(previousFx);
        }
    }

    /** 替身圖騰（2026-10）：範圍內的怪走過來打圖騰，不打玩家；Boss 只被引走走位與普攻，每次 4 秒、之後 8 秒免疫。 */
    private void taunt(final Deployed item, final Player owner, final long now) {
        if (now % 5 != 0) {
            return;
        }
        for (final LivingEntity candidate : targets.around(item.feet, item.spec.range(), owner)) {
            if (!(candidate instanceof org.bukkit.entity.Mob mob)) {
                continue;
            }
            final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(candidate);
            if (data.isEmpty()) {
                continue;
            }
            final boolean boss = data.get().boss();
            if (boss) {
                // Boss（2026-10）：只引走走位與普攻（招式照樣挑玩家放），每次最多 4 秒，之後 8 秒免疫
                if (now < bossImmuneUntil.getOrDefault(mob.getUniqueId(), 0L)) {
                    continue;
                }
                long start = bossTauntStart.computeIfAbsent(mob.getUniqueId(), key -> now);
                if (now - start > BOSS_TAUNT_TICKS + 20) {
                    // 2026-10-18：開始時間過舊（上次沒嘲諷滿就離開，殘留的紀錄）：重新計時，不要直接判成免疫
                    start = now;
                    bossTauntStart.put(mob.getUniqueId(), now);
                }
                if (now - start >= BOSS_TAUNT_TICKS) {
                    bossTauntStart.remove(mob.getUniqueId());
                    if (bossImmuneUntil.size() > 256) {
                        bossImmuneUntil.values().removeIf(until -> until < now); // 2026-10-17：過期的清掉（以前只增不減）
                    }
                    bossImmuneUntil.put(mob.getUniqueId(), now + BOSS_IMMUNE_TICKS);
                    decoyed.remove(mob.getUniqueId());
                    continue;
                }
            }
            decoyed.put(mob.getUniqueId(), item); // 走過去、打圖騰在 engageAll
        }
    }

    /** 2026-10-26 照 PoE2：Boss 被圖騰鎖住：每次最多 4 秒，之後 8 秒不會再被鎖（招式照樣挑玩家放）。回傳現在能不能鎖。 */
    private boolean bossLockable(final org.bukkit.entity.Mob mob, final long now) {
        if (now < bossImmuneUntil.getOrDefault(mob.getUniqueId(), 0L)) {
            return false;
        }
        long start = bossTauntStart.computeIfAbsent(mob.getUniqueId(), key -> now);
        if (now - start > BOSS_TAUNT_TICKS + 20) {
            start = now;
            bossTauntStart.put(mob.getUniqueId(), now);
        }
        if (now - start >= BOSS_TAUNT_TICKS) {
            bossTauntStart.remove(mob.getUniqueId());
            if (bossImmuneUntil.size() > 256) {
                bossImmuneUntil.values().removeIf(until -> until < now);
            }
            bossImmuneUntil.put(mob.getUniqueId(), now + BOSS_IMMUNE_TICKS);
            decoyed.remove(mob.getUniqueId());
            return false;
        }
        return true;
    }

    /** 被圖騰打中而鎖定圖騰的怪最多追多遠（超過就放掉，回去找玩家）。 */
    static final double PROVOKE_LEASH = 32;

    /**
     * 2026-10-26 照 PoE2「誰打牠就打誰」：你的圖騰（或英靈）打中怪物 → 牠改去打你最近的那根圖騰；你自己打中牠 → 牠改回來打你
     * （替身圖騰嘲諷的不會被你搶回來）。這次之前怪物從來不打圖騰（只有替身圖騰引來的會打）。
     */
    void aggro(final Player owner, final LivingEntity victim, final boolean totemHit) {
        if (!(victim instanceof org.bukkit.entity.Mob mob) || plugin.monsters() == null || plugin.monsters().data(victim).isEmpty()) {
            return;
        }
        final Deployed current = decoyed.get(mob.getUniqueId());
        if (!totemHit) {
            // 2026-10-27：任何玩家打中都轉回打他（組隊時隊友也拉得走）；這次之前只有圖騰的主人拉得回來，別人打牠也照打圖騰
            if (current != null && !current.taunt) {
                decoyed.remove(mob.getUniqueId());
                bossTauntStart.remove(mob.getUniqueId());
                mob.setTarget(owner); // 圖騰不再是牠的目標：轉回打牠的你
            }
            return;
        }
        if (current != null && current.taunt) {
            return;
        }
        Deployed nearest = null;
        double best = PROVOKE_LEASH * PROVOKE_LEASH;
        for (final Deployed item : deployed) {
            if (item.owner.equals(owner.getUniqueId()) && item.spec.kind() == DeploySpec.Kind.TOTEM && item.life > 0
                    && item.feet.getWorld().equals(mob.getWorld())) {
                final double distance = item.feet.distanceSquared(mob.getLocation());
                if (distance < best) {
                    best = distance;
                    nearest = item;
                }
            }
        }
        if (nearest == null) {
            return;
        }
        final long now = now();
        if (plugin.monsters().data(victim).get().boss() && !bossLockable(mob, now)) {
            return;
        }
        decoyed.put(mob.getUniqueId(), nearest);
    }

    /** 2026-10-26：鎖定圖騰的怪（替身圖騰嘲諷的、被圖騰打中的）：走過去打它（近戰貼身打、拿弓的在 12 格內射）。 */
    private void engageAll(final long now) {
        if (decoyed.isEmpty() || now % 5 != 0) {
            return;
        }
        for (final Map.Entry<UUID, Deployed> entry : new ArrayList<>(decoyed.entrySet())) {
            final Deployed item = entry.getValue();
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            final Player owner = plugin.getServer().getPlayer(item.owner);
            if (!(entity instanceof org.bukkit.entity.Mob mob) || !mob.isValid() || mob.isDead() || owner == null || !deployed.contains(item)
                    || !mob.getWorld().equals(item.feet.getWorld()) || mob.getLocation().distanceSquared(item.feet) > leash(item) * leash(item)) {
                decoyed.remove(entry.getKey());
                bossTauntStart.remove(entry.getKey());
                continue;
            }
            final tw.exilecore.monster.MonsterData data = plugin.monsters().data(mob).orElse(null);
            if (data == null) {
                decoyed.remove(entry.getKey());
                continue;
            }
            if (data.boss() && !item.taunt && !bossLockable(mob, now)) {
                continue;
            }
            engage(item, owner, mob, data, now);
        }
    }

    private static double leash(final Deployed item) {
        return item.taunt ? item.spec.range() + 6 : PROVOKE_LEASH;
    }

    /** 2026-10-27 效能：這隻怪現在的路徑終點就在圖騰旁邊（1.5 格內）：不用重找。 */
    private static boolean headingTo(final org.bukkit.entity.Mob mob, final Location feet) {
        final com.destroystokyo.paper.entity.Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        if (path == null || path.getFinalPoint() == null) {
            return false;
        }
        final Location end = path.getFinalPoint();
        if (end.getWorld() != null && !end.getWorld().equals(feet.getWorld())) {
            return false;
        }
        final double dx = end.getX() - feet.getX();
        final double dz = end.getZ() - feet.getZ();
        return dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(end.getY() - feet.getY()) <= 2;
    }

    /** 一隻怪打圖騰：照牠的攻擊速度、命中傷害（DamageCalculator.expectedOutgoing），扣圖騰的抗性、護甲。 */
    private void engage(final Deployed item, final Player owner, final org.bukkit.entity.Mob mob, final tw.exilecore.monster.MonsterData data,
                        final long now) {
        if (mob.getTarget() instanceof Player) {
            mob.setTarget(null);
        }
        final boolean ranged = mob instanceof com.destroystokyo.paper.entity.RangedEntity && mob.getEquipment() != null
                && (mob.getEquipment().getItemInMainHand().getType() == Material.BOW
                || mob.getEquipment().getItemInMainHand().getType() == Material.CROSSBOW);
        final double reach = ranged ? 12 : 1.9 + mob.getWidth() / 2;
        final double distance = mob.getLocation().distance(item.feet);
        if (distance > reach) {
            // 2026-10-27 效能：照實體編號錯開（每隻一樣每 10 tick 找一次路，只是不全部擠在同一個 tick）；
            // 圖騰不會動，已經在往圖騰走的就不重找。這次之前所有被嘲諷的怪在 now % 10 == 0 那一刻一起找路
            if ((now / 5 + mob.getEntityId()) % 2 == 0 && !headingTo(mob, item.feet)) {
                mob.getPathfinder().moveTo(item.feet, 1.1);
            }
            return;
        }
        final tw.exilecore.stats.StatSheet sheet = plugin.monsters().sheet(mob, data);
        final double speed = Math.max(0.3, sheet.apply(tw.exilecore.stats.StatKeys.ATTACK_SPEED, 1.0, Set.of(tw.exilecore.stats.Tags.ATTACK)));
        final long gap = Math.max(10, Math.round((ranged ? 40 : 20) / speed));
        final long last = item.attacks.getOrDefault(mob.getUniqueId(), 0L);
        if (now - last < gap) {
            return;
        }
        mob.getPathfinder().stopPathfinding();
        item.attacks.put(mob.getUniqueId(), now);
        mob.lookAt(item.feet.clone().add(0, 1, 0));
        if (ranged) {
            final Location from = mob.getEyeLocation();
            final Vector line = item.feet.clone().add(0, 1, 0).toVector().subtract(from.toVector());
            final int points = (int) Math.max(2, line.length() / 0.6);
            for (int i = 0; i <= points; i++) {
                Fx.spawn(from.getWorld(), org.bukkit.Particle.CRIT, from.clone().add(line.clone().multiply(i / (double) points)), 1, 0, 0, 0, 0.0);
            }
            from.getWorld().playSound(from, org.bukkit.Sound.ENTITY_ARROW_SHOOT, 0.6f, 1.0f);
        } else {
            mob.swingMainHand();
        }
        hurtTotem(item, owner, mob, totemTaken(item, tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, SkillProfile.monsterAttack()))
                * (data.boss() ? BOSS_TOTEM_DAMAGE : 1.0), now);
    }

    /** 2026-10-26 照 PoE2：圖騰受到的傷害：元素、混沌扣圖騰的抗性，物理扣護甲。 */
    static double totemTaken(final double resistElemental, final double resistChaos, final double armour,
                             final tw.exilecore.stats.DamagePacket packet) {
        return totemTaken(resistElemental, resistChaos, armour, 0, packet);
    }

    /** 2026-10-27：加上圖騰的物理傷害減免 %（在護甲之後再扣）。 */
    static double totemTaken(final double resistElemental, final double resistChaos, final double armour, final double physReduction,
                             final tw.exilecore.stats.DamagePacket packet) {
        double taken = 0;
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            final double amount = packet.get(type);
            if (amount <= 0) {
                continue;
            }
            if (type == tw.exilecore.stats.DamageType.PHYSICAL) {
                taken += amount * (1 - tw.exilecore.stats.Formulas.armourReduction(armour, amount)) * (1 - physReduction / 100.0);
            } else {
                final double resist = type == tw.exilecore.stats.DamageType.CHAOS ? resistChaos : resistElemental;
                taken += amount * (1 - resist / 100.0);
            }
        }
        return taken;
    }

    private static double totemTaken(final Deployed item, final tw.exilecore.stats.DamagePacket packet) {
        return totemTaken(item.resistElemental, item.resistChaos, item.armour, item.physReduction, packet);
    }

    /** 這個實體是不是一根圖騰（給怪物的招式找目標用）。 */
    boolean isTotem(final org.bukkit.entity.Entity entity) {
        return totemOf(entity) != null;
    }

    private Deployed totemOf(final org.bukkit.entity.Entity entity) {
        if (!(entity instanceof ArmorStand)) {
            return null;
        }
        for (final Deployed item : deployed) {
            if (item.stand == entity && item.spec.kind() == DeploySpec.Kind.TOTEM) {
                return item;
            }
        }
        return null;
    }

    /**
     * 2026-10-26 照 PoE2：怪物的招式（擊中、範圍、投射物、地面效果）打到圖騰：照那一招的命中傷害扣圖騰的抗性、護甲、生命，
     * 生命歸零就摧毀。這次之前怪物的招式打不到圖騰。
     */
    void hitByMonster(final org.bukkit.entity.Entity stand, final LivingEntity attacker, final SkillProfile profile) {
        final Deployed item = totemOf(stand);
        if (item == null || attacker == null || plugin.monsters() == null) {
            return;
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(attacker).orElse(null);
        if (data == null) {
            return;
        }
        final tw.exilecore.stats.DamagePacket packet = tw.exilecore.stats.DamageCalculator.expectedOutgoing(plugin.monsters().sheet(attacker, data), profile);
        hurtTotem(item, plugin.getServer().getPlayer(item.owner), attacker, totemTaken(item, packet) * (data.boss() ? BOSS_TOTEM_DAMAGE : 1.0), now());
    }

    /** 2026-10-27：圖騰受到的傷害補償為生命只算實際扣掉的生命（超過剩下生命的部分不算；這次之前整下都算，便宜圖騰擋 Boss 一下就補滿）。 */
    static double recoupable(final double life, final double amount) {
        return Math.min(Math.max(0, amount), Math.max(0, life));
    }

    /** 圖騰被打：扣生命，有怨燼就反擊，沒血了收掉。 */
    private void hurtTotem(final Deployed item, final Player owner, final LivingEntity attacker, final double amount, final long now) {
        if (item.life <= 0) {
            return; // 2026-10-27：已經倒下（無名心木還撐著的那幾秒）：不再扣、不再補償
        }
        final double taken = recoupable(item.life, amount);
        item.life -= Math.max(0, amount);
        item.shownSeconds = -1; // 名字上的生命跟著改
        if (owner != null && plugin.skills() != null && plugin.skills().treeMechanics() != null) {
            plugin.skills().treeMechanics().totemDamaged(owner, taken); // 2026-10-26 照 PoE2 天賦：圖騰受到的傷害補償為生命
        }
        Fx.spawn(item.feet.getWorld(), org.bukkit.Particle.DAMAGE_INDICATOR, item.feet.clone().add(0, 1.2, 0), 2, 0.2, 0.2, 0.2, 0.0);
        if (item.vengeful > 0) {
            vengeful(item, owner, attacker, now);
        }
        if (item.life <= 0) {
            item.feet.getWorld().playSound(item.feet, org.bukkit.Sound.BLOCK_WOOD_BREAK, 1.0f, 0.8f);
            // 2026-10-27（無名心木）：有「生命降到 0 之後 x 秒才死」就交給 tick 計時；這次之前被打到 0 直接收掉，只有堅木之壘扣到 0 才有效
            if (owner == null || tw.exilecore.ascendancy.AscendancyEffects.totemLingerTicks(owner) <= 0) {
                remove(item, false);
            } else if (item.deadAt == 0) {
                item.deadAt = now;
            }
        }
    }

    /** 怨燼（2026-10）：往天上射一團火，落在打它的敵人身上炸開（冷卻 1 秒）；傷害 = 圖騰最大生命的 x%（基礎值，吃你的加成與輔助）。 */
    private void vengeful(final Deployed item, final Player owner, final LivingEntity attacker, final long now) {
        if (now < item.vengefulReady || attacker == null || !attacker.isValid()) {
            return;
        }
        item.vengefulReady = now + 20;
        final double base = item.vengefulLife * item.vengeful / 100.0;
        final tw.exilecore.stats.DamagePacket min = new tw.exilecore.stats.DamagePacket();
        final tw.exilecore.stats.DamagePacket max = new tw.exilecore.stats.DamagePacket();
        min.set(tw.exilecore.stats.DamageType.FIRE, base * 0.85);
        max.set(tw.exilecore.stats.DamageType.FIRE, base * 1.15);
        final SkillProfile profile = new SkillProfile(item.skillId() + ".vengeful",
                Set.of(tw.exilecore.stats.Tags.SPELL, tw.exilecore.stats.Tags.FIRE, tw.exilecore.stats.Tags.AREA,
                        tw.exilecore.stats.Tags.TOTEM, tw.exilecore.stats.Tags.TOTEM_HIT, tw.exilecore.stats.Tags.PROJECTILE),
                0.0, min, max, java.util.Map.of(), item.build.profile().local());
        final double radius = 1.8 * item.build.areaScale();
        final Location from = item.feet.clone().add(0, 2, 0);
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId());
        try {
            Fx.spawn(from.getWorld(), org.bukkit.Particle.FLAME, from, 12, 0.15, 0.3, 0.15, 0.08);
        } finally {
            Fx.restore(previousFx);
        }
        from.getWorld().playSound(from, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.7f, 0.8f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final Player caster = plugin.getServer().getPlayer(item.owner);
            if (caster == null || !attacker.getWorld().equals(item.feet.getWorld())) {
                return;
            }
            final Location at = attacker.isValid() ? attacker.getLocation() : item.feet;
            final java.util.UUID previous = Fx.source(caster.getUniqueId());
            try {
                Fx.spawn(at.getWorld(), org.bukkit.Particle.LAVA, at.clone().add(0, 0.5, 0), 8, radius * 0.3, 0.2, radius * 0.3, 0.0);
                Fx.spawn(at.getWorld(), org.bukkit.Particle.FLAME, at.clone().add(0, 0.5, 0), 30, radius * 0.4, 0.3, radius * 0.4, 0.04);
            } finally {
                Fx.restore(previous);
            }
            at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
            for (final LivingEntity target : targets.around(at, radius, caster)) {
                damage.hit(caster, target, profile, null);
            }
        }, 10L);
    }

    /** 被替身圖騰引過去的怪不能改打玩家。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecoyTarget(final org.bukkit.event.entity.EntityTargetEvent event) {
        if (decoyed.isEmpty()) {
            return;
        }
        final Deployed decoy = decoyed.get(event.getEntity().getUniqueId());
        if (decoy == null) {
            return;
        }
        if (!deployed.contains(decoy) || !event.getEntity().getWorld().equals(decoy.feet.getWorld())
                || event.getEntity().getLocation().distanceSquared(decoy.feet) > leash(decoy) * leash(decoy)) {
            decoyed.remove(event.getEntity().getUniqueId());
            bossTauntStart.remove(event.getEntity().getUniqueId()); // 2026-10-18：離開範圍：Boss 的嘲諷計時一起清掉（不然下次直接判成免疫）
            return;
        }
        if (event.getTarget() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /** 2026-10-18：怪死掉：替身圖騰的紀錄清掉（不然 map 一直累積）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDecoyDeath(final org.bukkit.event.entity.EntityDeathEvent event) {
        final UUID uuid = event.getEntity().getUniqueId();
        decoyed.remove(uuid);
        bossTauntStart.remove(uuid);
        bossImmuneUntil.remove(uuid);
    }

    /** 你在有怨燼的圖騰旁邊被怪打中：圖騰也反擊（2026-10；圖騰平常不會被打，這樣一般圖騰也用得到）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerHit(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || event.attacker() instanceof Player || !event.result().hit()
                || event.result().blocked()) {
            return;
        }
        final long now = now();
        for (final Deployed item : new ArrayList<>(deployed)) {
            if (item.vengeful <= 0 || !item.owner.equals(player.getUniqueId()) || !player.getWorld().equals(item.feet.getWorld())
                    || player.getLocation().distanceSquared(item.feet) > VENGEFUL_OWNER_RANGE * VENGEFUL_OWNER_RANGE) {
                continue;
            }
            vengeful(item, player, event.attacker(), now);
        }
    }

    /** 怨燼：你離圖騰多近被打，圖騰也會反擊。 */
    static final double VENGEFUL_OWNER_RANGE = 10;

    private void tickTrap(final Deployed item, final Player owner, final long now) {
        final java.util.UUID previousFx = Fx.source(owner.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            tickTrapInner(item, owner, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickTrapInner(final Deployed item, final Player owner, final long now) {
        if (!item.armed) {
            if (now - item.placedTick >= Math.round(item.spec.arm() * 20)) {
                item.armed = true;
                visuals.deployArmed(item.feet);
            }
            return;
        }
        if (now % 2 != 0) {
            return;
        }
        if (now % 10 == 0) {
            visuals.deployIdle(item.build.definition().visual(), item.spec.kind(), item.feet, item.spec.trigger());
        }
        final List<LivingEntity> near = targets.around(item.feet, item.spec.trigger(), owner);
        if (near.isEmpty()) {
            return;
        }
        final LivingEntity trigger = near.getFirst();
        visuals.deployTrigger(item.build.definition().visual(), item.feet);
        if (item.spec.root() > 0) {
            trigger.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.round(item.spec.root() * 20), 9, false, true, true));
            visuals.root(trigger);
        }
        executor.executeFrom(Origin.towards(owner, item.feet, trigger), item.build, trigger);
        remove(item, false);
    }

    /** 鐘響：打鐘周圍一圈（用放鐘那個技能的傷害；代號加 .ring，免得自己的響聲又觸發自己）。 */
    private void ring(final Deployed bell, final long now) {
        if (!deployed.contains(bell) || bell.ringsLeft <= 0) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(bell.owner);
        if (owner == null) {
            return;
        }
        bell.ringsLeft--;
        bell.nextTick = now + Math.max(1, Math.round(bell.spec.interval() * 20));
        // 2026-10-28 照 PoE2：鐘身上的元素異常讓震波額外獲得元素傷害、會擊退的那一下讓震波範圍更大（StageLeftovers）；這次之前沒有
        final SkillProfile shockwave = StageLeftovers.bellShockwave(bell,
                bell.build.profile().derive(bell.skillId() + ".ring", java.util.Set.of(tw.exilecore.stats.Tags.AREA), java.util.Set.of()), now);
        // 2026-10-28 照 PoE2 Tempest Bell 的品質：鐘每被打一下（含這一下）震波更多 (0—3)% 傷害
        final double perHit = bell.build.qualityFlat(tw.exilecore.stats.GemQualityKeys.MORE_PER_BELL_HIT);
        final int timesHit = Math.max(0, bell.spec.rings() - bell.ringsLeft);
        final SkillProfile profile = perHit > 0 && timesHit > 0 ? shockwave.scaled(1 + perHit / 100.0 * timesHit, "鐘被打的次數") : shockwave;
        final double radius = bell.build.radius() * Math.sqrt(Math.max(1, bell.ringArea));
        bell.ringArea = 1;
        for (final LivingEntity target : targets.around(bell.feet, radius, owner)) {
            damage.hit(owner, target, profile, null);
        }
        visuals.bellRing(bell.build.definition().visual(), bell.feet, radius, bell.ringsLeft);
        if (bell.ringsLeft <= 0) {
            remove(bell, true);
        }
    }

    /** 會讓鐘響的：近戰攻擊（不是鐘自己響的、不是地面效果一波一波的、不是遠程）。 */
    private static boolean ringsBell(final SkillProfile skill) {
        return !skill.id().endsWith(".ring") && !skill.id().endsWith(".zone") && skill.isAttack()
                && skill.tags().contains(tw.exilecore.stats.Tags.MELEE);
    }

    /** 2026-10-25：近戰攻擊揮到的範圍裡有自己的鐘：直接響（不用旁邊有敵人）。 */
    private void struck(final Player owner, final SkillProfile skill, final java.util.function.Predicate<Location> inside, final Object source) {
        if (!ringsBell(skill)) {
            return;
        }
        final long now = now();
        final List<Deployed> exploded = new ArrayList<>();
        for (final Deployed bell : deployed) {
            if (bell.spec.kind() == DeploySpec.Kind.BELL && bell.owner.equals(owner.getUniqueId()) && bell.ringsLeft > 0
                    && inside.test(bell.feet.clone().add(0, 0.5, 0))) {
                // 2026-10-28 照 PoE2：完美時機的引霆突把鐘炸掉；其他的照鐘的門檻上異常、記下擊退（StageLeftovers）
                if (plugin.skills() != null && plugin.skills().leftovers().bellHit(owner, bell, skill, null, now)) {
                    exploded.add(bell);
                } else if (now >= bell.nextTick) {
                    queueRing(bell, now);
                }
            }
        }
        exploded.forEach(bell -> remove(bell, true));
    }

    /** 施放者在鐘旁邊打中敵人：鐘排隊響一次（下一 tick）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.result().blocked()
                || !ringsBell(event.skill())) {
            return; // 只有近戰攻擊打中（不是地面效果一波一波的、不是遠程）
        }
        final long now = now();
        final List<Deployed> exploded = new ArrayList<>();
        for (final Deployed bell : deployed) {
            if (bell.spec.kind() != DeploySpec.Kind.BELL || !bell.owner.equals(player.getUniqueId()) || bell.ringsLeft <= 0
                    || !event.defender().getWorld().equals(bell.feet.getWorld())
                    || event.defender().getLocation().distanceSquared(bell.feet) > bell.spec.range() * bell.spec.range()) {
                continue;
            }
            // 2026-10-28 照 PoE2：完美時機的引霆突把鐘炸掉；其他的照那一下（減免前）的傷害讓鐘上異常、記下擊退（StageLeftovers）
            if (plugin.skills() != null && plugin.skills().leftovers().bellHit(player, bell, event.skill(), event.result().outgoing(), now)) {
                exploded.add(bell);
            } else if (now >= bell.nextTick) {
                queueRing(bell, now);
            }
        }
        exploded.forEach(bell -> remove(bell, true));
    }

    private void queueRing(final Deployed bell, final long now) {
        for (final Ring ring : rings) {
            if (ring.bell() == bell) {
                return;
            }
        }
        bell.nextTick = now + Math.max(1, Math.round(bell.spec.interval() * 20));
        rings.add(new Ring(bell, now + 1));
    }

    /** 玩家不能拿走圖騰頭上的東西。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onManipulate(final PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(key)) {
            event.setCancelled(true);
        }
    }

    /** 圖騰、鐘、陷阱不會受傷、不會被打掉（創造模式的玩家、爆炸、火都一樣）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof ArmorStand stand && stand.getPersistentDataContainer().has(key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombust(final org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getEntity() instanceof ArmorStand stand && stand.getPersistentDataContainer().has(key)) {
            event.setCancelled(true);
        }
    }

    private void remove(final Deployed item, final boolean expired) {
        if (deployed.remove(item) && !expired && item.spec.kind() == DeploySpec.Kind.TOTEM && item.life <= 0 && plugin.skills() != null) {
            // 2026-10-27 照 PoE2 樁爆（Tawhoa's Tending）：圖騰被打倒時炸開
            plugin.skills().supportMechanics().lineage().totemDied(plugin.getServer().getPlayer(item.owner), item.build, item.feet, item.maxLife);
        }
        decoyed.entrySet().removeIf(entry -> {
            if (entry.getValue() != item) {
                return false;
            }
            bossTauntStart.remove(entry.getKey()); // 圖騰沒了：Boss 的嘲諷計時重來（免疫照算）
            return true;
        });
        rings.removeIf(ring -> ring.bell() == item);
        if (item.stand.isValid()) {
            item.stand.remove();
        }
        if (item.tag != null && item.tag.isValid()) {
            item.tag.remove();
        }
        visuals.deployGone(item.build.definition().visual(), item.spec.kind(), item.feet, expired);
        if (item.spec.kind() == DeploySpec.Kind.TOTEM && (!item.spec.buff().isEmpty() || item.spiritBond)) {
            final Player owner = plugin.getServer().getPlayer(item.owner);
            if (owner != null) {
                plugin.players().markDirty(owner);
            }
        }
    }

    /** 某個玩家的全部收掉（登出、換角色）。 */
    void forget(final UUID owner) {
        lastFire.remove(owner); // 2026-10-27：下線的人不留著（以前只增不減）
        for (final Deployed item : new ArrayList<>(deployed)) {
            if (item.owner.equals(owner)) {
                remove(item, false);
            }
        }
    }

    /** 全部收掉（插件停用）；也把上次沒收乾淨的盔甲座（當機留下的）清掉。 */
    void clear() {
        for (final Deployed item : new ArrayList<>(deployed)) {
            remove(item, false);
        }
        sweep();
    }

    /** 清掉世界裡帶著我們標記、但不在名單上的盔甲座（上次當機留下的）。 */
    void sweep() {
        for (final World world : plugin.getServer().getWorlds()) {
            for (final ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getPersistentDataContainer().has(key) && !tracked(stand)) {
                    stand.remove();
                }
            }
            for (final TextDisplay tag : world.getEntitiesByClass(TextDisplay.class)) {
                if (tag.getPersistentDataContainer().has(key) && !tracked(tag)) {
                    tag.remove();
                }
            }
        }
    }

    private boolean tracked(final Entity entity) {
        for (final Deployed item : deployed) {
            if (item.stand == entity || item.tag == entity) {
                return true;
            }
        }
        return false;
    }

    /** 這個盔甲座是不是我們放的東西（其他模組判斷用）。 */
    boolean isDeployable(final Entity entity) {
        return entity instanceof ArmorStand && entity.getPersistentDataContainer().has(key);
    }

    int size() {
        return deployed.size();
    }

    /** 給說明：面向的方向（放圖騰、鐘的位置）。 */
    static Vector facing(final Player player) {
        return SkillTargets.facing(player);
    }
}
