package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 戰吼的設定（skills.yml 裡 mechanic: warcry 的 warcry 區塊）。純資料，不依賴 Bukkit。
 * <p>
 * 2026-10-26 照 PoE2 0.5 重做：真正的戰吼（cry: true）數附近敵人的「強度」（普通 1、魔法 2、稀有 10、傳奇與 Boss 20），
 * 照強度決定強化幾次攻擊（{@link Empower}），附近沒有敵人就不強化；守護、怒氣、隊友的冰冷傷害也照強度算。
 * 冷卻可以花一顆磐星跳過（skills.yml 的 cooldown_bypass: endurance）。原作沒有的治療、嘲諷、受到傷害增加、隊伍增益拿掉了，
 * 那些欄位還留著給戰旗、祭品、神龕祝禱、隱藏的戰星技能用（它們不是戰吼：cry: false 的戰吼輔助不能掛）。
 *
 * @param radius        範圍（格 = 公尺）
 * @param duration      增益、強化持續幾秒
 * @param taunt         範圍內的敵人轉過來打你（只有非原作的技能用）
 * @param heal          立刻回復最大生命的百分比（隨等級；只有非原作的技能用）
 * @param allies        隊友也得到增益
 * @param buff          給自己（與隊友）的增益（隨技能等級變）
 * @param taken         範圍內的敵人受到的傷害增加百分比（隨等級；0 = 沒有）
 * @param exerts        舊寫法：接下來固定幾次攻擊被強化（0 = 沒有；新的戰吼用 empower 照強度算）
 * @param exertMore     強化的攻擊更多幾 % 傷害（隨等級）
 * @param charges       吼一聲直接獲得的戰星：代號（charges.yml）→ 幾顆（只給自己；滿了就把最舊的那顆換新）
 * @param minionBuff    2026-10 祭品：範圍內你的召喚物得到的增益（隨等級；持續 duration 秒）
 * @param spike         2026-10 祭柱（PoE2 的祭品）
 * @param rageExtend    2026-10 銀月庇：吼的時候用掉全部怒氣，每點怒氣增益多撐幾秒（0 = 不用怒氣）
 * @param recall        2026-10-18 召歸令：發動時先把你所有的召喚物傳到你身邊
 * @param cry           2026-10-26：是不是原作的戰吼（戰吼標籤、戰吼輔助、戰吼速度）；戰旗、祭品、召歸令是 false
 * @param empower       2026-10-26：照強度強化的攻擊（null = 不強化）
 * @param extras        2026-10-26：各戰吼自己的效果（守護、隊友冰冷、燃爆、怒氣、榮耀…）
 */
public record WarcrySpec(double radius, double duration, boolean taunt, Scaled heal, boolean allies, List<ScaledMod> buff,
                         Scaled taken, int exerts, Scaled exertMore, Map<String, Integer> charges,
                         List<ScaledMod> minionBuff, boolean spike, double rageExtend, boolean recall,
                         boolean cry, Empower empower, Extras extras) {

    /** 2026-10-18：舊的 13 參數建構式（沒有 recall）。 */
    public WarcrySpec(final double radius, final double duration, final boolean taunt, final Scaled heal, final boolean allies, final List<ScaledMod> buff,
                      final Scaled taken, final int exerts, final Scaled exertMore, final Map<String, Integer> charges,
                      final List<ScaledMod> minionBuff, final boolean spike, final double rageExtend) {
        this(radius, duration, taunt, heal, allies, buff, taken, exerts, exertMore, charges, minionBuff, spike, rageExtend, false);
    }

    /** 2026-10-26：舊的 14 參數建構式（不是原作的戰吼、沒有強度）。 */
    public WarcrySpec(final double radius, final double duration, final boolean taunt, final Scaled heal, final boolean allies, final List<ScaledMod> buff,
                      final Scaled taken, final int exerts, final Scaled exertMore, final Map<String, Integer> charges,
                      final List<ScaledMod> minionBuff, final boolean spike, final double rageExtend, final boolean recall) {
        this(radius, duration, taunt, heal, allies, buff, taken, exerts, exertMore, charges, minionBuff, spike, rageExtend, recall,
                false, null, Extras.NONE);
    }

    public WarcrySpec {
        buff = List.copyOf(buff);
        minionBuff = minionBuff == null ? List.of() : List.copyOf(minionBuff);
        charges = charges == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(charges));
        extras = extras == null ? Extras.NONE : extras;
    }

    /** 普通、魔法、稀有、傳奇（含 Boss）的強度（PoE2：1、2、10、20）。 */
    public static final double POWER_NORMAL = 1;
    public static final double POWER_MAGIC = 2;
    public static final double POWER_RARE = 10;
    public static final double POWER_UNIQUE = 20;

    /** 會強化攻擊（舊的固定次數，或新的照強度）。 */
    public boolean empowers() {
        return exerts > 0 || empower != null;
    }

    /**
     * 照強度算的強化次數（PoE2：每 per 點強度一次，最多算到 cap；有任何強度至少 1 次；沒有敵人 0 次）。
     * 舊寫法（exert.count）固定次數。
     */
    public int empowersFor(final double power) {
        if (empower == null) {
            return exerts;
        }
        if (empower.whileActive()) {
            return Empower.ALL;
        }
        if (power <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(Math.min(power, empower.cap()) / empower.per() + 1e-9));
    }

    /**
     * 強化：哪一類攻擊（attack 任何攻擊、melee 近戰、slam 猛擊、strike 打擊、shield 盾的攻擊）、什麼條件下才強化（heavy_stun：
     * 吼的時候有敵人被重暈或打到重暈中的敵人；freeze：有敵人被凍結或打到凍結中的敵人）、每幾點強度一次、最多算到幾點強度，
     * 以及強化的攻擊多了什麼。
     *
     * @param gainFire      獲得 x% 傷害為額外火焰傷害（焚獄）
     * @param coldMin       每 5 點強度附加的冰冷傷害（最小；最多算到 cap，寒夜嗥）
     * @param coldMax       同上（最大）
     * @param armourBreak   擊中時破甲 x 點（裂甲熊咆）
     * @param extraTargets  打擊多打幾個敵人（熔魂嘯）
     * @param aftershocks   猛擊多幾次餘震（震地）
     * @param chilledGround 猛擊留下 4 秒冰緩地面（寒夜嗥）
     * @param trigger       盾的攻擊觸發的技能（堅壁戰吼的盾波）；null = 沒有
     * @param more          舊寫法：更多 x% 傷害
     * @param whileActive   持續期間這一類攻擊全部算被強化（熔魂嘯），不照強度算次數
     */
    public record Empower(String kind, String condition, int per, int cap, Scaled gainFire, Scaled coldMin, Scaled coldMax,
                          Scaled armourBreak, int extraTargets, int aftershocks, boolean chilledGround, String trigger, Scaled more,
                          boolean whileActive) {

        /** 持續期間全部強化的次數（夠大就好）。 */
        public static final int ALL = 9999;

        static final List<String> KINDS = List.of("attack", "melee", "slam", "strike", "shield");
        static final List<String> CONDITIONS = List.of("heavy_stun", "freeze");

        /** 這個攻擊技能會不會被這種強化用掉。 */
        public boolean accepts(final SkillDefinition attack) {
            if (attack == null || !attack.isAttack()) {
                return false;
            }
            return switch (kind) {
                case "melee" -> attack.tags().contains(tw.exilecore.stats.Tags.MELEE);
                case "slam" -> attack.tags().contains(tw.exilecore.stats.Tags.SLAM);
                case "strike" -> attack.tags().contains(tw.exilecore.stats.Tags.STRIKE);
                case "shield" -> Warcries.SHIELD_ATTACKS.contains(attack.id());
                default -> true;
            };
        }

        /** 給玩家看的「哪一類攻擊」。 */
        public String kindText() {
            return switch (kind) {
                case "melee" -> "近戰攻擊";
                case "slam" -> "猛擊";
                case "strike" -> "打擊";
                case "shield" -> "盾的攻擊";
                default -> "攻擊";
            };
        }
    }

    /**
     * 各戰吼自己的效果。
     *
     * @param guardPer5      每 5 點強度給的守護（最多算到 guardCap 點強度；堅壁戰吼）
     * @param guardCap       守護最多算到幾點強度
     * @param guardSeconds   守護持續幾秒
     * @param allyColdMin    每 5 點強度你與隊友附加的冰冷傷害（最小；寒夜嗥，最多算到強化的 cap）
     * @param allyColdMax    同上（最大）
     * @param combust        範圍內的敵人動搖，死掉時燃爆（隱藏技能代號；焚獄戰吼）；null = 沒有
     * @param ragePerPower   每 1 點強度給幾點怒氣（裂甲熊咆）
     * @param glory          要幾點榮耀才能用（點燃敵人時照牠的強度累積；熔魂嘯）；0 = 不用
     * @param endurancePct   用掉全部磐星，每顆持續時間更多 x%（熔魂嘯）
     * @param knockback      把範圍內的敵人震退
     * @param steps          持續期間每走 1.2 公尺觸發的技能（熔魂嘯的火山步）；null = 沒有
     * @param eruption       持續期間近戰打擊打中時觸發的技能（熔魂嘯的火山噴發，冷卻 0.4 秒）；null = 沒有
     * @param minionSkills   minion_buff 只給這些技能叫出來的召喚物（銀月庇：狼群）；空的 = 全部
     */
    public record Extras(Scaled guardPer5, int guardCap, double guardSeconds, Scaled allyColdMin, Scaled allyColdMax, String combust,
                         double ragePerPower, int glory, double endurancePct, boolean knockback, String steps, String eruption,
                         List<String> minionSkills) {
        static final Extras NONE = new Extras(Scaled.of(0), 0, 0, Scaled.of(0), Scaled.of(0), null, 0, 0, 0, false, null, null, List.of());

        public Extras {
            minionSkills = minionSkills == null ? List.of() : List.copyOf(minionSkills);
        }
    }

    /** skills.yml 的 warcry 區塊；沒有回傳 null。 */
    static WarcrySpec parse(final YamlNode skill) {
        if (!skill.has("warcry")) {
            return null;
        }
        final YamlNode node = skill.node("warcry");
        final String where = node.path();
        final Scaled heal = scaled(where, node, "heal", 0, 0, 50);
        final List<ScaledMod> buff = ScaledMod.parseList(node, "buff");
        Scaled taken = Scaled.of(0);
        if (node.has("debuff")) {
            final YamlNode sub = node.node("debuff");
            taken = scaled(sub.path(), sub, "taken", 10, 0, 100);
        }
        int exerts = 0;
        Scaled exertMore = Scaled.of(0);
        if (node.has("exert")) {
            final YamlNode sub = node.node("exert");
            exerts = (int) check(sub.path(), "count", sub.integer("count", 3), 1, 10);
            exertMore = scaled(sub.path(), sub, "more", 30, 1, 200);
        }
        final Map<String, Integer> charges = new LinkedHashMap<>();
        if (node.has("charges")) {
            final YamlNode sub = node.node("charges");
            for (final String key : sub.keys()) {
                final String id = key.trim().toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z_]+")) {
                    throw new IllegalArgumentException(sub.path() + "：要寫戰星的代號（charges.yml，例如 frenzy），卻是「" + key + "」");
                }
                charges.put(id, (int) check(sub.path(), key, sub.integer(key, 1), 1, 10));
            }
        }
        final List<ScaledMod> minionBuff = ScaledMod.parseList(node, "minion_buff");
        final Empower empower = node.has("empower") ? parseEmpower(node.node("empower")) : null;
        final Extras extras = parseExtras(node);
        final boolean cry = node.bool("cry", true);
        if (heal.last() <= 0 && buff.isEmpty() && taken.last() <= 0 && exerts == 0 && charges.isEmpty() && minionBuff.isEmpty()
                && empower == null && extras.guardPer5().last() <= 0 && extras.combust() == null && extras.ragePerPower() <= 0
                && !skill.has("damage")) {
            throw new IllegalArgumentException(where + "：戰吼至少要有 heal、buff、debuff、exert、empower、guard、charges、minion_buff 或 damage 其中一個");
        }
        return new WarcrySpec(check(where, "radius", node.decimal("radius", 8), 2, 16),
                check(where, "duration", node.decimal("duration", 6), 1, 30),
                node.bool("taunt", true), heal, node.bool("allies", true), buff, taken, exerts, exertMore, charges, minionBuff,
                node.bool("spike", false), check(where, "rage_extend", node.decimal("rage_extend", 0), 0, 2),
                node.bool("recall", false), cry, empower, extras); // 2026-10-18：召歸令；2026-10-26：原作戰吼
    }

    private static Empower parseEmpower(final YamlNode node) {
        final String where = node.path();
        final String kind = node.string("kind", "attack").trim().toLowerCase(Locale.ROOT);
        if (!Empower.KINDS.contains(kind)) {
            throw new IllegalArgumentException(where + ".kind：要寫 " + String.join("、", Empower.KINDS) + "，卻是「" + kind + "」");
        }
        final String condition = node.string("condition", null);
        if (condition != null && !Empower.CONDITIONS.contains(condition)) {
            throw new IllegalArgumentException(where + ".condition：要寫 " + String.join("、", Empower.CONDITIONS) + "，卻是「" + condition + "」");
        }
        final int per = (int) check(where, "per", node.integer("per", 10), 1, 100);
        final int cap = (int) check(where, "cap", node.integer("cap", 50), per, 1000);
        Scaled coldMin = Scaled.of(0);
        Scaled coldMax = Scaled.of(0);
        if (node.has("cold_per_5_power")) {
            final YamlNode cold = node.node("cold_per_5_power");
            coldMin = scaled(cold.path(), cold, "min", 0, 0, 100000);
            coldMax = scaled(cold.path(), cold, "max", 0, 0, 100000);
        }
        return new Empower(kind, condition, per, cap, scaled(where, node, "gain_fire", 0, 0, 500), coldMin, coldMax,
                scaled(where, node, "armour_break", 0, 0, 1000000), (int) check(where, "extra_targets", node.integer("extra_targets", 0), 0, 10),
                (int) check(where, "aftershocks", node.integer("aftershocks", 0), 0, 5), node.bool("chilled_ground", false),
                node.string("trigger", null), scaled(where, node, "more", 0, 0, 500), node.bool("while_active", false));
    }

    private static Extras parseExtras(final YamlNode node) {
        final String where = node.path();
        Scaled guard = Scaled.of(0);
        int guardCap = 0;
        double guardSeconds = 0;
        if (node.has("guard")) {
            final YamlNode sub = node.node("guard");
            guard = scaled(sub.path(), sub, "per_5_power", 0, 0, 100000);
            guardCap = (int) check(sub.path(), "power_cap", sub.integer("power_cap", 20), 5, 1000);
            guardSeconds = check(sub.path(), "duration", sub.decimal("duration", 4), 0.5, 30);
        }
        Scaled allyMin = Scaled.of(0);
        Scaled allyMax = Scaled.of(0);
        if (node.has("ally_cold_per_5_power")) {
            final YamlNode sub = node.node("ally_cold_per_5_power");
            allyMin = scaled(sub.path(), sub, "min", 0, 0, 100000);
            allyMax = scaled(sub.path(), sub, "max", 0, 0, 100000);
        }
        String steps = null;
        String eruption = null;
        if (node.has("volcanic")) {
            final YamlNode sub = node.node("volcanic");
            steps = sub.string("steps", null);
            eruption = sub.string("eruption", null);
        }
        return new Extras(guard, guardCap, guardSeconds, allyMin, allyMax, node.string("combust", null),
                check(where, "rage_per_power", node.decimal("rage_per_power", 0), 0, 10),
                (int) check(where, "glory", node.integer("glory", 0), 0, 1000),
                check(where, "duration_per_endurance", node.decimal("duration_per_endurance", 0), 0, 200),
                node.bool("knockback", false), steps, eruption, node.strings("minion_skills"));
    }

    private static Scaled scaled(final String where, final YamlNode node, final String key, final double def,
                                 final double min, final double max) {
        final Scaled value = Scaled.parse(where + "." + key, node.raw(key), def);
        check(where, key, value.min(), min, max);
        check(where, key, value.first(), min, max);
        check(where, key, value.last(), min, max);
        return value;
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
