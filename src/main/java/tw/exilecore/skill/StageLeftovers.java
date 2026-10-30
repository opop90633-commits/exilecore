package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentConfig;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-28 照 PoE2 0.5（RePoE skills.json 的 stat_sets、keywords）：前幾輪分段傷害之後剩下的機制。這次之前這些不是沒做，就是平衡等效。
 * <ul>
 *   <li>引霆突（Gathering Storm）：完美突進打到驚雷之鐘把鐘炸掉，放雷暴震波（Tempest Shockwave，自己的表、3.2 公尺）＋感電地面。</li>
 *   <li>驚雷之鐘：鐘的異常門檻、暈眩門檻（bell_ailment_threshold／bell_poise_threshold）——打到鐘的那一下讓鐘點燃、感電、冰緩、凍結，
 *       鐘身上的元素異常讓震波額外獲得 30% 物理為那一種；會擊退的那一下讓震波每 0.1 公尺擊退更多 30% 範圍。</li>
 *   <li>寒魄掌（Shattering Palm）：冰片掛在 3.8 公尺內的敵人身上，你對牠造成的擊中傷害累積到門檻才碎。</li>
 *   <li>血獵（Blood Hunt）：用掉的失血每 X 點更多 10% 範圍（最多 500%），被炸到的失去用掉失血 25% 的生命。</li>
 *   <li>萬箭齊落：箭雨自己排（圖騰、觸發放的一樣落全部的箭）、同一隻每 0.12 秒最多被打一次、同時一場箭雨。</li>
 *   <li>土壁擊：牆的每一段有生命，你的猛擊／戰吼／衝撞讓牠崩碎（更多 100%），敵人攻擊扣完生命也崩碎。</li>
 *   <li>熾陽之矛的點燃地面、奔雷踏的鋸齒地面、回旋鍛錘的熔岩裂地、颶風槍的旋風（緩速、致盲、階數、你跨過邊緣崩散）、
 *       霜牙刺的冰緩地面、三連刺矛頭的癱瘓、穿甲重彈的裂甲。</li>
 *   <li>雷雲施法時多放一道雷（SkillZones）、地火柱的引導與開場爆發、天柱雷每 5% 感電效果更多 10%、寒晶雷脈衝的元素曝露。</li>
 * </ul>
 * 純邏輯的部分是 static（單元測試）；其他依賴 Bukkit。
 */
public final class StageLeftovers implements Listener {

    // ---- 技能代號 ----
    static final String GATHERING = "gathering_storm";
    static final String TEMPEST_SHOCKWAVE = "proc_storm_tempest_shockwave";
    static final String TEMPEST_GROUND = "proc_storm_shocked_ground";
    static final String PALM = "frost_palm";
    static final String PALM_BURST = "proc_shattering_burst";
    static final String BLOOD = "blood_harvest";
    static final String BLOOD_BURST = "proc_blood_hunt_burst";
    static final String RAIN = "rain_of_arrows";
    static final String SHIELD_WALL = "shield_wall";
    static final String SHIELD_RUSH = "shield_rush";
    static final String SOLARIS = "sunfire_spear";
    static final String STAMPEDE = "stampede";
    static final String FORGE = "forge_hammer";
    static final String WHIRLWIND_LANCE = "whirlwind_lance";
    static final String FANGS = "fangs_of_frost";
    static final String FANGS_GROUND = "proc_fangs_chilled_ground";
    static final String HVR = "piercing_rounds";
    static final String ORB = "storm_orb";
    static final String VOLCANO = "volcano";
    static final String FROST_BOMB = "frost_mine";
    static final String TOXIC_GROWTH = "toxic_growth";

    // ---- 屬性鍵（skills.yml 的 modifiers／level_modifiers；ModifierText 用 describe 顯示）----
    /** FLAT：照目標身上的感電效果，每 5% 更多 x% 傷害（天柱雷 10）。 */
    public static final String MORE_PER_5_SHOCK = "damage.more_per_5_shock";
    /** 地火柱引導了幾階（Signatures 的 stage_stat）。 */
    static final String VOLCANO_STAGES = "volcano.stages";
    /** 這次萬箭齊落用掉了怒星（StageAttacks 放的記號）。 */
    static final String RAIN_FRENZY = "rain.frenzy";
    /** 這次颶風槍用掉了怒星。 */
    static final String WHIRLWIND_FRENZY = "whirlwind.frenzy";
    static final String PUSTULE_MORE_PER = "pustule.more_per";
    static final String PUSTULE_RADIUS_PER = "pustule.radius_per";
    static final String PUSTULE_FASTER_PER = "pustule.faster_per";

    // ---- PoE2 的固定數值 ----
    /** 驚雷之鐘：每種元素異常獲得 30% 物理為額外那一種；每 0.1 公尺擊退更多 30% 範圍。 */
    static final double BELL_GAIN = 30;
    static final double BELL_AREA_PER_TENTH = 0.30;
    /** 鐘身上的異常持續（PoE2 預設：點燃、感電、凍結 4 秒，冰緩 2 秒）。 */
    static final long BELL_IGNITE_TICKS = 80;
    static final long BELL_SHOCK_TICKS = 80;
    static final long BELL_CHILL_TICKS = 40;
    static final long BELL_FREEZE_TICKS = 80;
    /** 寒魄掌：冰片掛在 3.8 公尺內的敵人身上；原作沒寫冰片多久，照 PoE2 減益的預設 4 秒。 */
    static final double PALM_RADIUS = tw.exilecore.core.Units.metres(3.8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long PALM_TICKS = 80;
    /** 血獵：每 X 點失血更多 10% 範圍、最多 500%；被炸到的失去用掉失血 25% 的生命；至少要 1 點失血才炸。 */
    static final double BLOOD_AREA_STEP = 10;
    static final double BLOOD_AREA_CAP = 500;
    static final double BLOOD_LIFE_LOSS = 0.25;
    static final double BLOOD_MIN = 1;
    /** 萬箭齊落：同一隻每 0.12 秒最多被打一次（2.4 tick → 隔 3 tick）。 */
    static final double RAIN_HIT_GAP_SECONDS = 0.12;
    /** 土壁擊：崩碎時往前炸 4 公尺、周圍 1.4 公尺；被你自己打碎更多 100%；敵人每秒攻擊牆一次。 */
    static final double WALL_LENGTH = tw.exilecore.core.Units.metres(4.0); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double WALL_AROUND = tw.exilecore.core.Units.metres(1.4); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double WALL_SELF_MORE = 1.0;
    static final double WALL_TOUCH = 1.2;
    /** 熾陽之矛：點燃地面跟著光矛的時間擴大到半徑更多 100%。 */
    static final double SOLARIS_GROW = 1.0;
    /** 奔雷踏：每一步隔多遠、鋸齒地面緩速 20%、終點猛擊炸 3.2 公尺內的鋸齒地面。 */
    static final double STAMPEDE_STEP = 1.6;
    static final double JAGGED_SLOW = 0.20;
    static final double STAMPEDE_EXPLODE_RADIUS = tw.exilecore.core.Units.metres(3.2); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 回旋鍛錘：鍛錘的印記 12 秒；每道裂地 4 公尺長。 */
    static final double FORGE_SECONDS = 12;
    static final double FISSURE_LENGTH = tw.exilecore.core.Units.metres(4.0); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 颶風槍：上限 3 階（怒星 4 階）、每多一階更多 150%、半徑 +0.3 公尺、擊退更多 10%；緩速 30%；最多 6 個旋風。 */
    static final int WHIRL_MAX_STAGE = 3;
    static final double WHIRL_STAGE_MORE = 1.5;
    static final double WHIRL_STAGE_RADIUS = tw.exilecore.core.Units.metres(0.3); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double WHIRL_STAGE_KNOCKBACK = 0.1;
    static final double WHIRL_SLOW = 0.30;
    static final int WHIRL_LIMIT = 6;
    /** 三連刺矛頭的癱瘓：移動更慢 30%、閃避減少 15%。 */
    static final double MAIM_SLOW = 0.30;
    static final double MAIM_EVASION = 0.15;
    /** 穿甲重彈的裂甲：存下減免前物理傷害的 5%，5 秒。 */
    static final double RIVEN_SHARE = 0.05;
    static final long RIVEN_TICKS = 100;
    /** 地火柱：每多一階開場爆發更多 150%、多噴 4 顆。 */
    static final double VOLCANO_STAGE_MORE = 1.5;
    static final int VOLCANO_STAGE_PROJECTILES = 4;
    /** 寒晶雷：曝露 20% 起、每一下 +2%、最多 50%、8 秒；每 0.25 秒脈衝一次（原作沒寫，照疊滿的時間推）。 */
    static final double BOMB_EXPOSURE_START = 20;
    static final double BOMB_EXPOSURE_STEP = 2;
    static final double BOMB_EXPOSURE_CAP = 50;
    static final long BOMB_EXPOSURE_TICKS = 160;
    static final long BOMB_PULSE_TICKS = 5;
    /** 雷雲：施法的地方離雷雲多近才多放一道（雷雲半徑 3.6 公尺）。 */
    static final double ORB_NEAR = tw.exilecore.core.Units.metres(3.6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算

    // ================================================================ 純邏輯（單元測試）

    /** 鐘的震波範圍倍率：觸發的那一下會擊退 metres 公尺（每 0.1 公尺更多 30%）。 */
    static double bellArea(final double metres) {
        return metres <= 0 ? 1.0 : 1 + BELL_AREA_PER_TENTH * metres / 0.1;
    }

    /** 鐘身上有這些元素異常時震波多的修飾器：每一種獲得 30% 物理為額外那一種。 */
    static List<Modifier> bellGains(final Set<DamageType> ailed) {
        final List<Modifier> out = new ArrayList<>();
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
            if (ailed.contains(type)) {
                out.add(Modifier.flat(StatKeys.damageGain(type), BELL_GAIN, "鐘的異常"));
            }
        }
        return out;
    }

    /** 血獵：用掉 consumed 點失血，範圍更多幾 %（每 per 點 10%，最多 500%）。 */
    static double bloodArea(final double consumed, final double per) {
        if (per <= 0 || consumed <= 0) {
            return 0;
        }
        return Math.min(BLOOD_AREA_CAP, BLOOD_AREA_STEP * Math.floor(consumed / per + 1e-9));
    }

    /** 毒苞的倒數速度（1 = 一般）：存的毒 × 60 每 per 點快 1%。 */
    static double pustuleRate(final double stored, final double per) {
        return per <= 0 || stored <= 0 ? 1.0 : 1 + stored * 60 / per / 100.0;
    }

    /** 箭雨的這一下能不能打這隻（上一次打牠是 last tick；null 沒打過）。 */
    static boolean rainGate(final Long last, final long now) {
        return last == null || (now - last) / 20.0 >= RAIN_HIT_GAP_SECONDS - 1e-9;
    }

    /** 寒晶雷第 pulse 下（1 起）的曝露 %。 */
    static double bombExposure(final int pulse) {
        return bombExposure(pulse, 0);
    }

    /** 2026-10-28 照 PoE2 Frost Bomb 的品質：曝露疊加的上限 +extraCap。 */
    static double bombExposure(final int pulse, final double extraCap) {
        return pulse <= 0 ? 0 : Math.min(BOMB_EXPOSURE_CAP + Math.max(0, extraCap), BOMB_EXPOSURE_START + BOMB_EXPOSURE_STEP * (pulse - 1));
    }

    /** 天柱雷：目標的感電效果（小數，0.2 = 受到的傷害增加 20%）→ 更多幾（小數）；每 5% 更多 per%（無條件捨去）。 */
    static double conduitMore(final double shock, final double per) {
        if (shock <= 0 || per <= 0) {
            return 0;
        }
        return Math.floor(shock * 100 / 5 + 1e-9) * per / 100.0;
    }

    /** 地火柱：引導 stages 階，開場爆發更多幾（小數）。 */
    static double volcanoMore(final int stages) {
        return VOLCANO_STAGE_MORE * Math.max(0, stages - 1);
    }

    /** 地火柱：開場爆發噴幾顆（base＝一般一波的顆數）。 */
    static int volcanoProjectiles(final int base, final int stages) {
        return Math.max(0, base) + VOLCANO_STAGE_PROJECTILES * Math.max(0, stages - 1);
    }

    /** 颶風槍：新的旋風疊在舊的上面時舊的變幾階；怒星直接 4 階。 */
    static int whirlStage(final int existing, final boolean frenzy) {
        final int grown = existing <= 0 ? 1 : Math.min(WHIRL_MAX_STAGE, existing + 1);
        return frenzy ? Math.max(grown, WHIRL_MAX_STAGE + 1) : Math.max(existing, grown);
    }

    /** 颶風槍：這一階崩散更多幾（小數）。 */
    static double whirlMore(final int stage) {
        return WHIRL_STAGE_MORE * Math.max(0, stage - 1);
    }

    /** 颶風槍：這一階的半徑（base 已乘範圍）。 */
    static double whirlRadius(final double base, final int stage, final double areaScale) {
        return base + WHIRL_STAGE_RADIUS * Math.max(0, stage - 1) * areaScale;
    }

    /** 熾陽之矛：點燃地面在 fraction（0–1）的時候半徑幾公尺。 */
    static double solarisRadius(final double base, final double fraction) {
        return base * (1 + SOLARIS_GROW * Math.max(0, Math.min(1, fraction)));
    }

    /** 回旋鍛錘的裂地：count 道，從 start 方向開始平均散開（弧度），每一道再往同一個方向轉一點（螺旋）。 */
    static List<double[]> spiral(final int count, final double start) {
        final List<double[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double angle = start + Math.PI * 2 * i / Math.max(1, count);
            out.add(new double[] {Math.cos(angle), Math.sin(angle), Math.cos(angle + 0.6), Math.sin(angle + 0.6)});
        }
        return out;
    }

    /** 冰晶、土牆的生命（表上的生命，照「冰晶要打幾下」的輔助比例放大縮小）。 */
    static double scaledLife(final double life, final int hits, final int baseHits) {
        return life <= 0 ? 0 : life * Math.max(1, hits) / Math.max(1, baseHits);
    }

    /** 裂甲：那一下減免前的物理傷害 → 存多少。 */
    static double rivenAmount(final double physical) {
        return Math.max(0, physical) * RIVEN_SHARE;
    }

    /** skills.yml 的屬性說明（ModifierText 不認得的時候問這裡）；不是這裡的回傳 null。 */
    public static String describe(final String stat, final double value) {
        final String number = value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
        return switch (stat) {
            case MORE_PER_5_SHOCK -> "照敵人身上的感電效果，每 5% 更多 " + number + "% 傷害";
            case "limit.crystal_life" -> "冰晶的最大生命 " + number;
            case "limit.wall_life" -> "土牆每一段的最大生命 " + number;
            case "limit.bell_ailment" -> "鐘的異常門檻 " + number;
            case "limit.bell_poise" -> "鐘的暈眩門檻 " + number;
            case "limit.palm_threshold" -> "冰片碎開的傷害門檻 " + number;
            case "limit.blood_per" -> "每用掉 " + number + " 點失血，血爆範圍更多 10%（最多 500%）";
            case "limit.exposure_level" -> "不會曝露等級高於 " + number + " 的敵人";
            case PUSTULE_MORE_PER -> "毒苞每存 " + number + " 點毒，爆炸更多 1% 傷害（最多 300%）";
            case PUSTULE_RADIUS_PER -> "毒苞每存 " + number + " 點毒，爆炸半徑 +0.1 格（最多 +1.5 格）"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（這次之前「+0.1 公尺（最多 +1.5）」）
            case PUSTULE_FASTER_PER -> "毒苞存的毒 × 60 每 " + number + " 點，倒數快 1%";
            default -> null;
        };
    }

    // ================================================================ 執行期

    private final ExileCore plugin;
    private final SkillService service;
    private int timer = -1;

    StageLeftovers(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        service.targets().addStrikeable(this::struck);
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private StatSheet sheet(final Player player) {
        return plugin.players().sheet(player);
    }

    /** 面板上這個技能這一下的數值（含輔助）；不在面板上回傳 null。 */
    private SkillBuild current(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        final SkillDefinition definition = service.config().skill(skillId);
        if (position < 0 || definition == null) {
            return null;
        }
        return service.build(state, sheet(player), definition, state.skills().skill(position));
    }

    /** 這一下打出去（減免前）的傷害，對一個沒有防禦的目標估（冰晶、鐘、毒苞這種東西用）。 */
    DamagePacket outgoing(final Player player, final SkillProfile skill) {
        try {
            return DamageCalculator.calculate(sheet(player), skill, new StatSheet(), 0, plugin.random()).outgoing();
        } catch (final RuntimeException ignored) {
            return new DamagePacket();
        }
    }

    // ---------------------------------------------------------------- 驚雷之鐘、引霆突

    /**
     * 你的技能打到你的一口鐘（SkillDeployables：打中鐘旁邊的敵人，或技能範圍蓋到鐘）。
     * 完美時機的引霆突把鐘炸掉（回傳 true，呼叫的地方拿掉鐘）；其他的照鐘的門檻上異常、記下擊退的範圍。
     */
    boolean bellHit(final Player owner, final SkillDeployables.Deployed bell, final SkillProfile skill, final DamagePacket hit, final long now) {
        if (GATHERING.equals(SupportProcs.baseId(skill.id())) && skill.id().endsWith(SupportProcs.PERFECT_SUFFIX)) {
            tempest(owner, bell.feet.clone());
            return true;
        }
        final SkillProfile own = bell.build.profile();
        final double threshold = StageAttacks.limit(own, "bell_ailment", 0);
        final double poise = StageAttacks.limit(own, "bell_poise", 0);
        if (threshold <= 0 || plugin.ailments() == null) {
            return false;
        }
        final DamagePacket packet = hit != null ? hit : outgoing(owner, skill);
        final StatSheet effective = skill.effective(sheet(owner));
        final AilmentConfig config = plugin.ailments().config();
        final Set<String> fire = Set.of(Tags.FIRE);
        final Set<String> lightning = Set.of(Tags.LIGHTNING);
        final Set<String> cold = Set.of(Tags.COLD);
        if (!StatKeys.flag(effective, StatKeys.FLAG_NO_AILMENTS)) {
            final double ignite = config.flammability(packet.get(DamageType.FIRE), threshold, effective.flat(StatKeys.IGNITE_CHANCE, fire),
                    effective.increased(StatKeys.IGNITE_CHANCE, fire));
            if (ignite > 0 && plugin.random().nextDouble() * 100 < ignite) {
                bell.ailed.put(DamageType.FIRE, now + BELL_IGNITE_TICKS);
            }
            final double shock = config.shockChance(packet.get(DamageType.LIGHTNING), threshold, effective.flat(StatKeys.SHOCK_CHANCE, lightning),
                    effective.increased(StatKeys.SHOCK_CHANCE, lightning));
            if (shock > 0 && plugin.random().nextDouble() < shock) {
                bell.ailed.put(DamageType.LIGHTNING, now + BELL_SHOCK_TICKS);
            }
            final double coldDamage = packet.get(DamageType.COLD);
            if (config.chillMagnitude(coldDamage, threshold, effective.increased(StatKeys.CHILL_EFFECT, cold)) > 0) {
                bell.ailed.put(DamageType.COLD, Math.max(bell.ailed.getOrDefault(DamageType.COLD, 0L), now + BELL_CHILL_TICKS));
            }
            if (poise > 0 && coldDamage > 0) {
                bell.freezeBuild += config.freezeBuildup(coldDamage, poise, effective.apply(StatKeys.FREEZE_BUILDUP, 1.0, cold), false);
                if (bell.freezeBuild >= 1) {
                    bell.freezeBuild = 0;
                    bell.ailed.put(DamageType.COLD, now + BELL_FREEZE_TICKS);
                }
            }
        }
        final double knockback = knockbackOf(owner, skill);
        if (knockback > 0) {
            bell.ringArea = Math.max(bell.ringArea, bellArea(knockback));
        }
        return false;
    }

    /** 鐘響時（SkillDeployables.ring）：震波照鐘身上的異常多拿元素傷害。 */
    static SkillProfile bellShockwave(final SkillDeployables.Deployed bell, final SkillProfile profile, final long now) {
        bell.ailed.values().removeIf(until -> until <= now);
        if (bell.ailed.isEmpty()) {
            return profile;
        }
        return profile.withLocal(bellGains(EnumSet.copyOf(bell.ailed.keySet())));
    }

    /** 這一下會擊退幾格（技能本身的擊退＋擊退的加成）。 */
    private double knockbackOf(final Player owner, final SkillProfile skill) {
        final SkillDefinition definition = service.config().skill(SupportProcs.baseId(skill.id()));
        final double base = definition == null ? 0 : definition.params().knockback();
        final StatSheet effective = skill.effective(sheet(owner));
        return Math.max(0, effective.apply(StatKeys.SKILL_KNOCKBACK, base + effective.flat(StatKeys.SKILL_KNOCKBACK, skill.tags()), skill.tags()));
    }

    /** 引霆突的雷暴震波（自己的表）＋感電地面。 */
    void tempest(final Player owner, final Location at) {
        final SkillBuild parent = current(owner, GATHERING);
        if (parent == null) {
            return;
        }
        final SkillBuild wave = service.stageAttacks().ownSub(owner, parent, TEMPEST_SHOCKWAVE);
        if (wave != null) {
            service.procCast(Origin.at(owner, at.clone().add(0, 0.2, 0), SkillTargets.facing(owner)), wave, null);
        }
        service.procs().castAt(owner, TEMPEST_GROUND, GATHERING, at);
        at.getWorld().playSound(at, Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.2f, 0.7f);
    }

    // ---------------------------------------------------------------- 擊中之後

    private record Shard(UUID owner, long until, double threshold, int level) {
    }

    /** 寒魄掌的冰片：敵人 → 冰片、累積的傷害。 */
    private final Map<UUID, Shard> shards = new HashMap<>();
    private final Map<UUID, Double> shardDamage = new HashMap<>();

    private record Riven(UUID owner, double amount, long until) {
    }

    /** 穿甲重彈的裂甲：敵人 → 誰的、存多少、到哪個 tick。 */
    private final Map<UUID, Riven> riven = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit() || event.result().blocked()) {
            return;
        }
        final SkillProfile skill = event.skill();
        final String id = SupportProcs.baseId(skill.id());
        final LivingEntity target = event.defender();
        final long now = now();
        // 寒魄掌：先累積這一下（掛上冰片的那一下不算），再掛新的
        final Shard shard = shards.get(target.getUniqueId());
        if (shard != null && shard.owner().equals(player.getUniqueId()) && !PALM.equals(id) && !PALM_BURST.equals(id)) {
            if (shard.until() <= now) {
                shards.remove(target.getUniqueId());
                shardDamage.remove(target.getUniqueId());
            } else {
                final double total = shardDamage.merge(target.getUniqueId(), event.result().total(), Double::sum);
                if (total >= shard.threshold()) {
                    shards.remove(target.getUniqueId());
                    shardDamage.remove(target.getUniqueId());
                    final Location at = target.getLocation();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && player.getWorld().equals(at.getWorld())) {
                            service.procs().castAt(player, PALM_BURST, PALM, at);
                        }
                    });
                }
            }
        }
        if (PALM.equals(id)) {
            palm(player, skill, target, now);
        } else if (BLOOD.equals(id)) {
            bloodHunt(player, target);
        }
        if (HVR.equals(id) && SkillStages.mark(skill, SkillStages.IS + ProjectileEngine.VS_ARMOUR_BROKEN) > 0) {
            final double amount = rivenAmount(event.result().outgoing().get(DamageType.PHYSICAL));
            if (amount > 0) {
                riven.put(target.getUniqueId(), new Riven(player.getUniqueId(), amount, now + RIVEN_TICKS));
                target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() * 0.6, 0), 8, 0.2, 0.3, 0.2, 0.05);
            }
        }
    }

    /** 寒魄掌：在目標周圍 3.8 公尺內的敵人身上掛冰片。 */
    private void palm(final Player player, final SkillProfile skill, final LivingEntity target, final long now) {
        final SkillBuild build = current(player, PALM);
        if (build == null) {
            return;
        }
        final double threshold = StageAttacks.limit(build.profile(), "palm_threshold", 0);
        if (threshold <= 0) {
            return;
        }
        final double area = Math.sqrt(Math.max(0.05, skill.effective(sheet(player)).apply(StatKeys.SKILL_AREA, 1.0, skill.tags())));
        for (final LivingEntity enemy : service.targets().around(target.getLocation(), PALM_RADIUS * area, player)) {
            shards.put(enemy.getUniqueId(), new Shard(player.getUniqueId(), now + PALM_TICKS, threshold, build.level()));
            shardDamage.remove(enemy.getUniqueId());
            enemy.getWorld().spawnParticle(Particle.SNOWFLAKE, enemy.getLocation().add(0, enemy.getHeight() * 0.6, 0), 6, 0.3, 0.4, 0.3, 0.01);
        }
        startTimer();
    }

    /** 血獵打中失過血的敵人：用掉流血與失血，血爆範圍照用掉的失血變大，炸到的另外失去 25%。 */
    private void bloodHunt(final Player player, final LivingEntity target) {
        if (plugin.ailments() == null || plugin.ailments().bloodLoss(target) < BLOOD_MIN) {
            return;
        }
        final SkillBuild parent = current(player, BLOOD);
        if (parent == null) {
            return;
        }
        final double consumed = plugin.ailments().consumeBloodLoss(target);
        plugin.ailments().consume(target, AilmentType.BLEED, player);
        SkillBuild burst = service.stageAttacks().ownSub(player, parent, BLOOD_BURST);
        if (burst == null) {
            return;
        }
        final double more = bloodArea(consumed, service.levelValue(BLOOD, "limit.blood_per", parent.level()));
        if (more > 0) {
            burst = burst.withAreaAndProjectiles(burst.areaScale() * Math.sqrt(1 + more / 100.0), burst.projectiles());
        }
        final SkillBuild cast = burst;
        final Location at = target.getLocation();
        final double radius = cast.radius();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.getWorld().equals(at.getWorld())) {
                return;
            }
            service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), cast, null);
            final double loss = consumed * BLOOD_LIFE_LOSS;
            if (loss > 0) {
                for (final LivingEntity enemy : service.targets().around(at, radius, player)) {
                    plugin.ailments().dealFlatPhysical(enemy, loss, player.getUniqueId());
                }
            }
        });
    }

    /**
     * 擊中前（SupportMechanics.preHit）：裂甲（你的攻擊附加存下的物理，不吃加成，跟穿刺一樣加在減免前）、
     * 天柱雷照目標的感電效果更多傷害。
     */
    List<Modifier> preHit(final Player attacker, final LivingEntity defender, final StatSheet skillSheet, final Set<String> tags,
                          final SkillProfile skill) {
        List<Modifier> out = List.of();
        final Riven entry = riven.get(defender.getUniqueId());
        if (entry != null && skill != null && skill.isAttack() && entry.owner().equals(attacker.getUniqueId())) {
            if (entry.until() <= now()) {
                riven.remove(defender.getUniqueId());
            } else {
                out = new ArrayList<>(2);
                out.add(Modifier.flat(tw.exilecore.stats.KeystoneOriginKeys.IMPALE_EXTRACT, entry.amount(), "裂甲"));
            }
        }
        final double per = skillSheet.flat(MORE_PER_5_SHOCK, tags);
        if (per > 0 && plugin.ailments() != null) {
            final double more = conduitMore(plugin.ailments().shockMagnitude(defender), per);
            if (more > 0) {
                out = out.isEmpty() ? new ArrayList<>(1) : out;
                out.add(Modifier.more(StatKeys.DAMAGE, more, "感電效果"));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 打到東西（土牆）

    private void struck(final Player owner, final SkillProfile skill, final java.util.function.Predicate<Location> inside, final Object source) {
        if (walls.isEmpty() || skill == null) {
            return;
        }
        final String id = SupportProcs.baseId(skill.id());
        if (SHIELD_WALL.equals(id) || !(skill.tags().contains(Tags.SLAM) || SHIELD_RUSH.equals(id))) {
            return;
        }
        for (final Wall wall : new ArrayList<>(walls)) {
            if (!wall.owner.equals(owner.getUniqueId())) {
                continue;
            }
            for (final Location point : wall.points) {
                if (inside.test(point.clone().add(0, 0.5, 0))) {
                    shatter(owner, wall, true);
                    break;
                }
            }
        }
    }

    /** 戰吼（Warcries）：範圍內你的土牆崩碎、鍛錘的印記震碎成裂地。 */
    void onWarcry(final Player player, final double radius) {
        final Location at = player.getLocation();
        for (final Wall wall : new ArrayList<>(walls)) {
            if (!wall.owner.equals(player.getUniqueId())) {
                continue;
            }
            for (final Location point : wall.points) {
                if (point.getWorld().equals(at.getWorld()) && point.distanceSquared(at) <= radius * radius) {
                    shatter(player, wall, true);
                    break;
                }
            }
        }
        final ForgeMark mark = forges.get(player.getUniqueId());
        if (mark != null && mark.until > now() && mark.at.getWorld().equals(at.getWorld()) && mark.at.distanceSquared(at) <= radius * radius) {
            forges.remove(player.getUniqueId());
            fissures(player, mark);
        }
    }

    // ---------------------------------------------------------------- 土壁擊

    private static final class Wall {
        final UUID owner;
        final SkillBuild build;
        final List<Location> points;
        final double[] life;
        final Vector forward;
        final long until;

        Wall(final UUID owner, final SkillBuild build, final List<Location> points, final double life, final Vector forward, final long until) {
            this.owner = owner;
            this.build = build;
            this.points = points;
            this.life = new double[points.size()];
            java.util.Arrays.fill(this.life, life);
            this.forward = forward;
            this.until = until;
        }
    }

    private final List<Wall> walls = new ArrayList<>();

    /** SkillExecutor 立起一道牆之後（只管土壁擊）：記下每一段的生命。 */
    void wallPlaced(final Origin origin, final SkillBuild build, final List<Location> points) {
        if (!SHIELD_WALL.equals(build.definition().id()) || origin.owner() == null || points.isEmpty()) {
            return;
        }
        final double life = StageAttacks.limit(build.profile(), "wall_life", 0);
        final Vector forward = origin.facing().clone().setY(0);
        if (forward.lengthSquared() < 1e-6) {
            forward.setX(1);
        }
        forward.normalize();
        final UUID owner = origin.owner().getUniqueId();
        int mine = 0;
        for (final Wall wall : walls) {
            if (wall.owner.equals(owner)) {
                mine++;
            }
        }
        final int max = Math.max(1, build.params().zone() == null ? 2 : build.params().zone().max())
                + SkillLimits.bonus(plugin.players().sheet(origin.owner()), build); // 2026-10-28 照 PoE2：「上限 2 道土牆」吃 +x 上限（過剩），跟 zoneMax 一樣
        for (final Iterator<Wall> it = walls.iterator(); it.hasNext() && mine >= max; ) {
            if (it.next().owner.equals(owner)) {
                it.remove();
                mine--;
            }
        }
        final List<Location> copy = new ArrayList<>();
        points.forEach(point -> copy.add(point.clone()));
        walls.add(new Wall(owner, build, copy, life, forward, now() + build.durationTicks(build.params().zone() == null ? 6 : build.params().zone().duration())));
        startTimer();
    }

    /** 牆崩碎：每一段往前 4 公尺、周圍 1.4 公尺炸開（同一隻只挨一下）；被你自己打碎更多 100%。 */
    private void shatter(final Player owner, final Wall wall, final boolean bySelf) {
        if (!walls.remove(wall)) {
            return;
        }
        explode(owner, wall, wall.points, bySelf);
    }

    private void explode(final Player owner, final Wall wall, final List<Location> points, final boolean bySelf) {
        SkillProfile profile = wall.build.profile().derive(SHIELD_WALL + ".shatter", Set.of(Tags.AREA), Set.of());
        if (bySelf) {
            // 2026-10-28 照 PoE2 Shield Wall 的品質：被自己的技能打碎時再更多 (0—40)%（加在 100% 上）
            profile = profile.scaled(1 + WALL_SELF_MORE + wall.build.qualityFlat(tw.exilecore.stats.GemQualityKeys.WALL_SELF_MORE) / 100.0, "自己打碎");
        }
        final double scale = wall.build.areaScale();
        final Set<UUID> hit = new HashSet<>();
        for (final Location point : points) {
            final Location to = point.clone().add(wall.forward.clone().multiply(WALL_LENGTH * scale));
            final List<LivingEntity> victims = new ArrayList<>(service.targets().around(point, WALL_AROUND * scale, owner));
            victims.addAll(service.targets().alongLine(point, to, WALL_AROUND * scale / 2, owner));
            for (final LivingEntity target : victims) {
                if (hit.add(target.getUniqueId())) {
                    service.executor().hitOne(owner, profile, target, point, 0);
                }
            }
            point.getWorld().spawnParticle(Particle.BLOCK, point.clone().add(0, 0.6, 0), 12, 0.4, 0.4, 0.4, 0,
                    org.bukkit.Material.DIRT.createBlockData());
        }
        points.getFirst().getWorld().playSound(points.getFirst(), Sound.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
    }

    /** 敵人站在牆邊攻擊牆（每秒一次，照怪物的基礎攻擊傷害）；某一段生命扣完就那一段崩碎（不算你打碎的）。 */
    private void wallsTick(final long now) {
        for (final Wall wall : new ArrayList<>(walls)) {
            final Player owner = plugin.getServer().getPlayer(wall.owner);
            if (owner == null || now >= wall.until || !owner.getWorld().equals(wall.points.getFirst().getWorld())) {
                walls.remove(wall);
                continue;
            }
            if (now % 20 != 0 || plugin.monsters() == null) {
                continue;
            }
            final List<Location> broken = new ArrayList<>();
            for (int i = 0; i < wall.points.size(); i++) {
                if (wall.life[i] <= 0) {
                    continue;
                }
                for (final LivingEntity enemy : service.targets().around(wall.points.get(i), WALL_TOUCH, owner)) {
                    final var data = plugin.monsters().data(enemy);
                    if (data.isEmpty()) {
                        continue;
                    }
                    final StatSheet monster = plugin.monsters().sheet(enemy, data.get());
                    wall.life[i] -= Math.max(1, (monster.get(StatKeys.WEAPON_MIN) + monster.get(StatKeys.WEAPON_MAX)) / 2);
                }
                if (wall.life[i] <= 0) {
                    broken.add(wall.points.get(i));
                }
            }
            if (!broken.isEmpty()) {
                explode(owner, wall, broken, false);
            }
        }
    }

    // ---------------------------------------------------------------- 萬箭齊落

    private static final class Rain {
        final UUID owner;
        final SkillProfile profile;
        final Location center;
        final double area;
        final double radius;
        final long start;
        final double perArrow;
        final int arrows;
        int fired;
        final Map<UUID, Long> lastHit = new HashMap<>();

        Rain(final UUID owner, final SkillProfile profile, final Location center, final double area, final double radius, final long start,
             final double perArrow, final int arrows) {
            this.owner = owner;
            this.profile = profile;
            this.center = center;
            this.area = area;
            this.radius = radius;
            this.start = start;
            this.perArrow = perArrow;
            this.arrows = arrows;
        }
    }

    /** 施放者（自己放的是 UUID，圖騰放的再接圖騰的位置）→ 那一場箭雨（同時只有一場）。 */
    private final Map<String, Rain> rains = new HashMap<>();

    /**
     * SkillExecutor.impact 的開頭：萬箭齊落自己排箭（回傳 true，原本的落下不要做）；寒晶雷開始脈衝（回傳 false，照常落下）。
     */
    boolean impact(final Origin origin, final SkillBuild build, final Location center, final int delay) {
        final String id = build.definition().id();
        final Player owner = origin.owner();
        if (owner == null) {
            return false;
        }
        if (RAIN.equals(id)) {
            return rain(origin, build, center, delay);
        }
        if (FROST_BOMB.equals(id)) {
            frostBomb(owner, build, center, delay);
        }
        return false;
    }

    private boolean rain(final Origin origin, final SkillBuild build, final Location center, final int delay) {
        final int base = StageAttacks.limit(build.profile(), "arrows", 0);
        if (base <= 0) {
            return false;
        }
        final boolean frenzy = !Double.isNaN(StageAttacks.local(build.profile(), RAIN_FRENZY));
        final int arrows = StageAttacks.rainArrows(base, build.projectiles() - 1, frenzy);
        final Player owner = origin.owner();
        final StatSheet effective = build.profile().effective(sheet(owner));
        // 原作：投射物速度的加成也加快落箭的頻率
        final double speed = Math.max(0.25, Math.min(4.0, effective.apply(StatKeys.SKILL_PROJECTILE_SPEED, 1.0, build.profile().tags())));
        final String key = origin.self() ? owner.getUniqueId().toString()
                : owner.getUniqueId() + "@" + origin.feet().getBlockX() + "," + origin.feet().getBlockZ();
        rains.put(key, new Rain(owner.getUniqueId(), build.profile(), center.clone(), StageAttacks.RAIN_RADIUS * build.areaScale(), build.radius(),
                now() + delay, StageAttacks.RAIN_TICKS_PER_ARROW / speed, arrows));
        startTimer();
        return true;
    }

    private void rainsTick(final long now) {
        for (final Iterator<Map.Entry<String, Rain>> it = rains.entrySet().iterator(); it.hasNext(); ) {
            final Rain rain = it.next().getValue();
            final Player owner = plugin.getServer().getPlayer(rain.owner);
            if (owner == null || !owner.getWorld().equals(rain.center.getWorld())) {
                it.remove();
                continue;
            }
            while (rain.fired < rain.arrows && now >= rain.start + Math.round(rain.fired * rain.perArrow)) {
                final Location at = rain.center.clone();
                if (rain.fired > 0) { // 第一支落在準心，其他的隨機散在箭雨範圍內
                    final double angle = plugin.random().nextDouble() * Math.PI * 2;
                    final double distance = Math.sqrt(plugin.random().nextDouble()) * rain.area;
                    at.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                }
                rain.fired++;
                for (final LivingEntity target : service.targets().around(at, rain.radius, owner)) {
                    if (rainGate(rain.lastHit.get(target.getUniqueId()), now)) {
                        rain.lastHit.put(target.getUniqueId(), now);
                        service.executor().hitOne(owner, rain.profile, target, at, 0);
                    }
                }
                service.targets().strike(owner, rain.profile, SkillTargets.sphere(at, rain.radius), new Object()); // 每一支箭各算一下（鐘、冰晶）
                at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 2.5, 0), 4, 0.05, 1.0, 0.05, 0);
                at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, 0.2, 0), 1, 0, 0, 0, 0);
                if (rain.fired % 6 == 1) {
                    at.getWorld().playSound(at, Sound.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.6f, 1.2f);
                }
            }
            if (rain.fired >= rain.arrows) {
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------- 寒晶雷

    private static final class Bomb {
        final UUID owner;
        final Location center;
        final double radius;
        final long until;
        final int levelCap;
        final double effect;
        int pulses;
        long next;
        /** 2026-10-28 照 PoE2 Frost Bomb 的品質：曝露上限另外 +x。 */
        double extraCap;

        Bomb(final UUID owner, final Location center, final double radius, final long until, final int levelCap, final double effect, final long next) {
            this.owner = owner;
            this.center = center;
            this.radius = radius;
            this.until = until;
            this.levelCap = levelCap;
            this.effect = effect;
            this.next = next;
        }
    }

    private final List<Bomb> bombs = new ArrayList<>();

    private void frostBomb(final Player owner, final SkillBuild build, final Location center, final int delay) {
        final StatSheet effective = build.profile().effective(sheet(owner));
        final double effect = Math.max(0, 1 + effective.increased(StatKeys.EXPOSURE_EFFECT, build.profile().tags()));
        final long now = now();
        final Bomb bomb = new Bomb(owner.getUniqueId(), center.clone(), build.radius(), now + delay,
                StageAttacks.limit(build.profile(), "exposure_level", 100), effect, now);
        bomb.extraCap = build.qualityFlat(tw.exilecore.stats.GemQualityKeys.EXPOSURE_CAP); // 2026-10-28 照 PoE2 Frost Bomb 的品質：曝露上限 +(0—10)
        bombs.add(bomb);
        startTimer();
    }

    private void bombsTick(final long now) {
        for (final Iterator<Bomb> it = bombs.iterator(); it.hasNext(); ) {
            final Bomb bomb = it.next();
            final Player owner = plugin.getServer().getPlayer(bomb.owner);
            if (owner == null || now >= bomb.until || !owner.getWorld().equals(bomb.center.getWorld())) {
                it.remove();
                continue;
            }
            if (now < bomb.next || plugin.combat() == null) {
                continue;
            }
            bomb.next = now + BOMB_PULSE_TICKS;
            bomb.pulses++;
            final double base = bombExposure(bomb.pulses, bomb.extraCap) * bomb.effect;
            for (final LivingEntity target : service.targets().around(bomb.center, bomb.radius, owner)) {
                if (target instanceof Player || plugin.monsters() == null) {
                    continue;
                }
                final var data = plugin.monsters().data(target);
                if (data.isEmpty() || data.get().level() > bomb.levelCap) {
                    continue; // 原作：比技能等級上限高的敵人不會被曝露
                }
                final double scale = Math.max(0, plugin.monsters().sheet(target, data.get())
                        .apply(tw.exilecore.ailment.AilmentKeys.EXPOSURE_EFFECT_ON_SELF, 1.0, Set.of()));
                for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                    plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("exposure." + type.tag(), owner.getUniqueId(),
                            now + BOMB_EXPOSURE_TICKS, 0, false, null, List.of(Modifier.flat(type.mitigationStat(), -base * scale, "曝露")), 0));
                }
            }
            bomb.center.getWorld().spawnParticle(Particle.SNOWFLAKE, bomb.center.clone().add(0, 0.5, 0), 6, bomb.radius * 0.4, 0.2, bomb.radius * 0.4, 0.01);
        }
    }

    // ---------------------------------------------------------------- 地火柱

    /** SkillExecutor.zone 放下火山之後：開場爆發（引導越多階越痛、噴越多顆）。 */
    void volcano(final Origin origin, final SkillBuild build, final Location center) {
        if (!VOLCANO.equals(build.definition().id()) || origin.owner() == null) {
            return;
        }
        final Player owner = origin.owner();
        final double marked = StageAttacks.local(build.profile(), VOLCANO_STAGES);
        final int stages = Double.isNaN(marked) ? 1 : Math.max(1, (int) Math.round(marked));
        final double radius = SkillStages.radius(build, "eruption", 1.2 * build.areaScale());
        SkillProfile eruption = SkillStages.profile(build, build.profile(), "eruption")
                .derive(VOLCANO + ".eruption", Set.of(Tags.AREA), Set.of(Tags.PROJECTILE));
        final double more = volcanoMore(stages);
        if (more > 0) {
            eruption = eruption.scaled(1 + more, "引導階數");
        }
        for (final LivingEntity target : service.targets().around(center, radius, owner)) {
            service.executor().hitOne(owner, eruption, target, center, 0);
        }
        final SkillParams.Zone zone = build.params().zone();
        if (zone == null || zone.shower() == null) {
            return;
        }
        final StatSheet effective = build.profile().effective(sheet(owner));
        final int base = zone.shower().count().intAt(build.level()) + (int) Math.round(effective.flat(StatKeys.SKILL_PROJECTILES, build.profile().tags()));
        final int count = Math.min(40, volcanoProjectiles(base, stages));
        final double shardRadius = zone.shower().radius() * build.areaScale();
        final SkillProfile shard = build.profile().scaled(zone.damage() / 100.0, "地面效果");
        for (final double[] offset : ZonePulses.showerPoints(count, build.radius(), plugin.random())) {
            final Location at = center.clone().add(offset[0], 0, offset[1]);
            for (final LivingEntity target : service.targets().around(at, shardRadius, owner)) {
                service.executor().hitOne(owner, shard, target, at, 0);
            }
            at.getWorld().spawnParticle(Particle.LAVA, at.clone().add(0, 0.3, 0), 3, 0.2, 0.1, 0.2, 0);
        }
        center.getWorld().spawnParticle(Particle.FLAME, center.clone().add(0, 0.6, 0), 30 + 10 * stages, radius * 0.5, 0.6, radius * 0.5, 0.05);
        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8f + 0.1f * stages, 0.8f);
    }

    // ---------------------------------------------------------------- 雷雲

    /** 你施放一個法術（SkillService）：附近的雷雲多放一道雷。 */
    void spellCast(final Player player, final SkillDefinition definition) {
        if (definition == null || ORB.equals(definition.id()) || !definition.tags().contains(Tags.SPELL)) {
            return;
        }
        service.zones().stormOrbCast(player, player.getLocation(), ORB_NEAR);
    }

    // ---------------------------------------------------------------- 熾陽之矛

    private static final class Ignited {
        final UUID owner;
        final Location center;
        final double base;
        final long start;
        final long end;
        final double fire;
        final StatSheet effective;

        Ignited(final UUID owner, final Location center, final double base, final long start, final long end, final double fire,
                final StatSheet effective) {
            this.owner = owner;
            this.center = center;
            this.base = base;
            this.start = start;
            this.end = end;
            this.fire = fire;
            this.effective = effective;
        }
    }

    private final List<Ignited> ignited = new ArrayList<>();

    /** SkillExecutor.pulses（光矛落地後）：留下會擴大的點燃地面。 */
    void solarisGround(final Player owner, final SkillBuild build, final Location at, final int delay) {
        final SkillStages.Stage stage = SkillStages.get(build.definition().id(), "ground");
        if (stage == null) {
            return;
        }
        final SkillProfile profile = SkillStages.profile(build, build.profile(), "ground");
        final double fire = outgoing(owner, profile).get(DamageType.FIRE);
        final double radius = SkillStages.radius(build, "ground", 2.0 * build.areaScale());
        final long start = now() + Math.max(1, delay);
        final long end = start + build.durationTicks(stage.duration() > 0 ? stage.duration() : 4);
        ignited.add(new Ignited(owner.getUniqueId(), at.clone(), radius, start, end, fire, profile.effective(sheet(owner))));
        // 2026-10-28 照 PoE2：點燃地面是元素地面（旋風矛、旋矛擊的旋風碰到變成火焰的）；地面會擴大，照擴大後的半徑登記（這次之前沒登記）
        ElementalGround.mark(at, solarisRadius(radius, 1), DamageType.FIRE, end);
        startTimer();
    }

    private void ignitedTick(final long now) {
        for (final Iterator<Ignited> it = ignited.iterator(); it.hasNext(); ) {
            final Ignited ground = it.next();
            final Player owner = plugin.getServer().getPlayer(ground.owner);
            if (owner == null || now >= ground.end || !owner.getWorld().equals(ground.center.getWorld())) {
                it.remove();
                continue;
            }
            if (now < ground.start || (now - ground.start) % 10 != 0) {
                continue;
            }
            final double radius = solarisRadius(ground.base, (now - ground.start) / (double) Math.max(1, ground.end - ground.start));
            if (plugin.ailments() != null && ground.fire > 0) {
                for (final LivingEntity target : service.targets().around(ground.center, radius, owner)) {
                    plugin.ailments().groundIgnite(owner, target, ground.fire, ground.effective);
                }
            }
            ground.center.getWorld().spawnParticle(Particle.FLAME, ground.center.clone().add(0, 0.1, 0), (int) Math.round(6 * radius),
                    radius * 0.5, 0.05, radius * 0.5, 0.01);
        }
    }

    // ---------------------------------------------------------------- 奔雷踏

    private record Patch(UUID owner, Location at, double radius, long until, SkillBuild build) {
    }

    private final List<Patch> jagged = new ArrayList<>();

    /** SkillExecutor.dash 之後（只管奔雷踏）：沿路每一步留下鋸齒地面，終點猛擊把附近的鋸齒地面炸開。 */
    void stampede(final Player player, final SkillBuild build, final Location from, final Location to) {
        if (!STAMPEDE.equals(build.definition().id())) {
            return;
        }
        final SkillStages.Stage explosion = SkillStages.get(STAMPEDE, "explosion");
        if (explosion == null) {
            return;
        }
        final long now = now();
        final double radius = SkillStages.radius(build, "explosion", 1.2 * build.areaScale());
        final long until = now + build.durationTicks(explosion.duration() > 0 ? explosion.duration() : 6);
        final double distance = from.distance(to);
        final int steps = Math.max(1, (int) Math.floor(distance / STAMPEDE_STEP));
        for (int i = 0; i <= steps; i++) {
            final Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(i / (double) steps));
            jagged.add(new Patch(player.getUniqueId(), at, radius, until, build));
        }
        // 終點猛擊：3.2 公尺內你的鋸齒地面全部炸開（每一片各炸一次）
        final double reach = STAMPEDE_EXPLODE_RADIUS * build.areaScale();
        final SkillProfile blast = SkillStages.profile(build, build.profile(), "explosion")
                .derive(STAMPEDE + ".jagged", Set.of(Tags.AREA), Set.of());
        for (final Iterator<Patch> it = jagged.iterator(); it.hasNext(); ) {
            final Patch patch = it.next();
            if (!patch.owner().equals(player.getUniqueId()) || !patch.at().getWorld().equals(to.getWorld())
                    || patch.at().distanceSquared(to) > reach * reach) {
                continue;
            }
            it.remove();
            for (final LivingEntity target : service.targets().around(patch.at(), patch.radius(), player)) {
                service.executor().hitOne(player, blast, target, patch.at(), 0);
            }
            patch.at().getWorld().spawnParticle(Particle.BLOCK, patch.at().clone().add(0, 0.3, 0), 10, patch.radius() * 0.4, 0.2, patch.radius() * 0.4, 0,
                    org.bukkit.Material.STONE.createBlockData());
        }
        startTimer();
    }

    private void jaggedTick(final long now) {
        jagged.removeIf(patch -> patch.until() <= now);
        if (jagged.isEmpty() || now % 10 != 0 || plugin.combat() == null) {
            return;
        }
        for (final Patch patch : jagged) {
            final Player owner = plugin.getServer().getPlayer(patch.owner());
            if (owner == null || !owner.getWorld().equals(patch.at().getWorld())) {
                continue;
            }
            for (final LivingEntity target : service.targets().around(patch.at(), patch.radius(), owner)) {
                if (!(target instanceof Player)) {
                    plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("jagged_ground", owner.getUniqueId(), now + 15, 0, false, null,
                            List.of(), JAGGED_SLOW));
                }
            }
            patch.at().getWorld().spawnParticle(Particle.CRIT, patch.at().clone().add(0, 0.1, 0), 2, patch.radius() * 0.4, 0.02, patch.radius() * 0.4, 0);
        }
    }

    // ---------------------------------------------------------------- 回旋鍛錘

    private record ForgeMark(Location at, long until, SkillBuild build) {
    }

    private final Map<UUID, ForgeMark> forges = new HashMap<>();

    /** ProjectileEngine：鍛錘飛到最遠要回頭的地方（落下的地方）。 */
    void forgeTurned(final Player owner, final SkillBuild build, final Location at) {
        if (!FORGE.equals(build.definition().id()) || SkillStages.get(FORGE, "fissure") == null) {
            return;
        }
        forges.put(owner.getUniqueId(), new ForgeMark(at.clone(), now() + build.durationTicks(FORGE_SECONDS), build));
    }

    private void fissures(final Player player, final ForgeMark mark) {
        final SkillBuild build = mark.build();
        final SkillStages.Stage stage = SkillStages.get(FORGE, "fissure");
        final int count = Math.max(1, stage.count().intAt(build.level()));
        final double width = SkillStages.radius(build, "fissure", 1.0 * build.areaScale());
        final SkillProfile profile = SkillStages.profile(build, build.profile(), "fissure")
                .derive(FORGE + ".fissure", Set.of(Tags.AREA), Set.of(Tags.PROJECTILE));
        final Vector facing = SkillTargets.facing(player).setY(0);
        final double start = Math.atan2(facing.getZ(), facing.getX());
        final Location origin = mark.at().clone();
        for (final double[] ray : spiral(count, start)) {
            // 螺旋：前半段照一個方向，後半段往旁邊彎
            final Location mid = origin.clone().add(ray[0] * FISSURE_LENGTH / 2, 0, ray[1] * FISSURE_LENGTH / 2);
            final Location end = mid.clone().add(ray[2] * FISSURE_LENGTH / 2, 0, ray[3] * FISSURE_LENGTH / 2);
            final Set<UUID> hit = new HashSet<>();
            for (final LivingEntity target : service.targets().alongLine(origin, mid, width, player)) {
                if (hit.add(target.getUniqueId())) {
                    service.executor().hitOne(player, profile, target, origin, 0);
                }
            }
            for (final LivingEntity target : service.targets().alongLine(mid, end, width, player)) {
                if (hit.add(target.getUniqueId())) {
                    service.executor().hitOne(player, profile, target, mid, 0);
                }
            }
            for (double d = 0; d <= FISSURE_LENGTH; d += 0.5) {
                final Location at = d <= FISSURE_LENGTH / 2 ? origin.clone().add(ray[0] * d, 0.1, ray[1] * d)
                        : mid.clone().add(ray[2] * (d - FISSURE_LENGTH / 2), 0.1, ray[3] * (d - FISSURE_LENGTH / 2));
                at.getWorld().spawnParticle(Particle.FLAME, at, 2, 0.15, 0.05, 0.15, 0.01);
            }
        }
        origin.getWorld().playSound(origin, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1.0f, 0.6f);
    }

    // ---------------------------------------------------------------- 颶風槍

    private static final class Whirl {
        final UUID owner;
        Location center;
        int stage;
        final double baseRadius;
        final double areaScale;
        SkillBuild build;
        SkillProfile profile;
        long until;
        boolean ownerInside;
        final String visual;

        Whirl(final UUID owner, final Location center, final int stage, final double baseRadius, final double areaScale, final SkillBuild build,
              final SkillProfile profile, final long until, final String visual) {
            this.owner = owner;
            this.center = center;
            this.stage = stage;
            this.baseRadius = baseRadius;
            this.areaScale = areaScale;
            this.build = build;
            this.profile = profile;
            this.until = until;
            this.visual = visual;
        }

        double radius() {
            return whirlRadius(baseRadius, stage, areaScale);
        }
    }

    private final List<Whirl> whirls = new ArrayList<>();

    /**
     * ProjectileEngine：颶風槍的矛落地捲起旋風。疊在你現有的旋風上時，舊的移過來多一階（最多 3 階）；用掉怒星的直接 4 階。
     * 旋風範圍內的敵人緩速 30%、致盲；你跨過旋風的邊緣就崩散（打裡面的敵人、往外擊退）。
     */
    void whirlwind(final Player owner, final SkillBuild build, final SkillProfile shot, final Location at, final String visual) {
        final SkillStages.Stage stage = SkillStages.get(build.definition().id(), ProjectileEngine.WHIRLWIND);
        if (stage == null) {
            return;
        }
        final boolean frenzy = !Double.isNaN(StageAttacks.local(shot, WHIRLWIND_FRENZY));
        final double baseRadius = SkillStages.radius(build, ProjectileEngine.WHIRLWIND, 1.8 * build.areaScale());
        final SkillProfile profile = SkillStages.profile(build, shot, ProjectileEngine.WHIRLWIND)
                .derive(build.definition().id() + "." + ProjectileEngine.WHIRLWIND, Set.of(Tags.MELEE, Tags.AREA), Set.of(Tags.PROJECTILE));
        final long until = now() + build.durationTicks(stage.duration() > 0 ? stage.duration() : 15);
        for (final Whirl whirl : whirls) {
            if (whirl.owner.equals(owner.getUniqueId()) && whirl.center.getWorld().equals(at.getWorld())
                    && whirl.center.distance(at) < whirl.radius() + baseRadius) {
                whirl.center = at.clone(); // 原作：疊上去的時候舊的移過來、多一階
                whirl.stage = whirlStage(whirl.stage, frenzy);
                whirl.build = build;
                whirl.profile = profile;
                whirl.until = until;
                whirl.ownerInside = inside(owner, whirl);
                return;
            }
        }
        int mine = 0;
        for (final Whirl whirl : whirls) {
            if (whirl.owner.equals(owner.getUniqueId())) {
                mine++;
            }
        }
        final int whirlLimit = WHIRL_LIMIT + SkillLimits.bonus(plugin.players().sheet(owner), build); // 2026-10-28 照 PoE2：「上限 6 個旋風」吃 +x 上限（過剩）
        for (final Iterator<Whirl> it = whirls.iterator(); it.hasNext() && mine >= whirlLimit; ) {
            if (it.next().owner.equals(owner.getUniqueId())) {
                it.remove();
                mine--;
            }
        }
        final Whirl whirl = new Whirl(owner.getUniqueId(), at.clone(), whirlStage(0, frenzy), baseRadius, build.areaScale(), build, profile, until, visual);
        whirl.ownerInside = inside(owner, whirl);
        whirls.add(whirl);
        startTimer();
    }

    /**
     * 2026-10-28 照 PoE2 Twister：旋風矛的旋風碰到 owner 的颶風槍旋風（at 的 reach 格內）就用掉它（不崩散、直接消失）；
     * 回傳它的階數，沒碰到回傳 0。一次只用掉一個。
     */
    int consumeWhirl(final UUID owner, final Location at, final double reach) {
        for (final Iterator<Whirl> it = whirls.iterator(); it.hasNext(); ) {
            final Whirl whirl = it.next();
            if (whirl.owner.equals(owner) && whirl.center.getWorld().equals(at.getWorld())
                    && whirl.center.distanceSquared(at) <= (whirl.radius() + reach) * (whirl.radius() + reach)) {
                it.remove();
                whirl.center.getWorld().spawnParticle(Particle.CLOUD, whirl.center.clone().add(0, 0.8, 0), 8 + 4 * whirl.stage,
                        whirl.radius() * 0.4, 0.4, whirl.radius() * 0.4, 0.05);
                return whirl.stage;
            }
        }
        return 0;
    }

    private static boolean inside(final Player owner, final Whirl whirl) {
        return owner.getWorld().equals(whirl.center.getWorld()) && owner.getLocation().distanceSquared(whirl.center) <= whirl.radius() * whirl.radius();
    }

    private void whirlsTick(final long now) {
        for (final Iterator<Whirl> it = whirls.iterator(); it.hasNext(); ) {
            final Whirl whirl = it.next();
            final Player owner = plugin.getServer().getPlayer(whirl.owner);
            if (owner == null || now >= whirl.until || !owner.getWorld().equals(whirl.center.getWorld())) {
                it.remove();
                continue;
            }
            final double radius = whirl.radius();
            final boolean in = inside(owner, whirl);
            if (in && !whirl.ownerInside) {
                it.remove();
                collapse(owner, whirl, radius);
                continue;
            }
            whirl.ownerInside = in;
            if (now % 5 != 0) {
                continue;
            }
            if (plugin.combat() != null) {
                for (final LivingEntity target : service.targets().around(whirl.center, radius, owner)) {
                    if (!(target instanceof Player)) {
                        plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("whirlwind_slow", owner.getUniqueId(), now + 10, 0, false, null,
                                List.of(), WHIRL_SLOW));
                        plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("blind", owner.getUniqueId(), now + 80, 0, false, null,
                                tw.exilecore.combat.HitEffects.blindModifiers(0), 0));
                    }
                }
            }
            whirl.center.getWorld().spawnParticle(Particle.CLOUD, whirl.center.clone().add(0, 0.6, 0), 3 + whirl.stage, radius * 0.4, 0.4, radius * 0.4, 0.02);
        }
    }

    private void collapse(final Player owner, final Whirl whirl, final double radius) {
        // 2026-10-28 照 PoE2 Whirlwind Lance 的品質：每多一階再更多 (0—40)%（加在 150% 上）
        final double more = whirlMore(whirl.stage)
                + SkillZones.localFlat(whirl.profile, Signatures.WHIRL_QUALITY_MORE) / 100.0 * Math.max(0, whirl.stage - 1);
        final SkillProfile profile = more > 0 ? whirl.profile.scaled(1 + more, "旋風階數") : whirl.profile;
        final double push = 0.8 * (1 + WHIRL_STAGE_KNOCKBACK * Math.max(0, whirl.stage - 1));
        for (final LivingEntity victim : service.targets().around(whirl.center, radius, owner)) {
            service.executor().hitOne(owner, profile, victim, whirl.center, push);
        }
        whirl.center.getWorld().spawnParticle(Particle.SWEEP_ATTACK, whirl.center.clone().add(0, 0.8, 0), 6 + 2 * whirl.stage, radius * 0.5, 0.4, radius * 0.5, 0);
        whirl.center.getWorld().playSound(whirl.center, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    // ---------------------------------------------------------------- 霜牙刺、三連刺

    /** StageEffects：霜牙刺炸開冰霜的地方留下冰緩地面。 */
    void fangsGround(final Player player, final Location at) {
        service.procs().castAt(player, FANGS_GROUND, FANGS, at);
    }

    /** StageEffects：三連刺插著矛頭的敵人被癱瘓（跟矛頭一樣久）。 */
    void spearheadMaim(final Player player, final LivingEntity target, final long until) {
        if (plugin.combat() == null || target instanceof Player) {
            return;
        }
        plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("maim", player.getUniqueId(), until, 0, false, null,
                List.of(Modifier.increased(StatKeys.EVASION, -MAIM_EVASION, "癱瘓")), MAIM_SLOW));
    }

    // ---------------------------------------------------------------- 毒苞（瘴苞雨）

    /** 你的技能打到地上的毒苞：這一下預期的中毒傷害（中毒機率 × 每秒 × 持續）。 */
    double expectedPoison(final Player owner, final SkillProfile skill) {
        if (plugin.ailments() == null) {
            return 0;
        }
        final StatSheet effective = skill.effective(sheet(owner));
        final Set<String> tags = skill.tags();
        final double chance = Math.max(0, Math.min(100, effective.flat(StatKeys.POISON_CHANCE, tags)
                * Math.max(0, 1 + effective.increased(StatKeys.POISON_CHANCE, tags)) * effective.more(StatKeys.POISON_CHANCE, tags))) / 100.0;
        if (chance <= 0) {
            return 0;
        }
        final DamagePacket packet = outgoing(owner, skill);
        final AilmentConfig config = plugin.ailments().config();
        final double dps = config.poisonDps(packet.get(DamageType.PHYSICAL) + packet.get(DamageType.CHAOS),
                effective.increased(StatKeys.POISON_MAGNITUDE, tags), effective.more(StatKeys.POISON_MAGNITUDE, tags));
        return chance * dps * config.poisonDuration();
    }

    // ---------------------------------------------------------------- 計時

    private void startTimer() {
        if (timer >= 0) {
            return;
        }
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1).getTaskId();
    }

    private void tick() {
        final long now = now();
        rainsTick(now);
        bombsTick(now);
        ignitedTick(now);
        jaggedTick(now);
        whirlsTick(now);
        wallsTick(now);
        if (now % 100 == 0) {
            shards.entrySet().removeIf(entry -> entry.getValue().until() <= now);
            shardDamage.keySet().removeIf(id -> !shards.containsKey(id));
            riven.values().removeIf(entry -> entry.until() <= now);
            forges.values().removeIf(mark -> mark.until() <= now);
        }
        if (rains.isEmpty() && bombs.isEmpty() && ignited.isEmpty() && jagged.isEmpty() && whirls.isEmpty() && walls.isEmpty() && shards.isEmpty()
                && riven.isEmpty() && forges.isEmpty() && timer >= 0) {
            plugin.getServer().getScheduler().cancelTask(timer);
            timer = -1;
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    void forget(final UUID id) {
        rains.values().removeIf(rain -> rain.owner.equals(id));
        bombs.removeIf(bomb -> bomb.owner.equals(id));
        ignited.removeIf(ground -> ground.owner.equals(id));
        jagged.removeIf(patch -> patch.owner().equals(id));
        whirls.removeIf(whirl -> whirl.owner.equals(id));
        walls.removeIf(wall -> wall.owner.equals(id));
        shards.values().removeIf(shard -> shard.owner().equals(id));
        riven.values().removeIf(entry -> entry.owner().equals(id));
        forges.remove(id);
    }
}
