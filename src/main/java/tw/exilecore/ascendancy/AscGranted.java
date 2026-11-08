package tw.exilecore.ascendancy;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.DeadeyeMetas;
import tw.exilecore.skill.Mechanic;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2 0.5：昇華天賦賦予的技能（skills.yml 的 BEGIN/END ascendancy，tools/gen_asc_skills.py 產生）自己的效果。
 * 數值照原作；引擎做不到的地方寫在 skills.yml 那個技能的說明與註解。
 * 2026-10-27 照 PoE2：三位巨靈與支援火力的砲手是有實體的召喚物（MinionService，只聽指令、不會受傷），指令從巨靈那裡出手（巨靈會瞬移過去）；
 * 冥想改成真的引導（Channelling 的 recharge 模式，受到傷害中斷），這裡不再處理。
 */
final class AscGranted implements Listener {

    private final AscendancyEffects effects;
    private final ExileCore plugin;

    /** 一個玩家（賦予的技能）的狀態。 */
    /** 2026-11-08：一個主要寶石的幻影（幻影銳眼、幻弓影各一個）。 */
    private static final class Mirage {
        long until;
        long ready;
        int index;
        int generation; // 2026-10-28：第幾個幻影（舊幻影排好的下一步作廢）
        final Map<String, Long> cooldowns = new HashMap<>(); // 2026-10-28 照 PoE2：這個幻影自己的冷卻（技能代號 → 好的 tick）
        Location at;
    }

    private static Mirage mirage(final Own own, final String hostId) {
        return DeadeyeMetas.ARCHER.equals(hostId) ? own.archer : own.deadeye;
    }

    private static final class Own {
        int temper;
        int[] motes = new int[3];
        long moteNext;
        long boilNext;
        int swarm;
        long swarmHit;
        long sprintEnd;
        long calledNext;
        int calledIndex; // 2026-10-28：預告射擊孔裡好幾個印記時輪流
        final Mirage deadeye = new Mirage(); // 2026-11-08：幻影銳眼的幻影（這次之前是 Own 上的 mirage＊ 欄位）
        final Mirage archer = new Mirage(); // 2026-11-08 照站長「幻弓影照 0.5 原作重做」：幻弓影的幻影（翻滾時留下）
        long alignNext;
        String alignSkill;
        long alignUntil;
        long flameNext;
        int purple;
        long purpleUntil;
        long lifeRemnantReady;
        long manaRemnantReady;
        long voidCharges = 3;
        long voidRecharge;
        double ward = -1;
        long wardReady;
        long demonUntil;
        int demonflame;
        long demonTick;
        long archonUntil;
        long archonTornado;
        long apocalypseUntil;
        long apocalypseNext;
        long beetleNext;
        int beetles;
        int deceptions;
        long deceptionLast;
        Location clone;
        long cloneUntil;
        long zealotryReady;
        long brandReady;
        long embraceReady;
        long embraceCast;
        final ArrayDeque<Long> damaging = new ArrayDeque<>();
        final ArrayDeque<double[]> rift = new ArrayDeque<>();
        Location[] riftAt = new Location[0];
        final ArrayDeque<Location> riftLocations = new ArrayDeque<>();
        long riftNext;
        long hounds;
        String sacrificeMod;
    }

    private final Map<UUID, Own> owns = new HashMap<>();
    /** 敵人身上的血泡、命定苦痛、時間凍結次數。 */
    private final Map<UUID, Integer> boils = new HashMap<>();
    private final Map<UUID, double[]> agony = new HashMap<>();
    private final Map<UUID, UUID> agonyOwner = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> frozenAt = new HashMap<>();
    /** 2026-10-27：上次清掉已經不在的敵人（沒死就消失、區塊卸載的）是哪個 tick。 */
    private long prunedAt;

    /**
     * 2026-10-27：一隻敵人最多累積幾個血泡。原作（poe2db Blood Boil）沒寫上限，但原作的怪活不久；
     * 我們的怪可以一直待在存在範圍裡（卡牆、站樁），這次之前沒有上限，久了範圍與傷害沒有盡頭。
     */
    static final int BOIL_MAX = 20;

    /** 2026-10-27 照 PoE2：爆開的範圍 1.5 公尺（2026-10-29 起換成 2.25 格），每個血泡更多 20% 範圍（加總，不是連乘）；這次之前是 1.2 的次方連乘。 */
    static double boilRadius(final int boil) {
        // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（這次之前 1.5 格）
        return tw.exilecore.core.Units.metres(1.5) * Math.sqrt(1 + 0.2 * Math.max(0, Math.min(BOIL_MAX, boil)));
    }

    /** 再加一個血泡（最多 {@link #BOIL_MAX}）。 */
    static int addBoil(final int current) {
        return Math.min(BOIL_MAX, Math.max(0, current) + 1);
    }
    /** 最近死掉的稀有怪物（儀式獻祭）：位置、詞綴、到哪個 tick。 */
    private final List<RareCorpse> rares = new ArrayList<>();
    /** 地上的殘骸（夏烏拉之炎、生命痕跡）。 */
    private final List<Remnant> remnants = new ArrayList<>();

    /**
     * 2026-11-01 照 PoE2 Mana Remnants（魔泉湧）：擊殺有元素異常的敵人 25% 機率、對有元素異常的目標暴擊（最多每 2 秒一次）產生魔力痕跡，
     * 痕跡 8 秒，撿到回魔力（可以溢出最大魔力），每個 28～281（寶石 1～20 級）＋品質 (0—40)。
     * 這次之前魔泉湧是「魔力回復增加 20～40%、擊殺回 1～2 魔力」，沒有痕跡。
     */
    static final String MANA_REMNANTS = "magic_spring_well";
    static final String MANA_REMNANT_STAT = "remnant.mana";
    static final double MANA_REMNANT_KILL_CHANCE = 0.25;
    static final long MANA_REMNANT_CRIT_TICKS = 40;
    /** 最近被你打到時身上有元素異常的敵人（死的那一刻異常可能已經先被清掉）。 */
    private final Map<UUID, Long> elementalAiled = new HashMap<>();

    /** PoE2 的元素異常：點燃、冰緩、凍結、感電、電擊。 */
    static final Set<AilmentType> ELEMENTAL_AILMENTS = Set.of(AilmentType.IGNITE, AilmentType.CHILL, AilmentType.FREEZE, AilmentType.SHOCK,
            AilmentType.ELECTROCUTE);

    private boolean elementallyAiled(final LivingEntity target) {
        if (plugin.ailments() == null) {
            return false;
        }
        for (final AilmentType type : ELEMENTAL_AILMENTS) {
            if (plugin.ailments().has(target, type)) {
                return true;
            }
        }
        return false;
    }

    /** 魔泉湧產生一個魔力痕跡（回多少照你插著的寶石等級加品質；殘骸輔助照那顆寶石上的）。回傳有沒有產生。 */
    private boolean dropManaRemnant(final Player player, final Location at) {
        final SkillBuild build = plugin.skills() == null ? null : plugin.skills().equippedBuild(player, MANA_REMNANTS);
        if (build == null) {
            return false;
        }
        final double mana = plugin.skills().levelValue(MANA_REMNANTS, MANA_REMNANT_STAT, build.level()) + build.qualityFlat(MANA_REMNANT_STAT);
        if (mana <= 0) {
            return false;
        }
        final tw.exilecore.skill.ChargeEffects.RemnantMods mods = tw.exilecore.skill.ChargeEffects.RemnantMods.of(
                build.profile().effective(plugin.players().sheet(player)), build.profile().tags());
        plugin.skills().chargeEffects().dropManaRemnant(player, at, mana, mods);
        return true;
    }
    /** 納維拉的綠洲。 */
    private final List<Oasis> oases = new ArrayList<>();
    /** 2026-11-01：站在綠洲裡累積的藥劑充能（每秒 0.5～0.7，滿 1 點給一次）。 */
    private final Map<UUID, Double> oasisFlask = new HashMap<>();

    /**
     * 2026-11-01 照 PoE2 Ruzhan's Trap：魯兹安擲出的巨劍落地，在 5 公尺內撒下 8 個火焰符文（每個人最多 21 個，6 秒；品質 +0～2 秒）；
     * 敵人走到 0.8 公尺內就觸發，噴出一道火焰龍捲（asc_ruzhan_tornado：1.2 公尺、4 秒、每 0.7 秒一下、易燃更多 400%）。
     * 這次之前整塊 5 格的地面每 0.7 秒打一下，沒有符文也沒有投射物。
     */
    record Rune(UUID owner, Location at, long until, int level, double tornadoSeconds) {
    }

    private final List<Rune> runes = new ArrayList<>();

    /**
     * 2026-11-01 照 PoE2 Ruzhan's Brand（炎刃．羅贊自帶的指令：按羅贊的鍵）：羅贊在一個敵人身上放火焰烙印，3 秒後（或被引爆時）炸開，
     * 2 公尺內的敵人受到火焰法術傷害、對燃燒中的敵人更多 50%；同時最多 3 個（再放的時候最舊的那個提早炸掉；2026-11-01 照站長）。施法 0.6 秒、魔力 2～29。
     * 羅贊的清算（原作是 Detonator）落地時引爆你全部的烙印。這次之前沒有這個指令（按羅贊的鍵只說「一直開著」）。
     */
    record Brand(UUID owner, LivingEntity target, long explodeAt, int level, Location last) {
    }

    private final List<Brand> brands = new ArrayList<>();
    static final int BRAND_LIMIT = 3;

    /**
     * 2026-11-01 照 PoE2 Navira's Embrace（終幻．娜瑟菈自帶的指令：按娜瑟菈的鍵）：在你身邊放一個 2.5 公尺的泡泡護盾，
     * 站在裡面的你與隊友被泡泡外面的敵人打時，泡泡吃掉 70% 的傷害；泡泡的生命 = 你最大魔力的 60%（每 100 魔力 60），
     * 被打破時塌成一塊冰緩地面（asc_chilled_ground：2 公尺、6 秒）。持續 5 秒 + 0～10 秒（照娜瑟菈的等級 1→100，娜瑟菈等級 = 技能等級 × 2），
     * 冷卻 15 秒、施法 2 秒。這次之前沒有這個指令（按娜瑟菈的鍵只說「一直開著」）。
     */
    static final class Bubble {
        final UUID owner;
        final Location center;
        final long until;
        double life;

        Bubble(final UUID owner, final Location center, final long until, final double life) {
            this.owner = owner;
            this.center = center;
            this.until = until;
            this.life = life;
        }
    }

    private final List<Bubble> bubbles = new ArrayList<>();
    static final double EMBRACE_ABSORB = 0.70;
    static final double EMBRACE_LIFE_PER_MANA = 0.60;
    static final long EMBRACE_COOLDOWN_TICKS = 300;
    static final long EMBRACE_CAST_TICKS = 40;
    /** 娜瑟菈的擁抱的魔力：原作「Cost 7—75」，跟娜瑟菈的綠洲同一張表。 */
    static final int[] EMBRACE_MANA = {7, 8, 10, 11, 13, 15, 17, 20, 23, 25, 29, 32, 36, 40, 45, 50, 55, 61, 68, 75};

    /** 泡泡持續幾秒（原作 base 5 秒、外加 0→10 秒照召喚物等級 1→100 線性）。 */
    static double embraceSeconds(final int level) {
        final double minionLevel = 2.0 * Math.max(1, level);
        return 5 + 10 * Math.min(1.0, (minionLevel - 1) / 99.0);
    }

    static int embraceMana(final int level) {
        if (level <= EMBRACE_MANA.length) {
            return EMBRACE_MANA[Math.max(1, level) - 1];
        }
        final double ratio = (double) EMBRACE_MANA[EMBRACE_MANA.length - 1] / EMBRACE_MANA[EMBRACE_MANA.length - 2];
        return (int) Math.round(EMBRACE_MANA[EMBRACE_MANA.length - 1] * Math.pow(ratio, level - EMBRACE_MANA.length));
    }

    /** 按終幻．娜瑟菈的鍵：下「娜瑟菈的擁抱」指令。 */
    String embrace(final Player player, final SkillBuild build) {
        final PlayerState state = plugin.players().get(player);
        final Own own = own(player);
        final long now = now();
        if (djinnAt(player, "final_mirage_spirit") == null) {
            return "<red>娜瑟菈的擁抱：娜瑟菈要在你身邊 20 格內";
        }
        if (now < own.embraceCast) {
            return "<gray>娜瑟菈的擁抱：施放中";
        }
        if (now < own.embraceReady) {
            return String.format(java.util.Locale.ROOT, "<gray>娜瑟菈的擁抱冷卻中（%.1f 秒）", (own.embraceReady - now) / 20.0);
        }
        final int mana = embraceMana(build.level());
        if (state.mana() + 1e-9 < mana) {
            return "<red>魔力不足（娜瑟菈的擁抱要 " + mana + "）";
        }
        state.setMana(state.mana() - mana);
        // 2026-11-01 照 PoE2（CommandWaterDjinnBubblePlayer castTime 0.4：你下指令很快；WaterBubbleWaterDjinn castTime 2：娜瑟菈放 2 秒 ÷ 她的施法速度）：
        //   你的動作照下指令的時間（SkillService），泡泡在娜瑟菈放完時才出現（在你那時候的位置）；這次之前固定 2 秒（EMBRACE_CAST_TICKS）、馬上出現
        final long cast = Math.max(1, plugin.skills() == null ? EMBRACE_CAST_TICKS : plugin.skills().djinnCastTicks(player, build));
        own.embraceCast = now + cast;
        // 2026-11-01 照 PoE2（WaterBubbleWaterDjinn 是娜瑟菈的技能，冷卻吃召喚物的 CooldownRecovery）：照娜瑟菈的屬性表、指令的情境
        //   （主人給召喚物的冷卻回復、天賦「召喚物的指令技能冷卻回復速度」、軍令增加 100%）；這次之前照你自己的冷卻回復速度
        final StatSheet navira = plugin.skills() == null || plugin.skills().minions() == null ? null
                : plugin.skills().minions().djinnSheet(player.getUniqueId(), "final_mirage_spirit");
        final double recovery = navira == null ? 1.0 : tw.exilecore.skill.Commandables.rate(navira);
        own.embraceReady = now + Math.round(EMBRACE_COOLDOWN_TICKS / recovery);
        navira(player); // 下指令觸發娜瑟菈的寧靜
        if (plugin.skills() != null) {
            plugin.skills().djinnCasting(player, build.definition(), cast, player.getLocation());
        }
        final int level = build.level();
        final double durationScale = build.durationScale();
        effects.later(cast, () -> {
            if (!player.isOnline() || djinnAt(player, "final_mirage_spirit") == null) {
                return; // 娜瑟菈不在了：泡泡沒有放出來
            }
            final Location center = player.getLocation();
            final long until = now() + Math.round(embraceSeconds(level) * 20 * durationScale);
            final double life = state.maxMana() * EMBRACE_LIFE_PER_MANA;
            bubbles.removeIf(b -> b.owner.equals(player.getUniqueId())); // 同一個人同時一個泡泡
            bubbles.add(new Bubble(player.getUniqueId(), center, until, life));
            moveDjinn(player, "final_mirage_spirit", center);
            tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.BUBBLE_POP, center.clone().add(0, 1, 0), 40, 1.2, 1.0, 1.2, 0.02);
        });
        return "<#8fd3ff>娜瑟菈的擁抱";
    }

    /** 純邏輯：泡泡吃掉多少（in 在裡面、out 打的人在外面；吃 70%，最多到泡泡剩下的生命）。 */
    static double bubbleTake(final double damage, final double bubbleLife) {
        return Math.max(0, Math.min(bubbleLife, damage * EMBRACE_ABSORB));
    }

    /** 被打的時候：defender 在某個泡泡裡、attacker 在那個泡泡外面，泡泡吃掉一部分；泡泡破了塌成冰緩地面。回傳吃掉多少。 */
    double embraceAbsorb(final Player defender, final LivingEntity attacker, final double damage) {
        if (bubbles.isEmpty() || attacker == null || damage <= 0) {
            return 0;
        }
        final double radius = tw.exilecore.core.Units.metres(2.5);
        final long now = now();
        for (final Iterator<Bubble> it = bubbles.iterator(); it.hasNext(); ) {
            final Bubble bubble = it.next();
            if (now >= bubble.until) {
                it.remove();
                continue;
            }
            if (bubble.center.getWorld() != defender.getWorld() || attacker.getWorld() != defender.getWorld()) {
                continue;
            }
            final boolean ownerOrAlly = bubble.owner.equals(defender.getUniqueId()) || plugin.party() != null
                    && plugin.party().groupMembers(bubble.owner).contains(defender.getUniqueId());
            if (!ownerOrAlly || defender.getLocation().distance(bubble.center) > radius || attacker.getLocation().distance(bubble.center) <= radius) {
                continue;
            }
            final double taken = bubbleTake(damage, bubble.life);
            bubble.life -= taken;
            if (bubble.life <= 1e-6) {
                it.remove();
                final Player owner = plugin.getServer().getPlayer(bubble.owner);
                if (owner != null && plugin.skills() != null) {
                    plugin.skills().triggerSkill(owner, "asc_chilled_ground", bubble.center);
                }
                tw.exilecore.skill.Fx.spawn(bubble.center.getWorld(), Particle.SNOWFLAKE, bubble.center.clone().add(0, 1, 0), 40, 1.2, 0.8, 1.2, 0.02);
            }
            return taken;
        }
        return 0;
    }

    private void tickBubbles(final long now) {
        if (bubbles.isEmpty()) {
            return;
        }
        final double radius = tw.exilecore.core.Units.metres(2.5);
        bubbles.removeIf(bubble -> now >= bubble.until || plugin.getServer().getPlayer(bubble.owner) == null);
        for (final Bubble bubble : bubbles) {
            tw.exilecore.skill.Fx.disk(bubble.center.getWorld(), Particle.BUBBLE_POP, bubble.center.clone().add(0, 0.2, 0), 18, radius, 0.1, 0.0);
        }
    }
    static final long BRAND_TICKS = 60;
    static final long BRAND_CAST_TICKS = 12;

    /** 羅贊的烙印的魔力（原作 2～29：1 級 2、20 級 29，中間照等比；超過 20 級照同樣的比例往上）。 */
    static int brandMana(final int level) {
        return (int) Math.round(2 * Math.pow(29.0 / 2.0, (Math.max(1, level) - 1) / 19.0));
    }

    /** 按炎刃．羅贊的鍵：下「羅贊的烙印」指令。回傳要顯示的訊息。 */
    String brand(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final PlayerState state = plugin.players().get(player);
        final Own own = own(player);
        final long now = now();
        if (djinnAt(player, "flame_blade_spirit") == null) {
            return "<red>羅贊的烙印：羅贊要在你身邊 20 格內";
        }
        if (now < own.brandReady) {
            return "<gray>羅贊的烙印：施放中";
        }
        final Location aimed = aim(player, preferred, 20);
        LivingEntity target = preferred != null && preferred.isValid() && !preferred.isDead() ? preferred : null;
        if (target == null) {
            double best = Double.MAX_VALUE;
            for (final LivingEntity enemy : effects.enemiesNear(player, aimed, 2.5)) {
                final double distance = enemy.getLocation().distanceSquared(aimed);
                if (distance < best) {
                    best = distance;
                    target = enemy;
                }
            }
        }
        if (target == null) {
            return "<gray>羅贊的烙印：準心附近沒有敵人";
        }
        final int mana = brandMana(build.level());
        if (state.mana() + 1e-9 < mana) {
            return "<red>魔力不足（羅贊的烙印要 " + mana + "）";
        }
        state.setMana(state.mana() - mana);
        // 2026-11-01 照 PoE2（CommandFireDjinnLivingBombPlayer castTime 0.4：你下指令很快；LivingBombFireDjinn castTime 0.6：羅贊放 0.6 秒 ÷ 他的施法速度）：
        //   你的動作照下指令的時間（SkillService），烙印在羅贊放完時才烙上（目標那時候還活著才有）；這次之前固定 0.6 秒（BRAND_CAST_TICKS）、馬上烙上
        final long cast = Math.max(1, plugin.skills() == null ? BRAND_CAST_TICKS : plugin.skills().djinnCastTicks(player, build));
        own.brandReady = now + cast;
        moveDjinn(player, "flame_blade_spirit", target.getLocation());
        if (plugin.skills() != null) {
            plugin.skills().djinnCasting(player, build.definition(), cast, target.getLocation());
        }
        final LivingEntity branded = target;
        final int level = build.level();
        effects.later(cast, () -> {
            if (!player.isOnline() || !branded.isValid() || branded.isDead() || branded.getWorld() != player.getWorld()
                    || djinnAt(player, "flame_blade_spirit") == null) {
                return;
            }
            brands.add(new Brand(player.getUniqueId(), branded, now() + BRAND_TICKS, level, branded.getLocation()));
            for (final Brand old : trimBrands(brands, player.getUniqueId(), BRAND_LIMIT)) {
                explodeBrand(player, old); // 2026-11-01 照站長：超過上限的最舊那個提早炸掉（這次之前直接消失）
            }
            tw.exilecore.skill.Fx.spawn(branded.getWorld(), Particle.FLAME, branded.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
        });
        return "<#ff9a3c>羅贊的烙印";
    }

    /** 純邏輯：owner 的烙印超過 limit 個時，最舊的先拿掉；回傳拿掉的（要提早炸掉的）。 */
    static List<Brand> trimBrands(final List<Brand> list, final UUID owner, final int limit) {
        int count = 0;
        for (final Brand brand : list) {
            if (brand.owner().equals(owner)) {
                count++;
            }
        }
        final List<Brand> removed = new ArrayList<>();
        for (final Iterator<Brand> it = list.iterator(); it.hasNext() && count > limit; ) {
            final Brand brand = it.next();
            if (brand.owner().equals(owner)) {
                it.remove();
                removed.add(brand);
                count--;
            }
        }
        return removed;
    }

    /** 烙印炸開（目標還活著在牠身上炸，死了在最後的位置）。 */
    private void explodeBrand(final Player owner, final Brand brand) {
        final boolean alive = brand.target() != null && brand.target().isValid() && !brand.target().isDead();
        final Location at = alive ? brand.target().getLocation() : brand.last();
        if (at != null && at.getWorld() == owner.getWorld() && plugin.skills() != null) {
            plugin.skills().triggerSkill(owner, "asc_ruzhan_brand", at, brand.level(), List.of());
        }
    }

    /** 烙印時間到（或被引爆）就炸；目標死了在牠最後的位置炸。detonateOwner 不是 null 時把那個玩家的全部引爆。 */
    private void tickBrands(final long now, final UUID detonateOwner) {
        if (brands.isEmpty()) {
            return;
        }
        final List<Brand> blow = new ArrayList<>();
        for (final ListIterator<Brand> it = brands.listIterator(); it.hasNext(); ) {
            final Brand brand = it.next();
            final Player owner = plugin.getServer().getPlayer(brand.owner());
            if (owner == null || !owner.isOnline()) {
                it.remove();
                continue;
            }
            final boolean alive = brand.target().isValid() && !brand.target().isDead();
            final Location at = alive ? brand.target().getLocation() : brand.last();
            if (at.getWorld() != owner.getWorld()) {
                it.remove();
                continue;
            }
            if (now >= brand.explodeAt() || brand.owner().equals(detonateOwner)) {
                it.remove();
                blow.add(new Brand(brand.owner(), brand.target(), brand.explodeAt(), brand.level(), at));
            } else if (alive) {
                it.set(new Brand(brand.owner(), brand.target(), brand.explodeAt(), brand.level(), at));
            }
        }
        for (final Brand brand : blow) {
            final Player owner = plugin.getServer().getPlayer(brand.owner());
            if (owner != null && plugin.skills() != null) {
                plugin.skills().triggerSkill(owner, "asc_ruzhan_brand", brand.last(), brand.level(), List.of());
            }
        }
    }
    /** 原作的符文數量（Creates 8 Runes、Limit 21 Runes）。 */
    static final int RUNES_PER_CAST = 8;
    static final int RUNE_LIMIT = 21;

    private record RareCorpse(Location at, List<String> mods, long until) {
    }

    /**
     * 2026-11-01 照 PoE2 Navira's Oasis：綠洲是由冰緩地面變成的（一塊冰緩地面一片綠洲）；生命、魔力補償照技能等級
     * （30%→80%、20%→50%），藥劑充能每秒 0.5（品質再 +0～0.2）。這次之前在納維拉腳下 8 公尺、補償固定 30%／20%。
     */
    private record Oasis(UUID owner, Location at, double radius, long until, double life, double mana, double flask) {
    }

    private static final class Remnant {
        final UUID owner;
        final Location at;
        final String kind;
        final double value;
        final long until;
        final ItemDisplay display;
        boolean reappear;

        Remnant(final UUID owner, final Location at, final String kind, final double value, final long until, final ItemDisplay display,
                final boolean reappear) {
            this.owner = owner;
            this.at = at;
            this.kind = kind;
            this.value = value;
            this.until = until;
            this.display = display;
            this.reappear = reappear;
        }
    }

    AscGranted(final AscendancyEffects effects) {
        this.effects = effects;
        this.plugin = effects.plugin;
        this.rips = new TimeRips(effects);
    }

    /** 2026-11-01：乘隙之機的時間殘影。 */
    private final TimeRips rips;

    /** 2026-11-01：技能格倒數：霞瑤之主、天災降臨、珂芮絲的分身、幻影、霞瑤之炎（紫）、命途同調的強化窗口還剩幾 tick。 */
    long slotTicks(final UUID player, final String skillId, final long now) {
        final Own own = owns.get(player);
        if (own == null) {
            return 0;
        }
        return switch (skillId) {
            case "rose_jade_lord" -> own.archonUntil - now;
            case "calamity_descent" -> own.apocalypseUntil - now;
            case "sand_spirit_trick" -> own.clone == null ? 0 : own.cloneUntil - now;
            case DeadeyeMetas.MIRAGE -> own.deadeye.until - now;
            case DeadeyeMetas.ARCHER -> own.archer.until - now; // 2026-11-08
            case "rift_dive" -> own.purple > 0 ? own.purpleUntil - now : 0;
            case "destiny_attune" -> own.alignSkill != null ? own.alignUntil - now : 0;
            default -> 0;
        };
    }

    private Own own(final Player player) {
        return owns.computeIfAbsent(player.getUniqueId(), id -> new Own());
    }

    private long now() {
        return effects.now();
    }

    private boolean on(final Player player, final String skillId) {
        return plugin.skills() != null && plugin.skills().auraOn(player, skillId);
    }

    private double level(final Player player, final String skillId, final String stat) {
        // 2026-10-30 照 PoE2：昇華技能的品質（gem_quality.yml）加在照等級的數值上；只有品質才有的屬性（等級表沒有）就是品質給的值
        return plugin.skills() == null ? 0 : plugin.skills().levelValue(skillId, stat, plugin.skills().grantedLevel(player))
                + plugin.skills().ascQualityFlat(player, skillId, stat);
    }

    void forget(final UUID player) {
        demonBars.remove(player); // 2026-11-05：下線的人用不到了
        final Own own = owns.remove(player);
        remnants.removeIf(remnant -> {
            if (remnant.owner.equals(player)) {
                if (remnant.display != null && remnant.display.isValid()) {
                    remnant.display.remove();
                }
                return true;
            }
            return false;
        });
        oases.removeIf(oasis -> oasis.owner().equals(player));
        runes.removeIf(rune -> rune.owner().equals(player)); // 2026-11-01
        oasisFlask.remove(player);
        if (own != null) {
            own.rift.clear();
        }
    }

    // =====================================================================
    // 放技能（AscendancyEffects.beforeCast）
    // =====================================================================

    /** 玩家自己放了一個技能（扣完消耗之後、真的出手之前）：回傳這一下要多加的修飾器。 */
    List<Modifier> used(final Player player, final PlayerState state, final SkillBuild build, final LivingEntity target) {
        final List<Modifier> local = new ArrayList<>();
        final SkillDefinition definition = build.definition();
        final String id = definition.id();
        final Own own = own(player);
        final long now = now();
        final boolean damaging = definition.mechanic() != Mechanic.AURA && definition.mechanic() != Mechanic.SUMMON
                && definition.mechanic() != Mechanic.WARCRY && build.deploy() == null && !definition.hidden();
        if (damaging) {
            own.damaging.addLast(now);
            while (!own.damaging.isEmpty() && now - own.damaging.peekFirst() > AscendancyEffects.RECENT) {
                own.damaging.pollFirst();
            }
            // 赤炎利劍：你使用造成傷害的技能時，魯兹安用狂熱斬擊（冷卻 4 秒）
            // 2026-10-27 照 PoE2：魯兹安是有實體的巨靈，瞬移到目標旁邊斬（這次之前沒有實體，直接在你前方 3 格斬）
            if (on(player, "flame_blade_spirit") && now >= own.zealotryReady && plugin.skills() != null && djinnAt(player, "flame_blade_spirit") != null) {
                // 2026-11-01 照 PoE2（FlameSliceFireDjinn 有 Cooldown）：冷卻照羅贊的冷卻回復速度（羅贊的屬性表，含召喚技能上的輔助）；這次之前固定 4 秒
                final StatSheet ruzhan = plugin.skills().minions() == null ? null
                        : plugin.skills().minions().djinnSheet(player.getUniqueId(), "flame_blade_spirit");
                final double recovery = ruzhan == null ? 1.0 : Math.max(0.1, ruzhan.apply(StatKeys.COOLDOWN_RECOVERY, 1.0, java.util.Set.of(Tags.COOLDOWN)));
                own.zealotryReady = now + Math.round(Math.max(1, level(player, "flame_blade_spirit", "asc.zealotry_cooldown")) * 20 / recovery);
                final Location at = aim(player, target, 3);
                moveDjinn(player, "flame_blade_spirit", at.clone().subtract(player.getLocation().getDirection().setY(0).normalize().multiply(1.5)));
                effects.later(4, () -> plugin.skills().triggerSkill(player, "asc_ruzhan_zealotry", at));
            }
            // 惡魔變身：使用法術以外的技能就變回人形
            if (own.demonUntil > now && !build.profile().tags().contains(Tags.SPELL)) {
                endDemon(player, own);
            }
            // 命運同調：在下一個殘影出現前自己施放同一個法術 → 強化（更多 30%）
            if (own.alignSkill != null && own.alignSkill.equals(id) && now <= own.alignUntil) {
                local.add(Modifier.more(StatKeys.DAMAGE, 0.30, "命途同調"));
                own.alignSkill = null;
                tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.ENCHANT, player.getLocation().add(0, 1.4, 0), 30, 0.5, 0.6, 0.5, 0.5);
            }
            // 幻影銳眼（2026-10-28 照 PoE2 Mirage Deadeye：主要寶石）：你發射遠程投射物攻擊時留下幻影（冷卻 10 秒），
            // 幻影在持續時間內一直用孔裡的遠程攻擊（傷害更少 50%；tickPlayer）。冷卻、持續時間吃其他孔的迅返、長留與身上的加成。
            // 這次之前孔裡不能放技能：幻影重複你接下來的投射物攻擊，冷卻固定 10 秒、持續時間不吃加成
            if (on(player, DeadeyeMetas.MIRAGE) && build.profile().tags().contains(Tags.ATTACK) && build.profile().tags().contains(Tags.PROJECTILE)
                    && !build.profile().tags().contains(Tags.MELEE)) {
                spawnMirage(player, own, DeadeyeMetas.MIRAGE, now);
            }
        }
        switch (id) {
            case "jade_entomb" -> {
                final AscendancyEffects.AscState s = effects.state(player);
                if (s.jade > 0) {
                    final double seconds = Math.max(4, level(player, id, "asc.jade_guard_seconds"));
                    final double perJade = 0.06 + level(player, id, "asc.jade_guard_percent") / 100.0; // 2026-10-30 照 PoE2：品質每層 +(0—1)% 最大生命
                    state.addGuard(state.maxLife() * perJade * s.jade, now + Math.round(seconds * 20));
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#5fd38a>封入翠玉：用掉 " + s.jade + " 層玉石，獲得 "
                            + Math.round(state.maxLife() * perJade * s.jade) + " 守護"));
                    s.jade = 0;
                    plugin.players().markDirty(player);
                }
            }
            case "forge_arms" -> {
                own.temper = 12;
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.2f);
            }
            case "rite_offering" -> ritualSacrifice(player, state, own);
            case "free_incarnation" -> rebuff(player, build, Math.max(8, level(player, id, "asc.buff_seconds")));
            case "rose_jade_lord" -> {
                own.archonUntil = now + 200;
                own.archonTornado = now;
            }
            case "become_fiend" -> {
                if (own.demonUntil > now) {
                    effects.later(1, () -> endDemon(player, own));
                } else {
                    own.demonUntil = now + 600;
                    own.demonflame = 0;
                    own.demonTick = now + 20;
                }
            }
            case "calamity_descent" -> {
                own.apocalypseUntil = now + 240;
                own.apocalypseNext = now + 5;
            }
            case "time_gap_rewind" -> effects.later(1, () -> plugin.skills().resetCooldowns(player, "time_gap_rewind"));
            case "time_halt" -> timeFreeze(player);
            case "fated_pain" -> {
                final Location at = aim(player, target, 20);
                final double radius = Math.max(1.5, level(player, id, "asc.agony_radius")) * areaScale(player); // 2026-10-31：吃範圍效果
                for (final LivingEntity enemy : effects.enemiesNear(player, at, radius)) {
                    agony.put(enemy.getUniqueId(), new double[] {0, now + 240});
                    agonyOwner.put(enemy.getUniqueId(), player.getUniqueId());
                }
                tw.exilecore.skill.Fx.disk(at.getWorld(), Particle.SCULK_SOUL, at.clone().add(0, 0.5, 0), 30, radius, 0.3, 0.02);
            }
            case "time_gap_breach" -> temporalRift(player, state, own);
            case "mirage_spirit_haven" -> {
                if (!navira(player)) {
                    break;
                }
                // 2026-11-01 照 PoE2（Command：娜瑟菈放完 Navira's Oasis 的施法時間 1.8 秒 ÷ 她的施法速度才變出綠洲）；這次之前按下去馬上變
                // 2026-11-05 照站長「插兩顆範圍沒跟著變大」：綠洲的範圍照這次指令的倍率（巨靈的屬性表，含娜瑟菈召喚技能上的範圍輔助）；
                //   這次之前照你自己的屬性表，掛在娜瑟菈身上的範圍輔助吃不到
                final double area = build.areaScale();
                whenCast(djinnCast(player, build), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // 2026-11-01 照 PoE2 Navira's Oasis（Converts Chilled Ground within 8 metres）：把納維拉身邊 8 公尺內的冰緩地面變成綠洲，
                    //   一塊冰緩地面一片綠洲（大小照那塊地面）；綠洲裡的植物繁茂。這次之前在納維拉腳下直接變出 8 公尺的綠洲
                    final long at = now();
                    final Location djinn = djinnAt(player, "final_mirage_spirit");
                    final double reach = tw.exilecore.core.Units.metres(8) * area;
                    final double life = level(player, id, "asc.oasis_life_recoup");
                    final double mana = level(player, id, "asc.oasis_mana_recoup");
                    final double flask = 0.5 + level(player, id, "asc.oasis_flask_rate");
                    for (final tw.exilecore.skill.ElementalGround.Patch patch
                            : tw.exilecore.skill.ElementalGround.chilledNear(plugin, djinn == null ? player.getLocation() : djinn, reach)) {
                        oases.add(new Oasis(player.getUniqueId(), patch.at(), patch.radius(), at + 300, life, mana, flask));
                        plugin.skills().overgrowArea(player.getUniqueId(), patch.at(), patch.radius(), at + 300);
                    }
                });
            }
            case "mirage_spirit_spring" -> {
                if (!navira(player)) {
                    break;
                }
                // 2026-11-01 照 PoE2（Command：娜瑟菈放完 Navira's Well 的施法時間 2.1 秒 ÷ 她的施法速度才生效）；這次之前按下去馬上生效
                whenCast(djinnCast(player, build), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    state.setLastDamageTick(now() - 400); // 能量護盾立刻開始回充
                    // 2026-11-01 照 PoE2 Navira's Well 的品質：增益再 +(0—20)% 能量護盾回充速度（增益本身照等級 10%→109%，skills.yml）
                    final double quality = level(player, id, "asc.well_es_recharge");
                    if (quality > 0) {
                        plugin.skills().buff(player, "mirage_spirit_spring.quality", definition.name(), 120,
                                List.of(Modifier.increased(StatKeys.ES_RECHARGE, quality / 100.0, definition.name())));
                    }
                });
            }
            case "mirage_spirit_freeze" -> navira(player);
            case "sand_spirit_trick" -> {
                // 連續使用每次更多 50%（最多 300%）；分身還在時再用，分身也爆炸
                own.deceptions = now - own.deceptionLast <= 120 ? Math.min(6, own.deceptions + 1) : 0;
                own.deceptionLast = now;
                // 2026-11-01 照 PoE2（djinn_damage_+%_final_per_teleport_in_sequence 50，品質每 1% +0.5）：每次 50%＋品質，最多 300%
                final double deceptionMore = deceptionMore(own.deceptions, level(player, id, "asc.deception_more"));
                if (deceptionMore > 0) {
                    local.add(Modifier.more(StatKeys.DAMAGE, deceptionMore, "珂芮絲的詭計"));
                }
                // 2026-11-01 照 PoE2（Command：克拉里放完 Kelari's Deception 的施法時間 0.8 秒 ÷ 他的施法速度才瞬移、炸開、留下分身，
                //   上一個還在的分身也在那時候爆炸）；這次之前按下去馬上
                final long cast = djinnCast(player, build);
                final Location oldClone = own.clone;
                final long oldUntil = own.cloneUntil;
                final Location spot = aim(player, target, 20);
                own.clone = spot;
                own.cloneUntil = now + cast + 80;
                final int level = build.level();
                final List<Modifier> sequence = List.copyOf(local);
                whenCast(cast, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (oldClone != null && oldUntil > now() && plugin.skills() != null) {
                        // 2026-10-29 照 PoE2：分身爆炸跟欺瞞同一級、也吃連續使用的更多傷害（這次之前用角色能用的最高級、沒吃到）
                        effects.later(2, () -> plugin.skills().triggerSkill(player, "asc_kelari_clone", oldClone, level, sequence));
                    }
                    moveDjinn(player, "foul_sand_spirit", spot); // 2026-10-27 照 PoE2：克拉里瞬移到目標的地方炸開
                });
            }
            // 2026-11-01 照 PoE2 Kelari's Brutality：飛刀是克拉里射出的投射物（SkillService.kelariKnives：飛刀落地、延遲後爆出刀刃）；
            //   這次之前在這裡撒 11 處落點的刀刃（中間那一處是技能本身的落點）
            // 2026-11-01 照 PoE2 Kelari's Judgment（「Command Kelari to Channel alongside you」、minions_do_not_move_to_skill_target）：
            //   珂芮絲留在你身邊陪你引導（SkillService／Signatures：按住引導、放開時刀刃在準心落下），不會飛過去；
            //   這次之前（2026-10-27）克拉里飛到目標的地方懸起刀刃
            case "sand_spirit_verdict" -> {
            }
            case "blade_spirit_settle" -> {
                final Location at = aim(player, target, 20);
                moveDjinn(player, "flame_blade_spirit", at); // 2026-10-27 照 PoE2：魯兹安瞬移到目標的地方
                // 2026-11-01 照 PoE2（Command：羅贊放完 Ruzhan's Reckoning 的施法時間 2.3 秒 ÷ 他的施法速度才落地出手）：熔岩浪、引爆烙印跟著往後；
                //   這次之前按下去 0.4 秒就落地
                final long cast = djinnCast(player, build);
                // 2026-11-01 照 PoE2 Ruzhan's Reckoning 的 Fire Wave（同一顆寶石的第二段）：熔岩浪跟清算同一級；這次之前用角色能用的最高級
                final int waveLevel = build.level();
                effects.later(cast + 18, () -> plugin.skills().triggerSkill(player, "asc_ruzhan_wave", at, waveLevel, List.of()));
                // 2026-11-01 照 PoE2（Ruzhan's Reckoning 是 Detonator）：落地時引爆你全部的羅贊的烙印
                effects.later(cast + 8, () -> tickBrands(now(), player.getUniqueId()));
            }
            case "blade_spirit_device" -> {
                // 2026-10-27：魯兹安把巨劍擲過去；2026-11-01 照 PoE2 Ruzhan's Trap：落地撒下 8 個火焰符文（投射物本身是技能的那一下）
                // 2026-11-01 照 PoE2（Command）：羅贊放完施法時間（1 秒 ÷ 他的施法速度）才從他身上擲出巨劍（SkillService.djinnOrder），
                //   符文照巨劍從羅贊飛到落點的時間撒下；這次之前羅贊先瞬移到落點、巨劍從你身上飛過去
                final Location at = aim(player, target, 20);
                final Location ruzhan = djinnAt(player, "flame_blade_spirit");
                final long cast = djinnCast(player, build);
                final double speed = tw.exilecore.core.Units.metres(35) / 20.0; // 每秒 35 公尺
                final long flight = cast + Math.max(1, Math.round((ruzhan != null && ruzhan.getWorld() == at.getWorld() ? ruzhan : player.getLocation())
                        .distance(at) / Math.max(0.1, speed)));
                final int level = build.level();
                final double runeSeconds = 6 + level(player, id, "asc.rune_duration");
                final double tornadoSeconds = 4 + level(player, id, "asc.tornado_duration");
                final double spread = tw.exilecore.core.Units.metres(5) * build.areaScale();
                final int limit = RUNE_LIMIT + tw.exilecore.skill.SkillLimits.bonus(state.sheet(), build); // 「+1 上限」（過剩）也吃
                effects.later(flight, () -> placeRunes(player, at, spread, level, runeSeconds, tornadoSeconds, limit));
            }
            case "explosive_concoction" -> {
                // 另外對附近最多 4 個點燃中的敵人各丟一瓶小的
                // 2026-10-28 照 PoE2 Mini Flasks：自己的表（stage.second 50% → 145% → 330%）、1.2 公尺（主瓶 1.8）；這次之前固定 145／185、同樣大
                final Location at = aim(player, target, 16);
                final SkillBuild mini = tw.exilecore.skill.StageAttacks.stage(build, "second");
                final SkillBuild smaller = mini.withAreaAndProjectiles(mini.areaScale() * 12.0 / 18.0, mini.projectiles());
                int thrown = 0;
                for (final LivingEntity enemy : effects.enemiesNear(player, at, 8)) {
                    if (thrown >= 4 + (int) Math.floor(build.qualityFlat("asc.explosive_extra") + 1e-9)) { // 2026-10-30 照 PoE2：品質多丟 (0—2) 個
                        break;
                    }
                    if (plugin.ailments() != null && plugin.ailments().has(enemy, AilmentType.IGNITE)) {
                        thrown++;
                        final SkillBuild small = smaller;
                        effects.later(8, () -> plugin.skills().recast(player, small, enemy.getLocation(), player.getLocation().getDirection(), enemy));
                    }
                }
            }
            default -> {
            }
        }
        return local;
    }

    /** 2026-11-01 照 PoE2 Ruzhan's Trap：在 at 附近 spread 格內撒下 8 個符文（超過 limit 個（21＋上限加成）的時候最舊的先消失）。 */
    private void placeRunes(final Player player, final Location at, final double spread, final int level, final double runeSeconds,
                            final double tornadoSeconds, final int limit) {
        if (!player.isOnline() || at.getWorld() == null || at.getWorld() != player.getWorld()) {
            return;
        }
        final long until = now() + Math.round(runeSeconds * 20);
        final java.util.Random random = new java.util.Random();
        for (int i = 0; i < RUNES_PER_CAST; i++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = Math.sqrt(random.nextDouble()) * spread;
            runes.add(new Rune(player.getUniqueId(), at.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance), until, level, tornadoSeconds));
        }
        trimRunes(runes, player.getUniqueId(), limit);
        tw.exilecore.skill.Fx.disk(at.getWorld(), Particle.FLAME, at.clone().add(0, 0.2, 0), 24, spread, 0.1, 0.01);
    }

    /** 純邏輯：owner 的符文超過 limit 個時，最舊的先拿掉。 */
    static void trimRunes(final List<Rune> list, final UUID owner, final int limit) {
        int count = 0;
        for (final Rune rune : list) {
            if (rune.owner().equals(owner)) {
                count++;
            }
        }
        for (final Iterator<Rune> it = list.iterator(); it.hasNext() && count > limit; ) {
            if (it.next().owner().equals(owner)) {
                it.remove();
                count--;
            }
        }
    }

    /** 每 0.5 秒：符文時間到消失；敵人走到 0.8 公尺內就觸發火焰龍捲。 */
    private void tickRunes(final long now) {
        if (runes.isEmpty()) {
            return;
        }
        final double trigger = tw.exilecore.core.Units.metres(0.8);
        for (final Iterator<Rune> it = runes.iterator(); it.hasNext(); ) {
            final Rune rune = it.next();
            final Player owner = plugin.getServer().getPlayer(rune.owner());
            if (now >= rune.until() || owner == null || !owner.isOnline() || rune.at().getWorld() != owner.getWorld()) {
                it.remove();
                continue;
            }
            if (!effects.enemiesNear(owner, rune.at(), trigger).isEmpty()) {
                it.remove();
                final List<Modifier> extra = rune.tornadoSeconds() > 4
                        ? List.of(Modifier.more(StatKeys.SKILL_DURATION, rune.tornadoSeconds() / 4 - 1, "羅贊的機關")) : List.of();
                plugin.skills().triggerSkill(owner, "asc_ruzhan_tornado", rune.at(), rune.level(), extra);
                continue;
            }
            if (AscendancyEffects.every(now, 20)) {
                tw.exilecore.skill.Fx.spawn(rune.at().getWorld(), Particle.SMALL_FLAME, rune.at().clone().add(0, 0.1, 0), 3, 0.15, 0.02, 0.15, 0.005);
            }
        }
    }

    /** 準心指的地方（有目標就是目標腳下）。 */
    private static Location aim(final Player player, final LivingEntity target, final double range) {
        if (target != null && target.getWorld() == player.getWorld()) {
            return target.getLocation();
        }
        final org.bukkit.util.RayTraceResult hit = player.getWorld().rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
        if (hit != null) {
            return hit.getHitPosition().toLocation(player.getWorld());
        }
        return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(range));
    }

    /** 戰吼型的增益照等級的秒數重給一次（引擎的戰吼持續時間是固定的）。 */
    private void rebuff(final Player player, final SkillBuild build, final double seconds) {
        effects.later(1, () -> {
            if (plugin.skills() != null && !build.warcryOwnModifiers().isEmpty()) {
                plugin.skills().buff(player, build.definition().id(), build.definition().name(), (int) Math.round(seconds * 20), build.warcryOwnModifiers());
            }
        });
    }

    /** 2026-11-01 照 PoE2：巨靈放這個指令的招式要幾 tick（SkillService.djinnCastTicks：施法時間 ÷ 巨靈的施法速度）；不是指令回傳 0。 */
    private long djinnCast(final Player player, final SkillBuild build) {
        return plugin.skills() == null ? 0 : plugin.skills().djinnCastTicks(player, build);
    }

    /** 2026-11-01 照 PoE2：巨靈放完招式（ticks）才做的事；0 就馬上做。 */
    private void whenCast(final long ticks, final Runnable task) {
        if (ticks <= 0) {
            task.run();
        } else {
            effects.later(ticks, task);
        }
    }

    /** 2026-10-27：那位巨靈（有實體的召喚物）在哪裡；沒召喚或不在 20 格內回傳 null。 */
    private Location djinnAt(final Player player, final String djinn) {
        return plugin.skills() == null || plugin.skills().minions() == null ? null : plugin.skills().minions().locate(player, djinn);
    }

    /** 2026-10-27：巨靈聽指令瞬移到 at（之後自己回到你身邊）。 */
    private void moveDjinn(final Player player, final String djinn, final Location at) {
        if (plugin.skills() != null && plugin.skills().minions() != null && at != null) {
            plugin.skills().minions().relocate(player, djinn, at);
        }
    }

    /** 納維拉的指令：要先召喚納維拉；觸發納維拉的寧靜（附近友軍魔力再生增加，4 秒）。 */
    private boolean navira(final Player player) {
        if (djinnAt(player, "final_mirage_spirit") == null) {
            return false; // 2026-10-27：納維拉不在（放之前 AscendancyEffects.cost 已經擋了，這是保險）
        }
        final double regen = navira_calming(player);
        if (regen > 0) {
            // 2026-11-01 照 PoE2 Navira's Calming：你，加上娜瑟菈身邊 2.5 公尺內的隊友；這次之前你身邊 10 格內的隊友
            final Location navira = djinnAt(player, "final_mirage_spirit");
            final double reach = tw.exilecore.core.Units.metres(2.5);
            for (final Player ally : player.getWorld().getPlayers()) {
                if (ally.equals(player) || navira != null && ally.getWorld() == navira.getWorld() && ally.getLocation().distance(navira) <= reach
                        && plugin.party() != null && plugin.party().groupMembers(player.getUniqueId()).contains(ally.getUniqueId())) {
                    plugin.skills().buff(ally, "navira_calming", "娜瑟菈的寧靜", 80, List.of(Modifier.increased(StatKeys.MANA_REGEN, regen / 100.0, "娜瑟菈的寧靜")));
                }
            }
        }
        return true;
    }

    private double navira_calming(final Player player) {
        // 原作 1 級 10% → 20 級 67% → 40 級 127%（skills.yml 的 level_modifiers），品質另外 +(0—20)%（gem_quality.yml；level() 加上）
        return Math.max(10, level(player, "final_mirage_spirit", "asc.calming_regen"));
    }

    /** 2026-11-05：顯示惡魔烈焰層數的增益（只顯示，沒有效果）。 */
    static final String DEMONFLAME_BUFF = "asc_demonflame";
    static final String DEMONFLAME_STACKS = "asc.demonflame_stacks";

    /**
     * 2026-11-05 照站長「沒裝模組的人改成在上方顯示」：沒裝模組（或模組不畫增益列）的人，畫面上方多一條血條「惡魔烈焰 N 層」（10 格，一層一格；
     * 黑暗精通沒有上限時滿 10 層就一直是滿的）。裝了模組的人看模組的增益列，不送這條。
     */
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> demonBars = new HashMap<>();

    private void demonBar(final Player player, final int stacks) {
        if (plugin.clientMod() != null && plugin.clientMod().drawsBuffs(player)) {
            hideDemonBar(player);
            return;
        }
        final net.kyori.adventure.text.Component name = plugin.text().parse("<#ff5a3c>🔥 惡魔烈焰 <white>" + stacks + "</white> 層"
                + "<gray>（法術傷害更多 " + (3 * stacks) + "%，每秒失去生命）");
        final float progress = Math.max(0f, Math.min(1f, stacks / 10f));
        final net.kyori.adventure.bossbar.BossBar bar = demonBars.computeIfAbsent(player.getUniqueId(), id -> net.kyori.adventure.bossbar.BossBar.bossBar(
                name, progress, net.kyori.adventure.bossbar.BossBar.Color.RED, net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10));
        bar.name(name);
        bar.progress(progress);
        player.showBossBar(bar);
    }

    private void hideDemonBar(final Player player) {
        final net.kyori.adventure.bossbar.BossBar bar = demonBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void endDemon(final Player player, final Own own) {
        hideDemonBar(player); // 2026-11-05
        own.demonUntil = 0;
        own.demonflame = 0;
        if (plugin.skills() != null) {
            plugin.skills().removeBuff(player, DEMONFLAME_BUFF); // 2026-11-05
            plugin.skills().removeBuff(player, "become_fiend");
        }
        plugin.players().markDirty(player);
    }

    /**
     * 2026-10-31：昇華賦予的範圍效果（註定之痛、時停、綠洲、女王進軍）照玩家的範圍效果放大（半徑 = √範圍倍率，含伺服器基礎 ×1.5）；
     * 這次之前是固定的半徑。
     */
    private double areaScale(final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        return sheet == null ? 1.0 : Math.sqrt(Math.max(0.05, sheet.apply(StatKeys.SKILL_AREA, 1.0, java.util.Set.of(Tags.AREA))));
    }

    private void timeFreeze(final Player player) {
        final long now = now();
        // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（時間凍結 10 公尺；這次之前 10 格）
        for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), tw.exilecore.core.Units.metres(10) * areaScale(player))) { // 2026-10-31：吃範圍效果
            final ArrayDeque<Long> times = frozenAt.computeIfAbsent(enemy.getUniqueId(), id -> new ArrayDeque<>());
            while (!times.isEmpty() && now - times.peekFirst() > 600) {
                times.pollFirst();
            }
            final long ticks = Math.round(60 / Math.pow(2, times.size()));
            times.addLast(now);
            if (plugin.ailments() != null) {
                plugin.ailments().holdStill(enemy, ticks);
            }
            tw.exilecore.skill.Fx.spawn(enemy.getWorld(), Particle.SNOWFLAKE, enemy.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.6f);
    }

    private void temporalRift(final Player player, final PlayerState state, final Own own) {
        if (own.rift.isEmpty() || own.riftLocations.isEmpty()) {
            return;
        }
        final double[] snapshot = own.rift.peekFirst();
        final Location at = own.riftLocations.peekFirst();
        if (at.getWorld() != player.getWorld()) {
            return;
        }
        player.teleport(at.clone().setDirection(player.getLocation().getDirection()));
        state.setLife(Math.min(state.maxLife(), snapshot[0]));
        state.setMana(Math.min(state.maxMana(), snapshot[1]));
        state.setEnergyShield(Math.min(state.maxEnergyShield(), snapshot[2]));
        own.rift.clear();
        own.riftLocations.clear();
        plugin.players().display(player, state);
        tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.REVERSE_PORTAL, player.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
    }

    /** 儀式獻祭：附近有稀有怪物的屍體就拿牠的詞綴；沒有就割自己 20% 生命，拿一個隨機的怪物詞綴（取代上一個）。 */
    private void ritualSacrifice(final Player player, final PlayerState state, final Own own) {
        if (plugin.monsters() == null || plugin.skills() == null) {
            return;
        }
        final long now = now();
        final int ticks = (int) Math.round(Math.max(82, level(player, "rite_offering", "asc.sacrifice_seconds")) * 20);
        rares.removeIf(rare -> rare.until() < now);
        RareCorpse best = null;
        for (final RareCorpse rare : rares) {
            if (rare.at().getWorld() == player.getWorld() && rare.at().distanceSquared(player.getLocation()) <= 64) {
                best = rare;
            }
        }
        if (best != null) {
            rares.remove(best);
            final List<String> got = new ArrayList<>();
            for (final String modId : best.mods()) {
                final String line = grantMod(player, modId, "儀典獻祭", "ritual." + modId, ticks);
                if (line != null) {
                    got.add(line);
                }
            }
            tellSacrifice(player, got, ticks);
            return;
        }
        state.setLife(Math.max(1, state.life() - state.maxLife() * 0.20));
        final List<tw.exilecore.monster.MonsterConfig.MonsterMod> all = new ArrayList<>(plugin.monsters().config().mods());
        all.removeIf(candidate -> modModifiers(candidate, "").isEmpty()); // 2026-11-07：只抽對玩家有效果的（會召喚、會傳送這種行為詞綴給玩家沒用）
        if (all.isEmpty()) {
            return;
        }
        if (own.sacrificeMod != null) {
            plugin.skills().removeBuff(player, "ritual.self");
        }
        final tw.exilecore.monster.MonsterConfig.MonsterMod mod = all.get(plugin.random().nextInt(all.size()));
        own.sacrificeMod = mod.id();
        final String line = grantMod(player, mod.id(), "儀典獻祭", "ritual.self", ticks);
        tellSacrifice(player, line == null ? List.of() : List.of(line), ticks);
        plugin.players().display(player, state);
    }

    /**
     * 2026-11-07 照站長「儀典獻祭沒顯示拿到什麼增益」：放完在聊天室列出拿到哪些詞綴、各給什麼、幾秒
     * （增益欄也看得到名字與倒數）；這次之前什麼都沒說，詞綴對玩家沒效果時連增益都沒有。
     */
    private void tellSacrifice(final Player player, final List<String> lines, final int ticks) {
        if (lines.isEmpty()) {
            player.sendMessage(plugin.text().parse("<#c48bff>儀典獻祭<gray>：這具屍體的詞綴對你沒有效果"));
            return;
        }
        player.sendMessage(plugin.text().parse("<#c48bff>儀典獻祭<gray>：獲得 " + lines.size() + " 個詞綴，持續 <white>" + Math.round(ticks / 20.0) + "<gray> 秒"));
        for (final String line : lines) {
            player.sendMessage(plugin.text().parse("<dark_gray>・" + line));
        }
    }

    /** 拿到一個怪物詞綴（增益）；回傳要列給玩家看的一行（「名字：效果、效果」），對玩家沒效果的回傳 null。 */
    private String grantMod(final Player player, final String modId, final String sourceName, final String buffId, final int ticks) {
        final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(modId);
        if (mod == null) {
            return null;
        }
        final List<Modifier> modifiers = modModifiers(mod, sourceName + "：" + mod.name());
        if (modifiers.isEmpty()) {
            return null;
        }
        plugin.skills().buff(player, buffId, mod.name() + "（" + sourceName + "）", ticks, modifiers);
        final List<String> effects = new ArrayList<>();
        for (final Modifier modifier : modifiers) {
            effects.add(tw.exilecore.skill.ModifierText.describe(modifier));
        }
        return "<#ffb347>" + tw.exilecore.item.ItemRenderer.escape(mod.name()) + "<gray>：" + tw.exilecore.item.ItemRenderer.escape(String.join("、", effects));
    }

    /** 怪物詞綴換成給玩家的修飾器（屬性、物理轉元素、移動速度、光環的屬性）；行為詞綴（召喚、傳送…）是空的。 */
    private static List<Modifier> modModifiers(final tw.exilecore.monster.MonsterConfig.MonsterMod mod, final String source) {
        final List<Modifier> modifiers = new ArrayList<>();
        for (final tw.exilecore.item.AffixMod affixMod : mod.mods()) {
            modifiers.add(affixMod.toModifier(Map.of(), source));
        }
        for (final Map.Entry<DamageType, Double> entry : mod.addedFromPhysical().entrySet()) {
            modifiers.add(Modifier.flat(StatKeys.damageGain(entry.getKey()), entry.getValue() * 100, source));
        }
        if (mod.vanillaSpeedBonus() > 0) {
            modifiers.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, mod.vanillaSpeedBonus(), source));
        }
        if (mod.aura() != null) {
            for (final tw.exilecore.item.AffixMod affixMod : mod.aura().stats()) {
                modifiers.add(affixMod.toModifier(Map.of(), source));
            }
        }
        return modifiers;
    }

    // =====================================================================
    // 擊中、擊殺、翻滾
    // =====================================================================

    /** 玩家打出去的這一下的條件加成（賦予的技能）。 */
    List<Modifier> conditional(final Player attacker, final LivingEntity defender, final StatSheet sheet, final SkillProfile skill) {
        final List<Modifier> out = new ArrayList<>();
        final Own own = owns.get(attacker.getUniqueId());
        final String id = skill.id();
        if (own != null) {
            // 赤炎利劍：魯兹安最近每用過一個造成傷害的技能更多 10%
            if (id.startsWith("ruzhan_") || id.startsWith("blade_spirit_") || id.equals("asc_ruzhan_zealotry") || id.equals("asc_ruzhan_wave")
                    || id.equals("asc_ruzhan_tornado") || id.equals("asc_ruzhan_brand")) {
                if (!own.damaging.isEmpty()) {
                    out.add(Modifier.more(StatKeys.DAMAGE, 0.10 * own.damaging.size(), "赤炎利劍"));
                }
            }
            // 惡魔變身：每層惡魔烈焰更多 3% 法術傷害
            if (own.demonUntil > now() && own.demonflame > 0 && skill.tags().contains(Tags.SPELL)) {
                out.add(Modifier.more(StatKeys.DAMAGE, 0.03 * own.demonflame, "惡魔烈焰"));
            }
            // 女王進軍的蟲群：每層蟲群前進更多 2%
            if (id.equals("asc_swarm") && own.swarm > 0) {
                out.add(Modifier.more(StatKeys.DAMAGE, 0.02 * own.swarm, "蟲群前進"));
            }
        }
        // 2026-11-01 照 PoE2 Ruzhan's Brand：對燃燒中的敵人更多 50% 擊中傷害；品質（炎刃．羅贊）另外更多 (0—20)%
        if (id.startsWith("asc_ruzhan_brand") && plugin.ailments() != null && plugin.ailments().has(defender, AilmentType.IGNITE)) {
            out.add(Modifier.more(StatKeys.DAMAGE, 0.50 + level(attacker, "flame_blade_spirit", "asc.brand_burning_more") / 100.0, "羅贊的烙印"));
        }
        // 2026-11-01 照 PoE2 Ruzhan's Fury（active_skill_damage_+%_final_vs_immobilised_enemies 60）：禁錮＝定身、凍結、重暈、電擊；
        //   這次之前用 isIncapacitated（輕暈也算）
        if (id.startsWith("blade_spirit_wrath_fire") && plugin.ailments() != null && immobilised(defender)) {
            out.add(Modifier.more(StatKeys.DAMAGE, 0.60, "羅贊的怒焰"));
        }
        // 2026-11-01 照 PoE2 Navira's Fracturing（fracture all nearby areas of Chilled Ground that have enemies standing on them）：
        //   只打站在冰緩地面上的敵人；這次之前打冰緩中的敵人
        if (id.startsWith("mirage_spirit_freeze") && !tw.exilecore.skill.ElementalGround.onChilled(plugin, defender.getLocation())) {
            out.add(Modifier.more(StatKeys.DAMAGE, -1.0, "只碎裂敵人腳下的冰緩地面"));
        }
        return out;
    }

    /** 2026-11-01 照 PoE2 Immobilised：定身、凍結、重暈、電擊（輕暈不算）。 */
    private boolean immobilised(final LivingEntity defender) {
        final tw.exilecore.ailment.AilmentService ailments = plugin.ailments();
        return ailments != null && (ailments.pinned(defender) || ailments.isFrozen(defender) || ailments.heavyStunned(defender)
                || ailments.electrocuted(defender));
    }

    /**
     * 2026-11-01 照 PoE2 Kelari's Deception：連續第 sequence 次（0 = 第一次）更多 (50 + 品質)% × 次數，最多 300%（小數）。
     */
    static double deceptionMore(final int sequence, final double qualityPercent) {
        return Math.min(3.0, Math.max(0, sequence) * (0.5 + Math.max(0, qualityPercent) / 100.0));
    }

    boolean boiling(final LivingEntity defender) {
        return boils.getOrDefault(defender.getUniqueId(), 0) > 0;
    }

    /** 玩家打中怪物之後。 */
    void hit(final Player player, final HitEvent event, final MonsterData data) {
        final LivingEntity target = event.defender();
        final SkillProfile skill = event.skill();
        if (skill == null) {
            return;
        }
        final Set<String> tags = skill.tags();
        final boolean totem = tags.contains(Tags.TOTEM_HIT);
        final long now = now();
        final Own own = own(player);
        final StatSheet sheet = effects.sheet(player);
        // 命定苦痛：記下 25% 的擊中傷害
        final double[] mark = agony.get(target.getUniqueId());
        if (mark != null) {
            mark[0] += event.result().total() * 0.25;
        }
        // 鍛鍊武器：強化過的近戰攻擊擊中時觸發燃爆
        if (own.temper > 0 && !totem && tags.contains(Tags.MELEE) && skill.isAttack() && plugin.skills() != null) {
            own.temper--;
            final Location at = target.getLocation();
            effects.later(1, () -> plugin.skills().triggerSkill(player, "asc_combust", at));
        }
        // 榮耀：壞滅災變（元素擊中）、夏烏拉統治者（混沌擊中）
        if (plugin.skills() != null && !totem) {
            final DamagePacket dealt = event.result().dealt();
            final double power = effects.power(target);
            if (dealt.get(DamageType.FIRE) + dealt.get(DamageType.COLD) + dealt.get(DamageType.LIGHTNING) > 0) {
                plugin.skills().gainGlory(player, "elemental_hit", power);
            }
            if (dealt.get(DamageType.CHAOS) > 0) {
                plugin.skills().gainGlory(player, "chaos_hit", power);
            }
        }
        // 夏烏拉統治者：擊中 25% 機率凋零 4 秒
        if (own.archonUntil > now && plugin.combat() != null && plugin.random().nextDouble() < 0.25) {
            tw.exilecore.combat.HitEffects.addWithered(plugin.combat().debuffs(), target.getUniqueId(), player.getUniqueId(), now, 1,
                    tw.exilecore.combat.HitEffects.WITHERED_TICKS);
        }
        // 2026-11-01 照 PoE2 Mana Remnants：對有元素異常的目標暴擊時產生魔力痕跡（最多每 2 秒一次）
        final boolean ailing = elementallyAiled(target);
        if (ailing) {
            if (elementalAiled.size() > 4096) {
                elementalAiled.clear();
            }
            elementalAiled.put(target.getUniqueId(), now);
        }
        if (ailing && event.result().crit() && now >= own.manaRemnantReady && on(player, MANA_REMNANTS)
                && dropManaRemnant(player, target.getLocation())) {
            own.manaRemnantReady = now + MANA_REMNANT_CRIT_TICKS;
        }
        // 生命痕跡：擊中時（最多每 3 秒一次）產生痕跡
        if (on(player, "vital_residue") && now >= own.lifeRemnantReady) {
            own.lifeRemnantReady = now + 60;
            spawnLifeRemnant(player, target.getLocation()); // 2026-11-02：殘骸輔助
        }
        // 2026-11-01 照 PoE2 Shattering Concoction（has_freeze_cull：Freeze enemies that are Primed for Freeze）：擊中蓄勢待凍的敵人直接凍結
        if (plugin.ailments() != null && has(skill, "asc.freeze_primed") && !rips.isVisage(target)) {
            plugin.ailments().freezeIfPrimed(target);
        }
        // 2026-11-01 照 PoE2 Moment of Vulnerability：打中蓄勢待暈／待電／待凍的敵人拉出時間殘影；打殘影的傷害分一部分給本體（TimeRips）
        rips.hit(player, event, data, "gap_opportunity".equals(skill.id()) ? level(player, "gap_opportunity", "asc.rip_share") : -1);
        // 流血混合物：加劇流血；酸性靈藥：消耗中毒爆裂
        if (plugin.ailments() != null) {
            if (has(skill, "asc.aggravate_on_hit") || AscendancyEffects.has(sheet, AscKeys.CURSE_AGGRAVATE) && plugin.combat() != null
                    && plugin.combat().debuffs().cursed(target.getUniqueId(), now)) {
                effects.later(1, () -> plugin.ailments().aggravateBleeds(target, player.getUniqueId()));
            }
            final double consume = sum(skill, "asc.consume_poison");
            if (consume > 0 && plugin.ailments().has(target, AilmentType.POISON)) {
                final double burst = plugin.ailments().debuffDps(target) * 2 * consume / 100.0;
                plugin.ailments().cure(target, AilmentType.POISON);
                if (burst > 0) {
                    plugin.ailments().dealFlatPhysical(target, burst, player.getUniqueId());
                    tw.exilecore.skill.Fx.spawn(target.getWorld(), Particle.ITEM_SLIME, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);
                }
            }
        }
    }

    private static boolean has(final SkillProfile skill, final String stat) {
        return sum(skill, stat) > 0;
    }

    private static double sum(final SkillProfile skill, final String stat) {
        double total = 0;
        for (final Modifier modifier : skill.local()) {
            if (modifier.stat().equals(stat)) {
                total += modifier.value();
            }
        }
        return total;
    }

    /** 這隻實體還在（載入中、沒被移除）。 */
    private boolean present(final UUID id) {
        final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(id);
        return entity != null && entity.isValid();
    }

    /** 2026-10-27：死了時空裂隙的殘影清掉（復活後不能倒轉回死前的位置與生命）；這次之前只檢查同一個世界。 */
    @EventHandler
    public void onPlayerDeath(final org.bukkit.event.entity.PlayerDeathEvent event) {
        final Own own = owns.get(event.getPlayer().getUniqueId());
        if (own != null) {
            own.rift.clear();
            own.riftLocations.clear();
        }
    }

    void death(final LivingEntity dead, final Player killer) {
        final UUID id = dead.getUniqueId();
        // 鮮血沸騰：血泡爆開，每個血泡給附近的敵人一層腐化之血（每秒死掉那隻最大生命 2% 的物理，5 秒）
        final int boil = boils.getOrDefault(id, 0);
        boils.remove(id);
        final org.bukkit.attribute.AttributeInstance max = dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double maxLife = max == null ? 0 : max.getValue();
        if (boil > 0 && maxLife > 0 && plugin.ailments() != null) {
            Player owner = null;
            for (final Player player : dead.getWorld().getPlayers()) {
                if (on(player, "seething_blood") && player.getLocation().distanceSquared(dead.getLocation()) <= 400) {
                    owner = player;
                    break;
                }
            }
            if (owner != null) {
                final Player source = owner;
                final double radius = boilRadius(boil) * areaScale(source); // 2026-10-31：吃範圍效果
                final List<LivingEntity> victims = effects.enemiesNear(source, dead.getLocation(), radius);
                tw.exilecore.skill.Fx.disk(dead.getWorld(), Particle.DUST, dead.getLocation().add(0, 1, 0), 30, radius, 0.5, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0x8a0303), 1.4f));
                final double perSecond = maxLife * 0.02 * Math.min(BOIL_MAX, boil);
                // 2026-10-30 照 PoE2 Blood Boil 的品質：腐化之血持續時間 +(0—1.5) 秒（最後不滿一秒的照比例）；這次之前固定 5 秒
                final double seconds = 5 + Math.max(0, level(source, "seething_blood", "asc.boil_seconds"));
                for (int i = 1; i <= (int) Math.ceil(seconds - 1e-9); i++) {
                    final double share = Math.min(1, seconds - (i - 1));
                    effects.later(20L * i, () -> {
                        for (final LivingEntity victim : victims) {
                            if (victim.isValid() && !victim.isDead()) {
                                plugin.ailments().dealFlatPhysical(victim, perSecond * share, source.getUniqueId());
                            }
                        }
                    });
                }
            }
        }
        agony.remove(id);
        agonyOwner.remove(id);
        frozenAt.remove(id);
        // 儀式獻祭：記下最近死掉的稀有怪物
        final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(dead).orElse(null);
        if (data != null && data.rarity() == MonsterRarity.RARE && !data.mods().isEmpty()) {
            rares.add(new RareCorpse(dead.getLocation().clone(), List.copyOf(data.mods()), now() + 600));
            if (rares.size() > 50) {
                rares.removeFirst();
            }
        }
        // 2026-11-01 照 PoE2 Mana Remnants：擊殺有元素異常的敵人 25% 機率產生魔力痕跡
        final Long ailedAt = elementalAiled.remove(dead.getUniqueId());
        if (killer != null && on(killer, MANA_REMNANTS) && (elementallyAiled(dead) || ailedAt != null && now() - ailedAt <= 20)
                && plugin.random().nextDouble() < MANA_REMNANT_KILL_CHANCE) {
            dropManaRemnant(killer, dead.getLocation());
        }
        // 生命痕跡：擊殺 25% 機率
        if (killer != null && on(killer, "vital_residue")
                && plugin.random().nextDouble() < 0.25 + level(killer, "vital_residue", "asc.remnant_kill_chance") / 100.0) { // 2026-10-30 照 PoE2：品質 +(0—10)%
            spawnLifeRemnant(killer, dead.getLocation()); // 2026-11-02：殘骸輔助
        }
    }

    /** 翻滾時（虛空幻影）：留下 1 點生命的幻影 2 秒，被敵人打掉（2 秒後身邊有敵人）就爆炸。最多存 3 次、每 4 秒回一次。 */
    void dodge(final Player player) {
        if (!on(player, "null_phantom")) {
            return;
        }
        final Own own = own(player);
        final long now = now();
        if (own.voidCharges < 3 && now >= own.voidRecharge) {
            own.voidCharges = Math.min(3, own.voidCharges + Math.max(1, (now - own.voidRecharge) / 80 + 1));
            own.voidRecharge = now + 80;
        }
        if (own.voidCharges <= 0) {
            return;
        }
        own.voidCharges--;
        if (own.voidRecharge < now) {
            own.voidRecharge = now + 80;
        }
        final Location at = player.getLocation().clone();
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.02);
        effects.later(Math.round(Math.max(1, level(player, "null_phantom", "asc.illusion_seconds")) * 20), () -> {
            // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（虛空幻影爆炸 3 公尺，跟 asc_void_explosion 的範圍一樣；這次之前 3 格）
            // 2026-10-30 照 PoE2 Void Illusion 的品質：爆炸半徑 +(0—0.4) 公尺（範圍照半徑平方換算）
            final double extra = Math.max(0, level(player, "null_phantom", "asc.void_radius"));
            if (!player.isOnline() || effects.enemiesNear(player, at, tw.exilecore.core.Units.metres(3 + extra)).isEmpty() || plugin.skills() == null) {
                return;
            }
            if (extra > 0) {
                final double area = Math.pow((3 + extra) / 3, 2) - 1;
                plugin.skills().triggerSkill(player, "asc_void_explosion", at, plugin.skills().grantedLevel(player),
                        List.of(Modifier.increased(StatKeys.SKILL_AREA, area, "虛無幻影品質")));
            } else {
                plugin.skills().triggerSkill(player, "asc_void_explosion", at);
            }
        });
    }

    /** 被擊中時（高尚禦盾：失去一顆寶珠）；巫術護盾的屏障先吃元素傷害。回傳屏障吃掉多少。 */
    double ward(final Player player, final PlayerState state, final StatSheet sheet, final double total, final DamagePacket dealt) {
        final Own own = owns.get(player.getUniqueId());
        if (own != null && (own.motes[0] + own.motes[1] + own.motes[2]) > 0) {
            final List<Integer> have = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (own.motes[i] > 0) {
                    have.add(i);
                }
            }
            own.motes[have.get(plugin.random().nextInt(have.size()))]--;
            plugin.players().markDirty(player);
        }
        if (!on(player, "witch_barrier") || dealt == null || dealt.total() <= 0 || total <= 0) {
            return 0;
        }
        final Own o = own(player);
        final long now = now();
        final double max = Math.min(32000, (sheet.get(StatKeys.ARMOUR) + sheet.get(StatKeys.EVASION))
                * (0.30 + Math.max(0, level(player, "witch_barrier", "asc.ward_percent")) / 100.0)); // 2026-10-30 照 PoE2：品質 +(0—5)%
        if (o.ward < 0 || now >= o.wardReady && o.ward < max) {
            o.ward = max;
        }
        final double share = AscendancyEffects.has(sheet, AscKeys.WARD_ALL) ? 1.0
                : (dealt.get(DamageType.FIRE) + dealt.get(DamageType.COLD) + dealt.get(DamageType.LIGHTNING)) / dealt.total();
        final double want = total * share;
        final double taken = Math.min(o.ward, want);
        if (taken > 0) {
            o.ward -= taken;
            o.wardReady = now + Math.round(Math.max(6, level(player, "witch_barrier", "asc.ward_delay")) * 20);
        }
        return taken;
    }

    // =====================================================================
    // 殘骸（夏烏拉之炎、生命痕跡）
    // =====================================================================

    /**
     * 2026-10-31 照站長：夏烏拉之炎改成直接生效（不用走過去撿）後效果調低：紫炎每層 7% → 4% 額外混沌、紅藍炎偷取 20% → 10%。
     * 2026-11-01 照站長：改回原作的紫炎每層 7%、紅藍炎偷取 20%（還是直接生效）。
     */
    static final double FLAME_PURPLE = 7;
    static final double FLAME_LEECH = 0.20;
    /** 2026-10-31 照站長：夏烏拉之炎產生的間隔乘幾倍；2026-11-01 照站長改回 1（原作 1.4～1.21 秒一個），這次之前 2。 */
    static final double FLAME_INTERVAL_SCALE = 1.0;

    /** 2026-10-31：自動吃掉時「再出現」只給一次（再出現的那個不會再出現）。 */
    private boolean reappearAllowed = true;

    /**
     * 2026-11-02 照站長（PoE2 Life Remnants 有 Remnant 標籤）：生命殘息的生命痕跡吃殘骸輔助——和骸（多產生一個的機率）、
     * 骸效（效果增加、產生幾秒後才生效）。輔助照生命殘息那一格插的；這次之前插得上去也沒作用（輔助顯示「要會留下殘骸的技能」）。
     */
    private void spawnLifeRemnant(final Player owner, final Location at) {
        final double value = level(owner, "vital_residue", "asc.remnant_life");
        final SkillBuild build = plugin.skills() == null ? null : plugin.skills().equippedBuild(owner, "vital_residue");
        final tw.exilecore.skill.ChargeEffects.RemnantMods mods = build == null ? tw.exilecore.skill.ChargeEffects.RemnantMods.NONE
                : tw.exilecore.skill.ChargeEffects.RemnantMods.of(build.profile().effective(plugin.players().sheet(owner)), build.profile().tags());
        final int count = lifeRemnantCount(mods.extra(), plugin.random().nextDouble());
        final double each = value * (1 + Math.max(0, mods.effect()) / 100.0);
        final long delay = Math.round(Math.max(0, mods.delaySeconds()) * 20);
        for (int i = 0; i < count; i++) {
            final Location spot = at.clone().add(i > 0 ? 0.8 : 0, 0, 0);
            if (delay > 0) {
                effects.later(delay, () -> {
                    if (owner.isOnline()) {
                        spawnRemnant(owner, spot, "life", each, 160);
                    }
                });
            } else {
                spawnRemnant(owner, spot, "life", each, 160);
            }
        }
    }

    /** 2026-11-02：和骸 extra% 機率多一個（純函式）。 */
    static int lifeRemnantCount(final double extraPercent, final double roll) {
        return 1 + (extraPercent > 0 && roll * 100 < extraPercent ? 1 : 0);
    }

    private void spawnRemnant(final Player owner, final Location base, final String kind, final double value, final long ticks) {
        if (base.getWorld() == null) {
            return;
        }
        final Location at = base.clone().add(0, 0.5, 0);
        if (tw.exilecore.skill.ChargeEffects.autoCollect()) {
            // 2026-10-31 照站長：生命痕跡、夏烏拉之炎產生時直接吃到身上（不放在地上）；再出現（風暴回憶）照舊 3 秒後再給一次
            if (!owner.isOnline() || owner.getWorld() != at.getWorld()) {
                return;
            }
            final boolean again = AscendancyEffects.has(effects.sheet(owner), "remnant.reappear_ms") && reappearAllowed;
            collect(owner, new Remnant(owner.getUniqueId(), at, kind, value, now() + ticks, null, false), effects.sheet(owner));
            if (again) {
                effects.later(60, () -> {
                    if (owner.isOnline()) {
                        reappearAllowed = false;
                        try {
                            spawnRemnant(owner, base, kind, value, ticks);
                        } finally {
                            reappearAllowed = true;
                        }
                    }
                });
            }
            return;
        }
        final org.bukkit.Material material = switch (kind) {
            case "purple" -> org.bukkit.Material.AMETHYST_SHARD;
            case "blue" -> org.bukkit.Material.LAPIS_LAZULI;
            default -> org.bukkit.Material.REDSTONE;
        };
        final ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setPersistent(false);
            d.setGlowing(true);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.45f, 0.45f, 0.45f), new org.joml.Quaternionf()));
            d.setVisibleByDefault(false); // 2026-10-27 照站長：痕跡只有自己看得到（別人撿不到，看到只是礙眼）
        });
        owner.showEntity(plugin, display);
        final boolean reappear = AscendancyEffects.has(effects.sheet(owner), "remnant.reappear_ms"); // 2026-10-26：跟灌注殘骸同一個（風暴回憶）
        remnants.add(new Remnant(owner.getUniqueId(), at, kind, value, now() + ticks, display, reappear));
    }

    private void tickRemnants(final long now) {
        final Iterator<Remnant> iterator = remnants.iterator();
        final List<Remnant> again = new ArrayList<>();
        while (iterator.hasNext()) {
            final Remnant remnant = iterator.next();
            final Player owner = plugin.getServer().getPlayer(remnant.owner);
            if (owner == null || now >= remnant.until || remnant.display == null || !remnant.display.isValid()) {
                if (remnant.display != null && remnant.display.isValid()) {
                    remnant.display.remove();
                }
                iterator.remove();
                continue;
            }
            final StatSheet sheet = effects.sheet(owner);
            // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（撿殘骸 1.6 公尺；這次之前 1.6 格）
            final double pickup = tw.exilecore.core.Units.metres(1.6) * Math.max(0.1, 1 + sheet.increased(tw.exilecore.stats.TreeMechanicKeys.REMNANT_RANGE, Set.of()));
            if (owner.getWorld() != remnant.at.getWorld() || owner.getLocation().distanceSquared(remnant.at) > pickup * pickup) {
                continue;
            }
            remnant.display.remove();
            iterator.remove();
            collect(owner, remnant, sheet);
            if (remnant.reappear) {
                again.add(remnant);
            }
        }
        for (final Remnant remnant : again) {
            final Player owner = plugin.getServer().getPlayer(remnant.owner);
            if (owner != null) {
                effects.later(60, () -> {
                    if (owner.isOnline()) {
                        final long left = 160;
                        spawnRemnantOnce(owner, remnant.at, remnant.kind, remnant.value, left);
                    }
                });
            }
        }
    }

    private void spawnRemnantOnce(final Player owner, final Location at, final String kind, final double value, final long ticks) {
        spawnRemnant(owner, at.clone().subtract(0, 0.5, 0), kind, value, ticks);
        if (!remnants.isEmpty()) {
            remnants.getLast().reappear = false; // 只再出現一次
        }
    }

    private void collect(final Player owner, final Remnant remnant, final StatSheet sheet) {
        final PlayerState state = plugin.players().get(owner);
        final double effect = Math.max(0, 1 + sheet.increased(AscKeys.REMNANT_EFFECT, Set.of()) + flameEffect(owner, remnant.kind));
        if (!tw.exilecore.skill.ChargeEffects.autoCollect()) { // 2026-10-31 照站長：直接吃到身上時不出聲
            owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
        }
        switch (remnant.kind) {
            case "life" -> {
                // 生命痕跡：回復生命，可以溢出到 1.5 倍（溢出的部分變成守護）
                final double amount = remnant.value * effect;
                final double room = state.missingLife(); // 2026-11-01：保留的生命回不來，那部分算溢出（PlayerState#missingLife）
                state.setLife(state.life() + Math.min(room, amount));
                // 2026-10-28：溢出的部分疊加到現有的守護上（合計最多 0.5 倍最大生命）；這次之前取較大的（連撿好幾個不會疊，看起來像沒作用）
                final double overflow = Math.max(0, amount - room);
                if (overflow > 0) {
                    final double total = Math.min(state.maxLife() * 0.5, state.guard(now()) + overflow);
                    state.addGuard(total, now() + 160);
                }
            }
            case "red" -> state.addLeech(state.maxLife() * FLAME_LEECH * effect);
            case "blue" -> state.addManaLeech(state.maxMana() * FLAME_LEECH * effect);
            default -> {
                final Own own = own(owner);
                own.purple = (int) Math.min(10, own.purple + 1);
                own.purpleUntil = now() + 200;
                plugin.players().markDirty(owner);
            }
        }
        plugin.players().display(owner, state);
    }

    /** 遁入裂痕的寶石等級給的夏烏拉之炎效果增加（0～50%）。 */
    private double flameEffect(final Player owner, final String kind) {
        if (kind.equals("life")) {
            return 0;
        }
        return level(owner, "rift_dive", "asc.flame_effect") / 100.0
                * (1 + Math.max(0, level(owner, "rift_dive", "asc.flame_effect_inc")) / 100.0); // 2026-10-30 照 PoE2：品質增加 (0—15)% 夏烏拉之炎效果
    }

    // =====================================================================
    // 每 10 tick
    // =====================================================================

    void tick(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead() || plugin.skills() == null) {
                continue;
            }
            try {
                tickPlayer(player, state, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("昇華賦予的技能（每秒）出錯", e);
            }
        }
        tickRemnants(now);
        tickRunes(now); // 2026-11-01 照 PoE2 Ruzhan's Trap
        tickBrands(now, null); // 2026-11-01 照 PoE2 Ruzhan's Brand
        tickBubbles(now); // 2026-11-01 照 PoE2 Navira's Embrace
        // 命定苦痛：時間到（或足以處決）時一次失去記下的生命
        final Iterator<Map.Entry<UUID, double[]>> iterator = agony.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, double[]> entry = iterator.next();
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                iterator.remove();
                continue;
            }
            final double tracked = entry.getValue()[0];
            final boolean expired = now >= entry.getValue()[1];
            final org.bukkit.attribute.AttributeInstance max = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            final double cull = max == null ? 0 : max.getValue() * 0.10;
            if (expired || tracked > 0 && living.getHealth() - tracked <= cull) {
                iterator.remove();
                final Player owner = plugin.getServer().getPlayer(agonyOwner.getOrDefault(entry.getKey(), new UUID(0, 0)));
                agonyOwner.remove(entry.getKey());
                if (tracked > 0) {
                    tw.exilecore.skill.Fx.spawn(living.getWorld(), Particle.DAMAGE_INDICATOR, living.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.1);
                    if (living.getHealth() - tracked <= cull && owner != null) {
                        living.damage(living.getHealth() + 1, owner); // 處決
                    } else {
                        living.setHealth(Math.max(1, living.getHealth() - tracked));
                    }
                }
            }
        }
        oases.removeIf(oasis -> oasis.until() <= now);
        // 2026-10-27：沒死就消失（區塊卸載、被清掉）的敵人不會有死亡事件，每 10 秒清一次；這次之前只在死亡時清，會一直累積
        if (now - prunedAt >= 200) {
            prunedAt = now;
            boils.keySet().removeIf(id -> !present(id));
            frozenAt.keySet().removeIf(id -> !present(id));
            agonyOwner.keySet().removeIf(id -> !agony.containsKey(id));
        }
        // 血肉低語：被詛咒的目標至少保留 15% 生命
        if (plugin.combat() != null && AscendancyEffects.every(now, 20)) {
            for (final UUID target : plugin.combat().debuffs().targets()) {
                if (!plugin.combat().debuffs().cursed(target, now)) {
                    continue;
                }
                double reserve = 0;
                for (final tw.exilecore.combat.Debuffs.Debuff debuff : plugin.combat().debuffs().active(target, now)) {
                    final Player source = debuff.source() == null ? null : plugin.getServer().getPlayer(debuff.source());
                    if (source != null) {
                        reserve = Math.max(reserve, AscendancyEffects.flat(effects.sheet(source), AscKeys.CURSE_RESERVE));
                    }
                }
                final org.bukkit.entity.Entity entity = reserve > 0 ? plugin.getServer().getEntity(target) : null;
                if (entity instanceof LivingEntity living && !(entity instanceof Player) && living.isValid()) {
                    final org.bukkit.attribute.AttributeInstance max = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (max != null && living.getHealth() > max.getValue() * (1 - reserve / 100.0)) {
                        living.setHealth(max.getValue() * (1 - reserve / 100.0));
                    }
                }
            }
        }
    }

    private void tickPlayer(final Player player, final PlayerState state, final long now) {
        final StatSheet sheet = state.sheet();
        final Own own = owns.get(player.getUniqueId());
        final boolean any = own != null || on(player, "righteous_shield") || on(player, "seething_blood") || on(player, "rift_dive")
                || on(player, "called_shots") || on(player, "destiny_attune") || on(player, "sand_spirit_venom_word") || on(player, "empress_march")
                || on(player, "hellfire_dog_call") || state.skills().find("time_gap_breach") >= 0 || !oases.isEmpty();
        if (!any) {
            return;
        }
        final Own o = own(player);
        boolean dirty = false;
        // 時空裂隙：每 0.25 秒記一個殘影（最多 16 個）
        if (state.skills().find("time_gap_breach") >= 0 && now >= o.riftNext) {
            o.riftNext = now + 5;
            o.rift.addLast(new double[] {state.life(), state.mana(), state.energyShield()});
            o.riftLocations.addLast(player.getLocation().clone());
            while (o.rift.size() > 16) {
                o.rift.pollFirst();
                o.riftLocations.pollFirst();
            }
        }
        // 高尚禦盾：每隔一陣子一顆隨機寶珠
        if (on(player, "righteous_shield")) {
            if (now >= o.moteNext) {
                o.moteNext = now + Math.round(Math.max(1.5, level(player, "righteous_shield", "asc.mote_interval")) * 20);
                final int[] cap = moteCaps(state);
                final List<Integer> open = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    if (o.motes[i] < cap[i]) {
                        open.add(i);
                    }
                }
                if (!open.isEmpty()) {
                    o.motes[open.get(plugin.random().nextInt(open.size()))]++;
                    dirty = true;
                }
            }
        } else if (o.motes[0] + o.motes[1] + o.motes[2] > 0) {
            o.motes = new int[3];
            dirty = true;
        }
        // 鮮血沸騰：存在範圍內的非傳奇敵人累積血泡
        if (on(player, "seething_blood") && now >= o.boilNext) {
            o.boilNext = now + Math.round(Math.max(1.5, level(player, "seething_blood", "asc.boil_interval")) * 20);
            for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), AscendancyEffects.presence(sheet))) {
                final MonsterData data = plugin.monsters().data(enemy).orElse(null);
                if (data != null && !data.boss() && data.rarity() != MonsterRarity.UNIQUE) {
                    boils.put(enemy.getUniqueId(), addBoil(boils.getOrDefault(enemy.getUniqueId(), 0)));
                    tw.exilecore.skill.Fx.spawn(enemy.getWorld(), Particle.DUST, enemy.getLocation().add(0, enemy.getHeight(), 0), 3, 0.2, 0.1, 0.2, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(0xb00000), 1f));
                }
            }
        }
        // 遁入裂痕：夏烏拉之炎
        if (on(player, "rift_dive") && now >= o.flameNext) {
            // 2026-10-31 照站長：直接生效之後間隔拉長兩倍（1.4～1.21 秒 → 2.8～2.42 秒）；2026-11-01 照站長改回 1.4～1.21 秒（FLAME_INTERVAL_SCALE 1）
            o.flameNext = now + Math.round(Math.max(1.2, level(player, "rift_dive", "asc.flame_interval")) * FLAME_INTERVAL_SCALE * 20);
            final int colour = (int) AscendancyEffects.flat(sheet, AscKeys.FLAME_COLOUR);
            final String kind = switch (colour) {
                case 1 -> "purple";
                case 2 -> "blue";
                case 3 -> "red";
                default -> new String[] {"purple", "blue", "red"}[plugin.random().nextInt(3)];
            };
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double distance = 2 + plugin.random().nextDouble() * 6.5;
            final Location at = player.getLocation().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            at.setY(player.getLocation().getY());
            spawnRemnant(player, at, kind, 0, 500);
        }
        if (o.purple > 0 && now >= o.purpleUntil) {
            o.purple = 0;
            dirty = true;
        }
        // 預告射擊（2026-10-28 照 PoE2 Called Shots：主要寶石）：預告射擊自己的冷卻（5 秒，不吃冷卻恢復）好了、孔裡的印記自己的冷卻也好了，
        // 就把它放在範圍內（10 格，吃範圍加成）一個還沒被標記的敵人身上（好幾個照孔的順序輪流）；印記用掉時馬上補放（markConsumed）。
        // 這次之前放面板上第一個印記、間隔固定 5 秒（同一天稍早：間隔吃冷卻恢復、不看印記自己的冷卻）
        if (!markListening && plugin.combat() != null && plugin.combat().debuffs() != null) {
            plugin.combat().debuffs().onMarkConsumed(this::markConsumed);
            markListening = true;
        }
        if (on(player, DeadeyeMetas.CALLED_SHOTS) && now >= o.calledNext && plugin.combat() != null) {
            calledApply(player, o, now);
        }
        // 命運同調：殘影施放面板上一個法術
        if (on(player, "destiny_attune") && now >= o.alignNext) {
            final List<String> spells = alignSpells(state);
            final double base = Math.max(9, level(player, "destiny_attune", "asc.visage_interval"));
            final double speed = Math.max(0.1, 1 + sheet.increased(StatKeys.COOLDOWN_RECOVERY, Set.of())) * (1 + 0.25 * spells.size())
                    * (1 + Math.max(0, level(player, "destiny_attune", "asc.visage_frequency")) / 100.0); // 2026-10-30 照 PoE2：品質更多 (0—20)% 殘影頻率
            o.alignNext = now + Math.round(base * 20 / speed);
            if (!spells.isEmpty()) {
                final String spell = spells.get(plugin.random().nextInt(spells.size()));
                final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), 15);
                if (!near.isEmpty()) {
                    plugin.skills().castForFree(player, spell, near.get(plugin.random().nextInt(near.size())), 1.0, "命途同調");
                    o.alignSkill = spell;
                    o.alignUntil = o.alignNext;
                    tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.ENCHANT, player.getLocation().add(0, 1.5, 0), 25, 0.6, 0.6, 0.6, 0.6);
                }
            }
        }
        // 克拉里的惡言：消耗存在範圍內的屍體，生出屍甲蟲衝向敵人爆炸
        if (on(player, "sand_spirit_venom_word") && on(player, "foul_sand_spirit") && now >= o.beetleNext && plugin.combat() != null) {
            o.beetleNext = now + Math.round(Math.max(0.9, level(player, "sand_spirit_venom_word", "asc.beetle_interval")) * 20);
            final int max = (int) Math.max(4, level(player, "sand_spirit_venom_word", "asc.beetle_max"));
            final tw.exilecore.combat.Corpses.Corpse corpse = plugin.combat().corpses().nearest(player.getWorld().getUID(),
                    player.getLocation().getX(), player.getLocation().getZ(), AscendancyEffects.presence(sheet), now);
            if (corpse != null) {
                plugin.combat().corpses().remove(corpse);
                final Location at = new Location(player.getWorld(), corpse.x(), corpse.y(), corpse.z());
                final List<LivingEntity> near = effects.enemiesNear(player, at, 10);
                for (int i = 0; i < 2 && i < Math.max(1, Math.min(max, near.size())); i++) {
                    final LivingEntity target = near.isEmpty() ? null : near.get(i % near.size());
                    if (target != null) {
                        final long delay = 10 + i * 4L;
                        // 2026-11-01 照 PoE2：屍甲蟲是惡言叫出來的召喚物，爆炸吃惡言上掛的輔助（SkillService.triggerHosted）；這次之前沒有輔助
                        effects.later(delay, () -> plugin.skills().triggerHosted(player, "sand_spirit_venom_word", "asc_corpse_beetle",
                                target.getLocation()));
                    }
                }
                tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.FALLING_DUST, at.clone().add(0, 0.5, 0), 20, 0.4, 0.3, 0.4, 0,
                        org.bukkit.Material.SAND.createBlockData());
            }
        }
        // 女王進軍：衝刺時化成蝗蟲，每 0.5 秒打 3 公尺（4.5 格）內的敵人
        if (on(player, "empress_march")) {
            if (player.isSprinting()) {
                o.sprintEnd = now;
                if (now >= o.swarmHit) {
                    o.swarmHit = now + 10;
                    swarm(player, state, o);
                }
            } else if (o.swarm > 0 && now - o.sprintEnd >= 80) {
                o.swarm = 0;
            }
        }
        // 召喚獄炎獵犬：獵犬身邊 2.5 公尺（3.75 格）的敵人點燃（照獵犬最大生命的 20%）
        if (on(player, "hellfire_dog_call") && AscendancyEffects.every(now, 20) && plugin.skills().minions() != null && plugin.ailments() != null) {
            for (final org.bukkit.entity.Entity entity : player.getNearbyEntities(30, 10, 30)) {
                if (entity instanceof org.bukkit.entity.Wolf wolf && plugin.skills().minions().isMinion(wolf)
                        && player.equals(plugin.skills().minions().owner(wolf))) {
                    final org.bukkit.attribute.AttributeInstance hp = wolf.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    // 2026-10-27 修正：原版血量是縮過的（真正生命 × healthScale，healthScale ≤ 1），要除回去；以前乘 max(1, 縮放) 等於沒還原，
                    // 獵犬生命超過 1024 時點燃一律只照 1024 算
                    final double houndLife = hp == null ? 0 : hp.getValue() / Math.max(1e-6, plugin.skills().minions().healthScale(wolf));
                    // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（獵犬點燃 2.5 公尺；這次之前 2.5 格）
                    for (final LivingEntity enemy : effects.enemiesNear(player, wolf.getLocation(), tw.exilecore.core.Units.metres(2.5))) {
                        plugin.ailments().igniteFrom(player, enemy, houndLife * 0.20, sheet);
                    }
                    tw.exilecore.skill.Fx.spawn(wolf.getWorld(), Particle.FLAME, wolf.getLocation().add(0, 0.5, 0), 6, 1.2, 0.2, 1.2, 0.01);
                }
            }
        }
        // 夏烏拉統治者：每 3 秒觸發夏烏拉之志
        if (o.archonUntil > now && now - o.archonTornado >= 60) {
            o.archonTornado = now;
            final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), 10);
            final Location at = near.isEmpty() ? player.getLocation() : near.getFirst().getLocation();
            plugin.skills().triggerSkill(player, "asc_chayula_will", at);
        }
        // 壞滅災變：每 0.75 秒一種天災
        if (o.apocalypseUntil > now && now >= o.apocalypseNext) {
            o.apocalypseNext = now + Math.max(1, Math.round(15 / (1 + Math.max(0, level(player, "calamity_descent", "asc.apocalypse_frequency")) / 100.0))); // 2026-10-30 照 PoE2：品質增加 (0—15)% 頻率
            final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), 7);
            final Location at = near.isEmpty() ? player.getLocation().add(plugin.random().nextGaussian() * 3, 0, plugin.random().nextGaussian() * 3)
                    : near.get(plugin.random().nextInt(near.size())).getLocation();
            plugin.skills().triggerSkill(player, new String[] {"asc_apocalypse_fire", "asc_apocalypse_cold", "asc_apocalypse_lightning"}[plugin.random().nextInt(3)], at);
        }
        // 惡魔變身：每秒一層惡魔烈焰（最多 10 層；黑暗精通：沒有上限），每層每秒失去生命；剩 1 生命就變回人形
        if (o.demonUntil > now) {
            if (now >= o.demonTick) {
                o.demonTick = now + 20;
                final boolean uncapped = AscendancyEffects.has(sheet, AscKeys.DEMONFLAME_UNCAPPED);
                o.demonflame = uncapped ? o.demonflame + 1 : Math.min(10, o.demonflame + 1);
                dirty = true;
            }
            final double loss = level(player, "become_fiend", "asc.demon_life_loss") * o.demonflame * AscendancyEffects.PERIOD / 20.0;
            if (state.life() - loss <= 1) {
                state.setLife(1);
                endDemon(player, o);
            } else {
                state.setLife(state.life() - loss);
            }
            plugin.players().display(player, state);
            o.demonUntil = Math.max(o.demonUntil, now + AscendancyEffects.PERIOD + 1); // 一直到變回人形
            if (o.demonflame > 0 && plugin.skills() != null) {
                // 2026-11-05 照站長「惡魔烈焰的層數要有地方顯示」：增益列（模組的增益列、沒裝模組的動作條）寫「惡魔烈焰 N 層」；
                //   修飾器只是顯示用的（DEMONFLAME_STACKS 沒有人讀），傷害照舊在擊中時算（上面的 demonflame）。這次之前層數完全看不到
                plugin.skills().buff(player, DEMONFLAME_BUFF, "惡魔烈焰 " + o.demonflame + " 層", (int) AscendancyEffects.PERIOD * 3,
                        List.of(Modifier.flat(DEMONFLAME_STACKS, o.demonflame, "惡魔烈焰")));
            }
            demonBar(player, o.demonflame);
        } else if (demonBars.containsKey(player.getUniqueId())) {
            hideDemonBar(player); // 2026-11-05：保險（變回人形沒走 endDemon 時）
        }
        // 納維拉的綠洲：站在裡面的你與隊友藥劑充能、受到的傷害回復
        for (final Oasis oasis : oases) {
            if (oasis.at().getWorld() == player.getWorld() && oasis.at().distanceSquared(player.getLocation()) <= oasis.radius() * oasis.radius()) {
                plugin.skills().buff(player, "mirage_spirit_haven.in", "娜瑟菈的綠洲", 15, List.of(Modifier.flat(StatKeys.LIFE_RECOUP, oasis.life(), "娜瑟菈的綠洲"),
                        Modifier.flat(StatKeys.MANA_RECOUP, oasis.mana(), "娜瑟菈的綠洲")));
                // 藥劑充能每秒 oasis.flask() 點（這裡每 0.5 秒一次，攢到 1 點給一次）
                final double charge = oasisFlask.merge(player.getUniqueId(), oasis.flask() * AscendancyEffects.PERIOD / 20.0, Double::sum);
                if (charge >= 1 && plugin.flasks() != null) {
                    final int whole = (int) Math.floor(charge);
                    oasisFlask.put(player.getUniqueId(), charge - whole);
                    plugin.flasks().addCharges(player, whole, tw.exilecore.flask.FlaskKind.LIFE);
                    plugin.flasks().addCharges(player, whole, tw.exilecore.flask.FlaskKind.MANA);
                }
                if (AscendancyEffects.every(now, 20)) {
                    tw.exilecore.skill.Fx.spawn(oasis.at().getWorld(), Particle.DRIPPING_WATER, oasis.at().clone().add(0, 1, 0), 20, oasis.radius() / 2, 0.4,
                            oasis.radius() / 2, 0);
                }
                break; // 2026-11-01：好幾片綠洲重疊時只算一片（藥劑充能不重複給）
            }
        }
        if (dirty) {
            plugin.players().markDirty(player);
        }
    }

    /** 女王進軍：打一下蝗蟲群。 */
    private void swarm(final Player player, final PlayerState state, final Own own) {
        if (plugin.combat() == null || plugin.combat().skillDamage() == null) {
            return;
        }
        // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（蝗蟲群 3 公尺；這次之前 3 格）
        final List<LivingEntity> near = effects.enemiesNear(player, player.getLocation(), tw.exilecore.core.Units.metres(3) * areaScale(player)); // 2026-10-31：吃範圍效果
        tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.ASH, player.getLocation().add(0, 1, 0), 30, 1.5, 0.8, 1.5, 0);
        if (near.isEmpty()) {
            return;
        }
        final double min = level(player, "empress_march", "asc.swarm_min");
        final double max = level(player, "empress_march", "asc.swarm_max");
        final double amount = (min + plugin.random().nextDouble() * Math.max(0, max - min)) * (1 + 0.02 * own.swarm);
        final SkillProfile profile = new SkillProfile("asc_swarm", Set.of(Tags.ATTACK, Tags.AREA, Tags.PHYSICAL), 1.0,
                new DamagePacket().set(DamageType.PHYSICAL, amount));
        for (final LivingEntity enemy : near) {
            plugin.combat().skillDamage().hit(player, enemy, profile, null);
        }
        // 打中時：你受到一點物理傷害、獲得一層蟲群前進
        own.swarm = Math.min(50, own.swarm + 1);
        effects.selfHit(player, state, new DamagePacket().set(DamageType.PHYSICAL, level(player, "empress_march", "asc.swarm_self")), "女皇巡行");
    }

    /** 高尚禦盾：每種寶珠的上限 = 3 ＋ 面板上需要那種屬性的技能數（我們的寶石只有一種顏色：算單一屬性，兩倍）。 */
    private int[] moteCaps(final PlayerState state) {
        final int[] caps = {3, 3, 3};
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry == null) {
                continue;
            }
            final int colour = AscBuild.skillColour(entry.skillId());
            if (colour >= 0) {
                caps[colour] += 2;
            }
        }
        return caps;
    }

    // =====================================================================
    // 星矢的主要寶石（2026-10-28 照 PoE2 0.5）
    // =====================================================================

    /** 印記被用掉的通知登記了沒。 */
    private boolean markListening;

    /** 預告射擊放一次孔裡的印記（預告射擊、印記自己的冷卻都好了才放）；放了就重新開始預告射擊的冷卻。回傳有沒有放。 */
    private boolean calledApply(final Player player, final Own o, final long now) {
        final List<SkillBuild> marks = plugin.skills().deadeyeSocketed(player, DeadeyeMetas.CALLED_SHOTS);
        if (marks.isEmpty() || plugin.combat() == null) {
            return false;
        }
        final SkillBuild host = plugin.skills().deadeyeHost(player, DeadeyeMetas.CALLED_SHOTS);
        final double radius = DeadeyeMetas.CALLED_RADIUS * (host == null ? 1.0 : host.areaScale());
        for (int i = 0; i < marks.size(); i++) {
            final SkillBuild mark = marks.get(Math.floorMod(o.calledIndex + i, marks.size()));
            if (plugin.skills().cooldownLeft(player, mark.definition().id()) > 0) {
                continue;
            }
            for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), radius)) {
                if (!plugin.combat().debuffs().marked(enemy.getUniqueId(), now) && plugin.skills().castSocketedMark(player, mark, enemy, true)) {
                    o.calledIndex += i + 1;
                    o.calledNext = now + DeadeyeMetas.ticks(Math.max(1, level(player, DeadeyeMetas.CALLED_SHOTS, "asc.called_seconds")));
                    return true;
                }
            }
            return false; // 範圍內沒有沒被標記的敵人
        }
        return false;
    }

    /** 印記被用掉（Debuffs.remove）：放的人開著預告射擊、是孔裡的印記，下一 tick 擴散、補放。 */
    private void markConsumed(final UUID target, final String markId, final UUID source, final long now) {
        final Player player = plugin.getServer().getPlayer(source);
        if (player == null || !player.isOnline() || plugin.skills() == null || !on(player, DeadeyeMetas.CALLED_SHOTS)) {
            return;
        }
        effects.later(1, () -> calledConsumed(player, target, markId));
    }

    /**
     * 2026-10-28 照 PoE2 Called Shots：孔裡的印記被用掉——
     * 擴散（SupportMetaDeadeyeMarksPlayer「Supported Skills will Mark another Enemy within 3 Metres of Marked Enemy when Consumed」，100%）：
     * 被用掉那隻 3 格內另一個還沒被標記的敵人；
     * 補放（「Consuming socketed Marks will cause them to be reapplied to an unmarked Enemy. This reapplication can happen at most once every few seconds」）：
     * 預告射擊的冷卻好了就馬上放到一個沒被標記的敵人身上。
     */
    private void calledConsumed(final Player player, final UUID targetId, final String markId) {
        if (!player.isOnline() || plugin.combat() == null || !on(player, DeadeyeMetas.CALLED_SHOTS)) {
            return;
        }
        SkillBuild mark = null;
        for (final SkillBuild socketed : plugin.skills().deadeyeSocketed(player, DeadeyeMetas.CALLED_SHOTS)) {
            if (socketed.definition().id().equals(markId)) {
                mark = socketed;
                break;
            }
        }
        if (mark == null) {
            return;
        }
        final long now = now();
        final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(targetId);
        if (entity instanceof LivingEntity consumed && consumed.getWorld() == player.getWorld()) {
            for (final LivingEntity enemy : effects.enemiesNear(player, consumed.getLocation(), DeadeyeMetas.SPREAD_RADIUS)) {
                if (!enemy.equals(consumed) && !plugin.combat().debuffs().marked(enemy.getUniqueId(), now)) {
                    plugin.skills().castSocketedMark(player, mark, enemy, false);
                    break;
                }
            }
        }
        final Own o = own(player);
        if (now >= o.calledNext) {
            calledApply(player, o, now);
        }
    }

    /**
     * 2026-11-08 照站長「幻弓影照 0.5 原作重做」（PoE2 Mirage Archer：While active, dodge rolling will create a Mirage）：翻滾時
     * 幻弓影開著、拿著弓／弩／矛、孔裡有能用的攻擊、幻影冷卻好了，就在你翻滾的起點留下幻影（DodgeRoll 呼叫）。
     */
    public void dodged(final Player player) {
        final Own own = owns.computeIfAbsent(player.getUniqueId(), ignored -> new Own());
        spawnMirage(player, own, DeadeyeMetas.ARCHER, now());
    }

    /**
     * 幻影銳眼（你發射遠程投射物攻擊時）、幻弓影（翻滾時）留下幻影：冷卻好了、拿著對的武器、孔裡有攻擊才留。
     * 持續時間照主要寶石的等級表（asc.mirage_seconds）＋品質，吃長留與持續時間加成；冷卻 10 秒吃迅返與冷卻回復。
     */
    private void spawnMirage(final Player player, final Own own, final String hostId, final long now) {
        final Mirage m = mirage(own, hostId);
        final PlayerState state = plugin.players().get(player);
        if (!on(player, hostId) || now < m.ready || m.until > now || plugin.skills() == null || !mirageWeapon(player, state, hostId)
                || plugin.skills().deadeyeSocketed(player, hostId).isEmpty()) {
            return;
        }
        final SkillBuild host = plugin.skills().deadeyeHost(player, hostId);
        final StatSheet effective = host == null ? null : host.profile().effective(state.sheet());
        final Set<String> context = host == null ? Set.of() : host.profile().tags();
        final boolean archer = DeadeyeMetas.ARCHER.equals(hostId);
        final double base = archer
                ? plugin.skills().levelValue(hostId, "asc.mirage_seconds", plugin.skills().entryLevel(player, hostId))
                + (host == null ? 0 : host.qualityFlat("asc.mirage_seconds"))
                : level(player, DeadeyeMetas.MIRAGE, "asc.mirage_seconds");
        final double seconds = Math.max(archer ? 5 : 4, base) * DeadeyeMetas.durationFactor(effective, context);
        m.at = player.getLocation().clone();
        m.until = now + DeadeyeMetas.ticks(seconds);
        m.ready = now + DeadeyeMetas.ticks((archer ? DeadeyeMetas.ARCHER_COOLDOWN : DeadeyeMetas.MIRAGE_COOLDOWN) * DeadeyeMetas.cooldownFactor(effective, context));
        m.cooldowns.clear();
        final int generation = ++m.generation;
        effects.later(6, () -> mirageStep(player, hostId, generation));
        tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.CLOUD, m.at.clone().add(0, 1, 0), 20, 0.3, 0.8, 0.3, 0.01);
    }

    /**
     * 2026-10-28 照 PoE2 Mirage Deadeye：幻影的下一步——照孔的順序挑一個（這個幻影自己的冷卻好了的）攻擊，朝最近的敵人用；
     * 引導／蓄力的先站著引導（疊滿、有完美時機的在完美時機放開），幻影先消失就沒放出去。2026-11-08：幻弓影也用這套。
     */
    private void mirageStep(final Player player, final String hostId, final int generation) {
        final Own o = owns.get(player.getUniqueId());
        if (o == null || plugin.skills() == null) {
            return;
        }
        final Mirage m = mirage(o, hostId);
        if (m.generation != generation) {
            return;
        }
        final long now = now();
        if (!player.isOnline() || m.until <= now || m.at == null || m.at.getWorld() != player.getWorld()
                || !on(player, hostId) || !mirageWeapon(player, plugin.players().get(player), hostId)) {
            m.until = Math.min(m.until, now);
            return;
        }
        final List<SkillBuild> attacks = plugin.skills().deadeyeSocketed(player, hostId);
        SkillBuild attack = null;
        long wait = 5;
        if (!attacks.isEmpty() && !effects.enemiesNear(player, m.at, DeadeyeMetas.MIRAGE_RANGE).isEmpty()) {
            long soonest = Long.MAX_VALUE;
            for (int i = 0; i < attacks.size(); i++) {
                final SkillBuild candidate = attacks.get(Math.floorMod(m.index + i, attacks.size()));
                final Long ready = m.cooldowns.get(candidate.definition().id());
                if (ready == null || ready <= now) {
                    attack = candidate;
                    m.index += i + 1;
                    break;
                }
                soonest = Math.min(soonest, ready - now);
            }
            if (attack == null) {
                wait = Math.max(1, Math.min(20, soonest));
            }
        }
        if (attack == null) {
            effects.later(wait, () -> mirageStep(player, hostId, generation));
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final tw.exilecore.skill.SkillService.MirageRelease release = plugin.skills().mirageRelease(player, attack);
        final SkillBuild fired = DeadeyeMetas.mirageBuild(release.build(), hostId);
        if (attack.cooldownTicks() > 0) {
            m.cooldowns.put(attack.definition().id(), now + release.holdTicks() + attack.cooldownTicks());
        }
        final long gap = Math.max(4, Math.round(attack.intervalTicks(sheet)));
        if (release.holdTicks() > 0) {
            final Location at = m.at.clone().add(0, 1.2, 0);
            for (long t = 0; t < release.holdTicks(); t += 5) {
                effects.later(t, () -> {
                    if (m.generation == generation && m.until > now()) {
                        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.ENCHANT, at, 6, 0.3, 0.4, 0.3, 0.3);
                    }
                });
            }
            effects.later(release.holdTicks(), () -> mirageFire(player, hostId, generation, fired));
            effects.later(release.holdTicks() + 4, () -> mirageStep(player, hostId, generation));
        } else {
            mirageFire(player, hostId, generation, fired);
            effects.later(gap, () -> mirageStep(player, hostId, generation));
        }
    }

    /**
     * 2026-11-01 照 PoE2 Mirage Deadeye（weaponTypes：Bow、Crossbow、Spear）：拿著弓、弩或矛才會留下幻影，換成別的武器幻影就散掉
     * （skills.yml 的 weapons；開技能時 SkillService 也擋）。這次之前什麼武器都行。2026-11-08：幻弓影也是。
     */
    private boolean mirageWeapon(final Player player, final PlayerState state, final String hostId) {
        final SkillDefinition mirage = plugin.skills() == null ? null : plugin.skills().config().skill(hostId);
        return mirage == null || state == null || mirage.usableWith(state.weaponTags());
    }

    /** 幻影放出那一下（朝幻影最近的敵人）；幻影已經消失就不放。 */
    private void mirageFire(final Player player, final String hostId, final int generation, final SkillBuild fired) {
        final Own o = owns.get(player.getUniqueId());
        final long now = now();
        if (o == null) {
            return;
        }
        final Mirage m = mirage(o, hostId);
        if (m.generation != generation || m.until <= now || m.at == null || !player.isOnline() || m.at.getWorld() != player.getWorld()) {
            return;
        }
        final List<LivingEntity> near = effects.enemiesNear(player, m.at, DeadeyeMetas.MIRAGE_RANGE);
        if (near.isEmpty()) {
            return;
        }
        LivingEntity target = near.getFirst();
        for (final LivingEntity enemy : near) {
            if (enemy.getLocation().distanceSquared(m.at) < target.getLocation().distanceSquared(m.at)) {
                target = enemy;
            }
        }
        final Location from = m.at.clone();
        plugin.skills().recast(player, fired, from, target.getLocation().toVector().subtract(from.toVector()).normalize(), target);
        // 2026-10-31：霆落矛改成投射物，幻影擲的矛停下來時自己插地（SkillService.lanceLanded）；
        // 這次之前（2026-10-30）這裡呼叫 proxyLanded 插在目標腳下
        tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.END_ROD, from.clone().add(0, 1, 0), 8, 0.2, 0.5, 0.2, 0.01);
    }

    private List<String> alignSpells(final PlayerState state) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            final SkillDefinition definition = entry == null ? null : plugin.skills().config().skill(entry.skillId());
            if (definition == null || definition.hidden() || definition.isAttack() || !definition.tags().contains(Tags.SPELL)
                    || definition.cooldown() > 0 || !definition.dealsDamage() || definition.tags().contains("channelling")
                    || definition.tags().contains("command") || definition.mechanic() == Mechanic.AURA || definition.mechanic() == Mechanic.WARCRY
                    || definition.mechanic() == Mechanic.SUMMON || definition.mechanic() == Mechanic.META) {
                continue;
            }
            out.add(definition.id());
        }
        return out;
    }

    /** 這些技能給的暫時修飾器（高尚禦盾的寶珠、紫色夏烏拉之炎）。 */
    List<Modifier> buffModifiers(final Player player, final PlayerState state) {
        final Own own = owns.get(player.getUniqueId());
        if (own == null) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        if (own.motes[0] > 0) {
            out.add(Modifier.increased(StatKeys.LIFE_MAX, 0.02 * own.motes[0], "正氣護盾"));
        }
        if (own.motes[1] > 0) {
            out.add(Modifier.increased(StatKeys.ARMOUR, 0.05 * own.motes[1], "正氣護盾"));
            out.add(Modifier.increased(StatKeys.EVASION, 0.05 * own.motes[1], "正氣護盾"));
            out.add(Modifier.increased(StatKeys.ES_MAX, 0.05 * own.motes[1], "正氣護盾"));
        }
        if (own.motes[2] > 0) {
            final double regen = 0.05 + Math.max(0, level(player, "righteous_shield", "asc.mote_regen")) / 100.0; // 2026-10-30 照 PoE2：品質每顆 +(0—2.5)%
            out.add(Modifier.increased(StatKeys.LIFE_REGEN, regen * own.motes[2], "正氣護盾"));
            out.add(Modifier.increased(StatKeys.MANA_REGEN, regen * own.motes[2], "正氣護盾"));
        }
        if (own.purple > 0) {
            final double effect = 1 + state.sheet().increased(AscKeys.REMNANT_EFFECT, Set.of()) + flameEffect(player, "purple");
            out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + Tags.CHAOS, FLAME_PURPLE * own.purple * effect, "霞瑤之炎"));
        }
        return out;
    }

    /** 元素展現：照力量、敏捷、智慧的比例選一種（火、電、冰）。 */
    static String expressionFor(final AscendancyEffects effects, final Player player) {
        final StatSheet sheet = effects.sheet(player);
        final double str = Math.max(0, sheet.get(StatKeys.STR));
        final double dex = Math.max(0, sheet.get(StatKeys.DEX));
        final double intelligence = Math.max(0, sheet.get(StatKeys.INT));
        final double total = str + dex + intelligence;
        final double roll = effects.plugin.random().nextDouble() * Math.max(1e-9, total);
        if (total <= 0 || roll < str) {
            return "asc_expression_fire";
        }
        return roll < str + dex ? "asc_expression_lightning" : "asc_expression_cold";
    }

    /** 衝刺突擊：衝刺時無法被重度暈眩（衝刺開始、結束時重算屬性表）。 */
    @EventHandler
    public void onSprint(final PlayerToggleSprintEvent event) {
        if (AscendancyEffects.has(effects.sheet(event.getPlayer()), AscKeys.SPRINT_STUN) || on(event.getPlayer(), "empress_march")) {
            plugin.players().markDirty(event.getPlayer());
        }
    }
}
