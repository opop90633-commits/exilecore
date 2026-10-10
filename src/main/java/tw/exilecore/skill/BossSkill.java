package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;

import java.util.Locale;

/**
 * Boss 的一個招式（data/boss_skills.yml）。純資料，不依賴 Bukkit。
 * <p>
 * 傷害都是「這隻怪物普通一下的幾倍」（{@code damage}），類型照 {@code type}（物理轉成那個類型，吃玩家的抗性）；
 * 大部分招式有前搖（{@code telegraph}，地上畫紅圈、紅線、扇形，玩家可以躲），發動時附近的玩家動作列也會提醒。
 *
 * @param kind      運作方式（{@link Kind}）
 * @param damage    普通一下的幾倍（0 = 不造成傷害）
 * @param type      傷害類型
 * @param cooldown  冷卻（秒）
 * @param range     目標在幾格內才會用
 * @param telegraph 前搖（秒）：Boss 站著蓄力、地上畫警告
 * @param delay     slam：前搖結束後再過幾秒才落下（這段時間地上有圈）
 * @param radius    slam／nova／zone／curse／explode：半徑
 * @param angle     cone：扇形張角
 * @param length    cone：扇形長度
 * @param count     slam：落幾顆；nova／cone：幾波；volley：射幾發；zone：幾塊；summon：叫幾隻
 * @param stagger   slam 多顆、nova／cone 多波時每顆（波）間隔幾秒
 * @param scatter   slam／zone 落點隨機偏移幾格內（at: random 時每顆各找一個玩家再偏移）
 * @param spread    volley：扇形張角
 * @param speed     volley：每 tick 幾格；charge：每 tick 衝幾格
 * @param reach     volley：最遠飛幾格；charge：最遠衝幾格
 * @param size      volley：碰撞半徑
 * @param explode   volley：打中（或飛到底）時爆炸的半徑（0 = 不爆）
 * @param duration  zone：留幾秒；curse：持續幾秒；summon：小怪活幾秒（0 = 不限）
 * @param interval  zone：幾秒一波
 * @param slow      打中的玩家緩速幾秒（0 = 不緩速）
 * @param width     charge：路線左右幾格內會被撞到
 * @param summonType summon：原版生物
 * @param summonMax  summon：同時最多幾隻（這隻 Boss 叫出來還活著的）
 * @param taken     curse：受到的傷害增加幾 %
 * @param at        slam／zone 落在哪：SELF 自己腳下、TARGET 目標腳下、RANDOM 隨機玩家腳下
 * @param visual    外觀樣式（SkillVisuals 的 style；沒寫照傷害類型）
 * @param shout     發動時 Boss 喊的話（可省略）
 * @param gaps      ring：擴散環上有幾個安全缺口（0 = 沒有，只能跑到 reach 外面）
 * @param gapAngle  ring：每個缺口幾度
 * @param safe      sanctuary：每個安全區的半徑
 * @param countPerPlayer sanctuary：範圍內每多一個玩家，多幾個安全區（2026-10；最多 10 個）
 * @param hint      怎麼應對（終局與試煉 Boss 放這招時畫面中間的副標題與聊天室的說明；沒寫照運作方式給一句，見 {@link BossAlerts}）
 * <p>
 * blink 的 damage 大於 0 就是「撲擊」：落地後過一下在腳下炸一圈（radius）。
 * <p>
 * 特殊機制的招式（2026-10）：
 * <ul>
 *   <li>beam：從 Boss 射出 count 道光束（length 長、左右各 width），前搖指著目標，之後 duration 秒內一起轉 angle 度
 *       （方向隨機），掃到的人每 interval 秒挨一下：要繞著 Boss 跑、跟著光束的反方向走</li>
 *   <li>ring：從 Boss 腳下擴散的環（從 radius 長到 reach，每 tick 長 speed 格、厚 width），count 波、每波隔 stagger 秒；
 *       環上有 gaps 個 gapAngle 度的缺口（地上畫綠色）——站進缺口；沒有缺口的環要跑到 reach 外面</li>
 *   <li>pull：duration 秒內把 radius 格內的人往 Boss 吸（每 tick speed 格），結束時 explode 格內爆炸：要一直往外跑</li>
 *   <li>orbs：叫出 count 顆光球，各自追一個玩家（每 tick speed 格、活 duration 秒），碰到人（size）或時間到就在 explode 格內爆炸</li>
 *   <li>sanctuary：長前搖的全場大招（radius 格內全部），地上會出現 count 個綠色安全區（半徑 safe、離 Boss scatter 格左右）：
 *       躲進去就沒事</li>
 * </ul>
 */
public record BossSkill(String id, String name, Kind kind, double damage, DamageType type, double cooldown, double range,
                        double telegraph, double delay, double radius, double angle, double length, int count, double stagger,
                        double scatter, double spread, double speed, double reach, double size, double explode,
                        double duration, double interval, double slow, double width, String summonType, int summonMax,
                        double taken, Target at, String visual, String shout, int gaps, double gapAngle, double safe, String hint,
                        int countPerPlayer) {

    public enum Kind {
        /** 延遲落下：地上先畫圈，過一下炸一圈（猛擊、隕石、雷暴）。 */
        SLAM,
        /** 以 Boss 為中心一圈（重踏、潮湧）。 */
        NOVA,
        /** 前方扇形（吐息）。 */
        CONE,
        /** 齊射：往目標方向射出一排魔法彈。 */
        VOLLEY,
        /** 地面效果：留一塊會一直傷害的區域（毒沼、虛空）。 */
        ZONE,
        /** 衝鋒：往目標衝過去，沿路撞人。 */
        CHARGE,
        /** 召喚小怪。 */
        SUMMON,
        /** 詛咒：範圍內的玩家一段時間受到的傷害增加。 */
        CURSE,
        /** 閃現到目標背後。 */
        BLINK,
        /** 光束掃射：幾道光束從 Boss 射出，一起轉一圈（或一段角度）。 */
        BEAM,
        /** 擴散環：從 Boss 腳下往外擴散的環，可以有安全的缺口。 */
        RING,
        /** 引力漩渦：把附近的人往 Boss 吸，最後爆炸。 */
        PULL,
        /** 追蹤光球：慢慢追著玩家的光球，碰到就爆炸。 */
        ORBS,
        /** 全場大招：除了地上的綠色安全區，範圍內全部挨打。 */
        SANCTUARY;

        static Kind parse(final String where, final String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".kind：不認得「" + text
                        + "」（slam、nova、cone、volley、zone、charge、summon、curse、blink、beam、ring、pull、orbs、sanctuary）");
            }
        }

        /** 放的時候 Boss 會站著不動、這一招結束前不會放下一招（光束、擴散環、漩渦、全場大招）。 */
        public boolean channelled() {
            return this == BEAM || this == RING || this == PULL || this == SANCTUARY;
        }

        /** 可以當場地危害（hazards：照時間自己落下，等 Boss 手上這一招放完才落）的種類。 */
        public boolean hazardCapable() {
            return this == SLAM || this == ZONE || this == ORBS;
        }
    }

    public enum Target {
        SELF, TARGET, RANDOM,
        /** 2026-10（世界 Boss）：從 Boss 腳下轉出去的螺旋，一顆接一顆。 */
        SPIRAL,
        /** 從 Boss 往四個方向（十字或斜十字）一格一格炸出去。 */
        CROSS,
        /** 從 Boss 往目標一直線炸過去（地脈追獵）。 */
        LINE,
        /** 圍著目標一圈（星牢：待在圈裡面）。 */
        CAGE;

        static Target parse(final String where, final String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".at：要寫 self、target、random、spiral、cross、line 或 cage");
            }
        }
    }

    /** 這個招式會不會造成傷害（blink 寫了 damage 就是撲擊，會）。 */
    public boolean dealsDamage() {
        return damage > 0 && kind != Kind.SUMMON && kind != Kind.CURSE;
    }

    /** 這隻 Boss 用時的冷卻與傷害（招式組的覆蓋值；小於 0 = 照原本的）。 */
    public BossSkill with(final double newDamage, final double newCooldown) {
        return new BossSkill(id, name, kind, newDamage >= 0 ? newDamage : damage, type, newCooldown >= 0 ? newCooldown : cooldown, range,
                telegraph, delay, radius, angle, length, count, stagger, scatter, spread, speed, reach, size, explode, duration, interval,
                slow, width, summonType, summonMax, taken, at, visual, shout, gaps, gapAngle, safe, hint, countPerPlayer);
    }

    /** 閃現到目標旁邊之後落地震一下（撲擊）。 */
    public boolean pounce() {
        return kind == Kind.BLINK && damage > 0;
    }

    /** 光球活幾 tick（到了就自己爆炸；至少 1 秒）。 */
    public long orbTicks() {
        return Math.max(20, Math.round(duration * 20));
    }

    /** 齊射、落點：同一招打到同一個人至少隔這麼多 tick（0.5 秒）。 */
    public static final int SAME_SKILL_GAP_TICKS = 10;

    /**
     * 同一招打到同一個人至少隔幾 tick（0 = 不限）。玩家沒有原版的受傷無敵時間，所以齊射站近時七、八發一起打中、
     * 好幾顆光球追到、好幾塊地面效果疊在腳下時，一瞬間就是這一招的好幾倍——一個時間只算一下：
     * 齊射、落點 0.5 秒內只算第一下；光球一次只會被一顆打到（從 Boss 身邊不同的位置出發，會前後差半秒多追到，
     * 所以照光球活多久算）；地面效果照自己的間隔（疊在一起也只算一塊）。
     * 新星、扇形、擴散環、光束、衝鋒、漩渦、全場大招一波本來就只打一次（或自己有間隔），不限。
     */
    public long hitGapTicks() {
        return switch (kind) {
            case VOLLEY, SLAM -> SAME_SKILL_GAP_TICKS;
            case ORBS -> orbTicks();
            case ZONE -> Math.max(2, Math.round(interval * 20)) - 1;
            default -> 0;
        };
    }

    /** 傷害類型預設的外觀樣式。 */
    public static String visualFor(final DamageType type) {
        return switch (type) {
            case FIRE -> "boss_fire";
            case COLD -> "boss_cold";
            case LIGHTNING -> "boss_lightning";
            case CHAOS -> "boss_chaos";
            default -> "boss_physical";
        };
    }

    static BossSkill parse(final String id, final YamlNode node) {
        final String where = node.path();
        final Kind kind = Kind.parse(where, node.requireString("kind"));
        final DamageType type = DamageType.fromTag(node.string("type", "physical").trim().toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new IllegalArgumentException(where + ".type：不認得的傷害類型（physical、fire、cold、lightning、chaos）");
        }
        final String summonType = node.string("summon", null);
        if (kind == Kind.SUMMON && (summonType == null || summonType.isBlank())) {
            throw new IllegalArgumentException(where + "：summon 招式要寫 summon: 原版生物代號");
        }
        final double damage = check(where, "damage", node.decimal("damage", kind == Kind.SUMMON || kind == Kind.CURSE || kind == Kind.BLINK ? 0 : 1.5), 0, 20);
        if (damage > 0 && (kind == Kind.SUMMON || kind == Kind.CURSE)) {
            throw new IllegalArgumentException(where + ".damage：召喚與詛咒不造成傷害，不要寫 damage");
        }
        if (damage <= 0 && kind != Kind.SUMMON && kind != Kind.CURSE && kind != Kind.BLINK) {
            throw new IllegalArgumentException(where + ".damage：這種招式要造成傷害（普通一下的幾倍）");
        }
        final int gaps = (int) check(where, "gaps", node.integer("gaps", 0), 0, 6);
        final double gapAngle = check(where, "gap_angle", node.decimal("gap_angle", 45), 10, 120);
        if (gaps * gapAngle > 300) {
            throw new IllegalArgumentException(where + "：缺口加起來太大（gaps × gap_angle 最多 300 度），環就沒有意義了");
        }
        return new BossSkill(id, node.string("name", id), kind, damage, type,
                check(where, "cooldown", node.decimal("cooldown", 10), 1, 300),
                check(where, "range", node.decimal("range", 12), 1, 48),
                check(where, "telegraph", node.decimal("telegraph", switch (kind) {
                    case SUMMON, BLINK -> 0.5;
                    case SANCTUARY -> 2.5;
                    case BEAM, PULL -> 1.2;
                    default -> 0.8;
                }), 0, 5),
                check(where, "delay", node.decimal("delay", kind == Kind.SLAM ? 1.0 : 0), 0, 5),
                check(where, "radius", node.decimal("radius", switch (kind) {
                    case RING -> 1.5;
                    case PULL -> 12;
                    case SANCTUARY -> 18;
                    default -> 3.5;
                }), 0.5, 32),
                check(where, "angle", node.decimal("angle", kind == Kind.BEAM ? 180 : 70), 5, 360),
                check(where, "length", node.decimal("length", kind == Kind.BEAM ? 14 : 8), 1, 48),
                (int) check(where, "count", node.integer("count", switch (kind) {
                    case VOLLEY -> 5;
                    case SUMMON, SANCTUARY -> 2;
                    case ORBS -> 3;
                    default -> 1;
                }), 1, 20),
                check(where, "stagger", node.decimal("stagger", kind == Kind.RING ? 0.8 : 0.3), 0, 3),
                check(where, "scatter", node.decimal("scatter", kind == Kind.SANCTUARY ? 9 : 0), 0, 16),
                check(where, "spread", node.decimal("spread", 45), 0, 360),
                speed(where, kind, node.decimal("speed", switch (kind) {
                    case CHARGE -> 1.0;
                    case RING -> 0.4;
                    case PULL -> 0.1;
                    case ORBS -> 0.18;
                    default -> 1.2;
                })),
                check(where, "reach", node.decimal("reach", switch (kind) {
                    case CHARGE -> 12;
                    case RING -> 16;
                    default -> 24;
                }), 2, 48),
                check(where, "size", node.decimal("size", kind == Kind.ORBS ? 0.8 : 0.6), 0.2, 2),
                check(where, "explode", node.decimal("explode", switch (kind) {
                    case PULL -> 4;
                    case ORBS -> 2.5;
                    default -> 0;
                }), 0, 8),
                check(where, "duration", node.decimal("duration", switch (kind) {
                    case ZONE -> 6;
                    case CURSE, ORBS -> 8;
                    case BEAM, PULL -> 3;
                    default -> 0;
                }), 0, 120),
                check(where, "interval", node.decimal("interval", kind == Kind.BEAM ? 0.25 : 0.5), 0.1, 5),
                check(where, "slow", node.decimal("slow", 0), 0, 10),
                check(where, "width", node.decimal("width", switch (kind) {
                    case BEAM -> 1.2;
                    case RING -> 0.8;
                    default -> 1.6;
                }), 0.5, 4),
                summonType == null ? null : summonType.trim().toUpperCase(Locale.ROOT),
                (int) check(where, "summon_max", node.integer("summon_max", 6), 1, 20),
                check(where, "taken", node.decimal("taken", 20), 0, 100),
                Target.parse(where, node.string("at", kind == Kind.ZONE ? "target" : "self")),
                node.string("visual", visualFor(type)).trim(), node.string("shout", null),
                gaps, gapAngle, check(where, "safe", node.decimal("safe", 2.5), 1, 6), parseHint(where, node.string("hint", null)),
                (int) check(where, "count_per_player", node.integer("count_per_player", 0), 0, 5));
    }

    /** 提示最多幾個字（畫面中間的副標題一行放得下）。 */
    public static final int MAX_HINT = 32;

    private static String parseHint(final String where, final String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        final String trimmed = hint.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_HINT) {
            throw new IllegalArgumentException(where + ".hint：最多 " + MAX_HINT + " 個字（畫面中間的副標題放得下），卻有 "
                    + trimmed.codePointCount(0, trimmed.length()) + " 個");
        }
        return trimmed;
    }

    /** 漩渦最多拉這麼快（每 tick 幾格）：跑步每 tick 大約 0.17 格，再快就逃不掉了。 */
    public static final double MAX_PULL_SPEED = 0.15;
    /** 光球最快這麼快：比跑步慢，才甩得掉。 */
    public static final double MAX_ORB_SPEED = 0.25;

    private static double speed(final String where, final Kind kind, final double value) {
        check(where, "speed", value, 0.05, 4);
        if (kind == Kind.PULL && value > MAX_PULL_SPEED) {
            throw new IllegalArgumentException(where + ".speed：漩渦每 tick 最多拉 " + MAX_PULL_SPEED + " 格（再快玩家跑步也逃不掉），卻是 " + value);
        }
        if (kind == Kind.ORBS && value > MAX_ORB_SPEED) {
            throw new IllegalArgumentException(where + ".speed：光球每 tick 最快 " + MAX_ORB_SPEED + " 格（要比跑步慢才甩得掉），卻是 " + value);
        }
        return value;
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
