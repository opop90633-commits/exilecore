package tw.exilecore.map;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 2026-11-02 照站長：Boss 換階段的鎖血機制不只有「打掉結晶」。這裡是設定（maps.yml 的 phases 寫 {@code mechanic:}）、
 * 沒寫時的預設輪流，與幾個機制的計算（純邏輯，不依賴 Bukkit；跑起來的部分在 {@link MapBosses}）。
 * <ul>
 *   <li>{@link Kind#CRYSTAL 結晶}：原本的護盾——Boss 無敵，限時打掉結晶，不然回血（{@link MapConfig.Shield}）。</li>
 *   <li>{@link Kind#ZONES 站位圈}：Boss 無敵，地上出現幾個光圈（每人份、最多算 6 人），有人站在圈裡就充能，全部充滿才破盾；
 *       站在圈裡會一直疊易傷（離開後慢慢消退）。時間到沒完成 Boss 回血。</li>
 *   <li>{@link Kind#COUNTDOWN 倒數大招}：Boss 無敵、蓄一招全場重擊，地上 1～2 個安全區；倒數結束時安全區外的人受到最大生命一定比例的傷害，
 *       放完之後破盾（2026-11-02 照站長：預設不再虛弱；yml 寫 weak、weak_taken 才有）。</li>
 *   <li>{@link Kind#ELEMENT 元素弱點}：Boss 不無敵，只吃一種元素（火、冰、電輪流，排除 Boss 抗性最高的那種），其他傷害只打一成；
 *       限時打掉這個階段一定比例的血就破盾，時間到沒完成 Boss 回血。</li>
 * </ul>
 * 這次之前只有結晶一種（phases 的 shield）。
 */
public final class PhaseMechanic {

    private PhaseMechanic() {
    }

    /** 鎖血機制的種類。 */
    public enum Kind {
        /** 不鎖（寫 {@code mechanic: none}：這個階段明確不要鎖血機制）。 */
        NONE("none", "沒有"),
        CRYSTAL("crystal", "結晶"),
        ZONES("zones", "站位圈"),
        COUNTDOWN("countdown", "倒數大招"),
        ELEMENT("element", "元素弱點"),
        /**
         * 2026-11-04 照站長「鎖血階段王還是能持續放招、靠招式解鎖、免疫控場、懲罰 30～45%」：破綻。Boss 不無敵、照常出招、免疫控場；
         * 鎖血期間只有「破綻」裡打的傷害算：牠每放完一招，沒被那招打到、又在牠 reach 格內的人身上開 window 秒的破綻（受到的傷害 ×openTaken）；
         * 鎖有 layers 層，一個破綻最多剝一層（這一階段 share 成血量 ÷ layers），全部剝掉才破盾；duration 秒內沒剝完回 heal%。
         * 破綻外的傷害不算也不扣血（血不會掉到鎖住時的門檻以下）。
         */
        OPENING("opening", "破綻");

        private final String id;
        private final String label;

        Kind(final String id, final String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** 設定檔的代號（大小寫不拘）；不認得回傳 null。 */
        public static Kind parse(final String text) {
            if (text == null) {
                return null;
            }
            final String key = text.trim().toLowerCase(Locale.ROOT);
            for (final Kind kind : values()) {
                if (kind.id.equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * 沒寫 mechanic 的階段配的機制。2026-11-04 照站長「一隻 Boss 最多一個鎖血；站位圈、倒數大招、元素弱點、結晶都拿掉」：只剩破綻，
     * 而且 {@link #resolve} 只配給第一個沒寫的階段，其他階段不鎖；這次之前站位圈、倒數大招輪流配給每個沒寫的階段。
     */
    static final List<Kind> ROTATION = List.of(Kind.OPENING);

    /**
     * 站位圈。
     *
     * 2026-11-02 照站長（第二次）：圈數 = 在場的人數（{@link #MIN_ZONES}～{@link #MAX_ZONES}，跟著人數變），沒人站的圈充能慢慢減少；
     * 這次之前每人 count 個、至少 2 個最多 8 個，沒人站時充能不動。
     *
     * @param radius     圈的半徑（格）
     * @param charge     一個圈要有人站幾秒才充滿
     * @param drain      沒人站時充能減少的速度是充能的幾倍（0.25 = 四分之一：充滿 5 秒的圈沒人站 20 秒才掉光）
     * @param duration   幾秒內要全部充滿
     * @param heal       沒完成時 Boss 回幾 % 最大生命
     * @param vulnPerSecond 站在圈裡每秒多受幾 % 傷害
     * @param vulnMax    易傷最多疊到幾 %
     * @param vulnDecay  離開圈後每秒消退幾 %
     */
    public record Zones(double radius, double charge, double drain, double duration, double heal, double vulnPerSecond, double vulnMax,
                        double vulnDecay) {
    }

    /**
     * 倒數大招。
     *
     * @param name       大招的名字
     * @param delay      倒數幾秒
     * @param safe       安全區幾個（1～2）
     * @param safeRadius 安全區半徑（格）
     * @param damage     安全區外的人受到最大生命（＋最大能量護盾）的幾成（0.6 = 60%；最多 0.95，不會滿血直接秒殺）
     * @param weak       放完之後 Boss 虛弱幾秒
     * @param weakTaken  虛弱時 Boss 受到的傷害更多幾成（0.5 = 50%）
     */
    public record Countdown(String name, double delay, int safe, double safeRadius, double damage, double weak, double weakTaken) {
    }

    /**
     * 元素弱點。
     *
     * @param duration 幾秒內要打完
     * @param heal     沒完成時 Boss 回幾 % 最大生命
     * @param share    要打掉這個階段血量（這個門檻到下一個門檻）的幾成
     * @param rotate   幾秒換一種元素
     * @param warn     換之前幾秒提示
     * @param other    其他傷害打幾成（0.1 = 10%）
     */
    public record Element(double duration, double heal, double share, double rotate, double warn, double other) {
    }

    /**
     * 破綻（2026-11-04）。
     *
     * @param duration  幾秒內要剝完
     * @param heal      沒剝完時 Boss 回幾 % 最大生命（2026-11-04 照站長：15%；這次之前 30～45%）
     * @param share     整個鎖要打掉這個階段血量（這個門檻到下一個門檻）的幾成
     * @param layers    幾層（一個破綻最多剝一層；2E 一下的人也要躲滿 layers 招）
     * @param window    破綻幾秒
     * @param reach     招式放完時離 Boss 幾格內的人才有破綻（站太遠沒躲招不算）
     * @param openTaken 破綻裡 Boss 受到的傷害倍率（1.5 = 比平常多 50%）
     */
    public record Opening(double duration, double heal, double share, int layers, double window, double reach, double openTaken) {
    }

    /** 一個階段的鎖血機制（只有 kind 對應的那一個欄位不是 null）。 */
    public record Spec(Kind kind, MapConfig.Shield crystal, Zones zones, Countdown countdown, Element element, Opening opening) {

        public static final Spec NONE = new Spec(Kind.NONE, null, null, null, null, null);

        public static Spec crystal(final MapConfig.Shield shield) {
            return new Spec(Kind.CRYSTAL, shield, null, null, null, null);
        }

        public static Spec zones(final Zones zones) {
            return new Spec(Kind.ZONES, null, zones, null, null, null);
        }

        public static Spec countdown(final Countdown countdown) {
            return new Spec(Kind.COUNTDOWN, null, null, countdown, null, null);
        }

        public static Spec element(final Element element) {
            return new Spec(Kind.ELEMENT, null, null, null, element, null);
        }

        public static Spec opening(final Opening opening) {
            return new Spec(Kind.OPENING, null, null, null, null, opening);
        }

        /** 限時幾秒（倒數大招是倒數的秒數）。 */
        public double seconds() {
            return switch (kind) {
                case CRYSTAL -> crystal.duration();
                case ZONES -> zones.duration();
                case COUNTDOWN -> countdown.delay();
                case ELEMENT -> element.duration();
                case OPENING -> opening.duration();
                case NONE -> 0;
            };
        }

        /** 沒完成時 Boss 回幾 % 最大生命（倒數大招沒有失敗，0）。 */
        public double heal() {
            return switch (kind) {
                case CRYSTAL -> crystal.heal();
                case ZONES -> zones.heal();
                case ELEMENT -> element.heal();
                case OPENING -> opening.heal();
                default -> 0;
            };
        }

        /** 這段時間 Boss 打不動（元素弱點不是無敵）。 */
        public boolean invulnerable() {
            return kind == Kind.CRYSTAL || kind == Kind.ZONES || kind == Kind.COUNTDOWN;
        }
    }

    // ---- 預設值 ----

    static final int MIN_ZONES = 1;
    static final int MAX_ZONES = 6;
    static final double DEFAULT_DURATION = 25;
    static final double DEFAULT_HEAL = 30;
    /** 倒數大招打幾成：一般終局 60%，極境、巔峰 85%（接近致命，不會滿血直接秒殺）。 */
    static final double COUNTDOWN_DAMAGE = 0.6;
    static final double COUNTDOWN_DAMAGE_HEAVY = 0.85;
    static final double COUNTDOWN_DAMAGE_MAX = 0.95;

    static Zones defaultZones(final double duration, final double heal) {
        return new Zones(2.5, 5, 0.25, duration, heal, 6, 60, 4);
    }

    static Countdown defaultCountdown(final boolean heavy) {
        return new Countdown("全場重擊", 8, 2, 3, heavy ? COUNTDOWN_DAMAGE_HEAVY : COUNTDOWN_DAMAGE, 0, 0); // 2026-11-02 照站長：放完不再虛弱；這次之前虛弱 6 秒、受到的傷害更多 50%
    }

    static Element defaultElement(final double duration, final double heal) {
        return new Element(duration, heal, 0.3, 12, 3, 0.1);
    }

    /** 2026-11-04：破綻的預設：30 秒、回血照 heal、這一階段三成的血、3 層、破綻 3 秒、30 格內、破綻裡 ×1.5（2026-11-05 照站長「12 格放寬給遠程」；這次之前 12 格）。 */
    static final double OPENING_DURATION = 30;

    static Opening defaultOpening(final double heal) {
        // 2026-11-06 照站長「破綻改成每階段 1 個，傷害不用 1.5 倍」：1 層（躲 1 招就破）、破綻裡 ×1；這次之前 3 層、×1.5
        return new Opening(OPENING_DURATION, OPENING_HEAL, 0.3, 1, 3, 30, 1.0); // 2026-11-04 照站長「破綻沒解除改成回 15% 生命」；這次之前照 heal（各 Boss 30～45%）
    }

    /** 2026-11-04 照站長：破綻沒剝完 Boss 回 15% 生命（yml 寫了 heal 照寫的）；這次之前照各 Boss 的 30～45%。 */
    static final double OPENING_HEAL = 15;

    static Opening parseOpening(final YamlNode node, final double heal) {
        final Opening d = defaultOpening(heal);
        return new Opening(seconds(node, "duration", d.duration()), clamp(node.decimal("heal", d.heal()), 0, 100),
                clamp(node.decimal("share", d.share()), 0.05, 0.9), (int) clamp(node.integer("layers", d.layers()), 1, 10),
                clamp(node.decimal("window", d.window()), 1, 10), clamp(node.decimal("reach", d.reach()), 4, 60),
                clamp(node.decimal("taken", d.openTaken()), 0.5, 5));
    }

    // ---- 讀設定 ----

    /**
     * 讀階段的 {@code mechanic:}：可以只寫種類（{@code mechanic: zones}，數值照預設），或寫成
     * {@code mechanic: { type: zones, count: 1, duration: 25, heal: 30, … }}（crystal 的數值寫在同一個階段的 shield）。沒寫回傳 null（照預設規則：有 shield 是結晶，終局以上的輪流）。
     * 限時與回血沒寫時照同一個階段的 shield（沒有就 25 秒、30%）。
     *
     * @param shield 同一個階段的 shield（沒有 null）
     */
    public static Spec parse(final YamlNode phase, final MapConfig.Shield shield) {
        if (!phase.has("mechanic")) {
            return null;
        }
        final Object raw = phase.raw("mechanic");
        final YamlNode node;
        final String type;
        if (raw instanceof Map<?, ?>) {
            node = phase.node("mechanic");
            type = node.string("type", null);
        } else {
            node = YamlNode.of(phase.path() + ".mechanic", Map.of());
            type = String.valueOf(raw);
        }
        final Kind kind = Kind.parse(type);
        if (kind == null) {
            throw new IllegalArgumentException(phase.path() + ".mechanic：不認得「" + type + "」（要寫 opening、crystal、zones、countdown、element 或 none）");
        }
        final double duration = shield == null ? DEFAULT_DURATION : shield.duration();
        final double heal = shield == null ? DEFAULT_HEAL : shield.heal();
        return switch (kind) {
            case NONE -> Spec.NONE;
            // 結晶的數值照同一個階段的 shield（mechanic 裡的 type 是機制的種類，不是結晶的生物）；沒寫 shield 用預設的結晶
            case CRYSTAL -> Spec.crystal(shield != null ? shield : MapConfig.parseShield(YamlNode.of(phase.path() + ".shield", Map.of())));
            case ZONES -> Spec.zones(parseZones(node, duration, heal));
            case COUNTDOWN -> Spec.countdown(parseCountdown(node));
            case ELEMENT -> Spec.element(parseElement(node, duration, heal));
            case OPENING -> Spec.opening(parseOpening(node, heal));
        };
    }

    static Zones parseZones(final YamlNode node, final double duration, final double heal) {
        final Zones d = defaultZones(duration, heal);
        return new Zones(clamp(node.decimal("radius", d.radius()), 1, 6), clamp(node.decimal("charge", d.charge()), 1, 30),
                clamp(node.decimal("drain", d.drain()), 0, 1), seconds(node, "duration", d.duration()), clamp(node.decimal("heal", d.heal()), 0, 100),
                clamp(node.decimal("vuln", d.vulnPerSecond()), 0, 50), clamp(node.decimal("vuln_max", d.vulnMax()), 0, 300),
                clamp(node.decimal("vuln_decay", d.vulnDecay()), 0.1, 100));
    }

    static Countdown parseCountdown(final YamlNode node) {
        final Countdown d = defaultCountdown(false);
        final double delay = node.decimal("delay", d.delay());
        if (delay < 3 || delay > 30) {
            throw new IllegalArgumentException(node.path() + ".delay：倒數要在 3 到 30 秒之間");
        }
        return new Countdown(node.string("name", d.name()), delay, (int) clamp(node.integer("safe", d.safe()), 1, 2),
                clamp(node.decimal("safe_radius", d.safeRadius()), 1.5, 6), clamp(node.decimal("damage", d.damage()), 0, COUNTDOWN_DAMAGE_MAX),
                clamp(node.decimal("weak", d.weak()), 0, 30), clamp(node.decimal("weak_taken", d.weakTaken()), 0, 3));
    }

    static Element parseElement(final YamlNode node, final double duration, final double heal) {
        final Element d = defaultElement(duration, heal);
        final double rotate = clamp(node.decimal("rotate", d.rotate()), 4, 60);
        return new Element(seconds(node, "duration", d.duration()), clamp(node.decimal("heal", d.heal()), 0, 100),
                clamp(node.decimal("share", d.share()), 0.05, 0.9), rotate, clamp(node.decimal("warn", d.warn()), 0, rotate - 1),
                clamp(node.decimal("other", d.other()), 0, 1));
    }

    private static double seconds(final YamlNode node, final String key, final double def) {
        final double value = node.decimal(key, def);
        if (value < 5 || value > 120) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要在 5 到 120 秒之間");
        }
        return value;
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- 每個階段配到哪一種 ----

    /** 輪流的起點用的鍵：終局代號（極境多 :uber）。 */
    public static String key(final String mapId, final boolean uber) {
        return uber ? mapId + ":uber" : mapId;
    }

    /** 沒寫 mechanic、也沒有 shield 的階段要不要輪流配一個：終局、極境、巔峰（特別的實例、一般地圖、試煉照寫的）。 */
    static boolean autoFill(final boolean isFinal, final boolean special) {
        return isFinal && !special;
    }

    /**
     * 預設的輪流（純函式）：fixed 是每個階段寫死的種類（null = 要輪流配），回傳每個階段的種類。
     * 從 {@link #ROTATION} 裡照 key 的雜湊決定起點，跳過這隻 Boss 其他階段已經用過的（全用過了才重複）。
     */
    static List<Kind> rotation(final String key, final List<Kind> fixed) {
        final List<Kind> used = new ArrayList<>();
        for (final Kind kind : fixed) {
            if (kind != null && kind != Kind.NONE) {
                used.add(kind);
            }
        }
        final int start = start(key);
        final List<Kind> result = new ArrayList<>();
        int auto = 0;
        for (final Kind kind : fixed) {
            if (kind != null) {
                result.add(kind);
                continue;
            }
            Kind pick = null;
            for (int i = 0; i < ROTATION.size(); i++) {
                final Kind candidate = ROTATION.get((start + auto + i) % ROTATION.size());
                if (!used.contains(candidate)) {
                    pick = candidate;
                    break;
                }
            }
            if (pick == null) {
                pick = ROTATION.get((start + auto) % ROTATION.size());
            }
            used.add(pick);
            result.add(pick);
            auto++;
        }
        return result;
    }

    /** 輪流的起點：代號的雜湊（高低位混一下，四個終局的起點才不會擠在同一種）對 {@link #ROTATION} 取餘數。 */
    static int start(final String key) {
        final int hash = key == null ? 0 : key.hashCode();
        return Math.floorMod(hash ^ (hash >>> 16), ROTATION.size());
    }

    /**
     * 這隻 Boss 每個階段的鎖血機制（null = 這個階段不鎖）：寫了 mechanic 照寫的；有 shield 是結晶；
     * 其他的 autoFill 時照 {@link #rotation} 輪流配（限時、回血照這隻 Boss 第一個寫了 shield 的階段，倒數大招的傷害 heavy 時比較高）。
     */
    public static List<Spec> resolve(final String key, final List<MapConfig.Phase> phases, final boolean autoFill, final boolean heavy) {
        final List<Kind> fixed = new ArrayList<>();
        MapConfig.Shield reference = null;
        for (final MapConfig.Phase phase : phases) {
            final Spec explicit = explicit(phase);
            fixed.add(explicit != null ? explicit.kind() : autoFill ? null : Kind.NONE);
            if (reference == null && phase.shield() != null) {
                reference = phase.shield();
            }
        }
        final double duration = reference == null ? DEFAULT_DURATION : reference.duration();
        final double heal = reference == null ? DEFAULT_HEAL : reference.heal();
        final List<Kind> kinds = rotation(key, fixed);
        final List<Spec> specs = new ArrayList<>();
        // 2026-11-06 照站長「破綻改成每階段 1 個」：沒寫的階段都配（autoFill 時）；這次之前（11-04）一隻 Boss 最多一個鎖血，沒寫的只配第一個
        for (int i = 0; i < phases.size(); i++) {
            final Spec explicit = explicit(phases.get(i));
            if (explicit != null) {
                specs.add(explicit.kind() == Kind.NONE ? null : explicit);
                continue;
            }
            if (!autoFill) {
                specs.add(null);
                continue;
            }
            specs.add(switch (kinds.get(i)) {
                case ZONES -> Spec.zones(defaultZones(duration, heal));
                case COUNTDOWN -> Spec.countdown(defaultCountdown(heavy));
                case ELEMENT -> Spec.element(defaultElement(duration, heal));
                case OPENING -> Spec.opening(defaultOpening(heal));
                default -> null;
            });
        }
        // 2026-11-06 照站長「破綻改成每階段 1 個」：有破綻的 Boss，每個階段都有（寫 none、沒寫的照第一個破綻）
        Spec first = null;
        for (final Spec spec : specs) {
            if (spec != null && spec.kind() == Kind.OPENING) {
                first = spec;
                break;
            }
        }
        if (first != null) {
            for (int i = 0; i < specs.size(); i++) {
                if (specs.get(i) == null) {
                    specs.set(i, first);
                }
            }
        }
        return specs;
    }

    /** 這個階段自己寫的（mechanic，或只寫了 shield 的結晶）；都沒寫 null。 */
    static Spec explicit(final MapConfig.Phase phase) {
        if (phase.mechanic() != null) {
            return phase.mechanic();
        }
        return phase.shield() != null ? Spec.crystal(phase.shield()) : null;
    }

    // ---- 站位圈 ----

    /**
     * 圈的總數 = 在場的人數，最少 {@link #MIN_ZONES}、最多 {@link #MAX_ZONES}（2026-11-02 照站長第二次：單人一個圈，不用來回跑；
     * 這次之前每人份、至少 2 個最多 8 個）。
     */
    static int zoneCount(final int players) {
        return Math.max(MIN_ZONES, Math.min(MAX_ZONES, players));
    }

    /**
     * 圈的充能（0～1）：有人站著就每秒加 1 ÷ charge；沒人站時每秒減 drain ÷ charge（明顯比充能慢，最低 0）。
     * 2026-11-02 照站長第二次：沒人站會慢慢退；這次之前停在原處。
     */
    static double charge(final double current, final boolean occupied, final double seconds, final double chargeSeconds, final double drain) {
        final double rate = seconds / Math.max(0.05, chargeSeconds);
        if (!occupied) {
            return Math.max(0, Math.min(1, current) - rate * Math.max(0, drain));
        }
        final double next = current + rate;
        return next >= 1 - 1e-9 ? 1 : next; // 浮點誤差（0.1 加十次是 0.999…）：差一點點就算滿
    }

    /** 易傷（%）：站在圈裡每秒加 vulnPerSecond（最多 vulnMax），離開後每秒消退 vulnDecay（最低 0）。 */
    static double vulnerability(final double current, final boolean inside, final double seconds, final Zones zones) {
        if (inside) {
            return Math.min(zones.vulnMax(), current + zones.vulnPerSecond() * seconds);
        }
        return Math.max(0, current - zones.vulnDecay() * seconds);
    }

    /** 全部充滿了沒。 */
    static boolean allCharged(final double[] charges) {
        for (final double value : charges) {
            if (value < 1) {
                return false;
            }
        }
        return charges.length > 0;
    }

    // ---- 倒數大招 ----

    /** 安全區（與站位圈）的判定放寬多少格：地上的粒子是一圈點、網路延遲。 */
    static final double SPOT_SLACK = 0.75;

    /** 站在任何一個圈（圓心 x、z，半徑 radius，再放寬 {@link #SPOT_SLACK}）裡面。 */
    static boolean inside(final double x, final double z, final List<double[]> centers, final double radius) {
        final double reach = (radius + SPOT_SLACK) * (radius + SPOT_SLACK);
        for (final double[] center : centers) {
            final double dx = x - center[0];
            final double dz = z - center[1];
            if (dx * dx + dz * dz <= reach) {
                return true;
            }
        }
        return false;
    }

    /** 安全區外的人受到多少傷害：（最大生命＋最大能量護盾）× damage（damage 最多 {@link #COUNTDOWN_DAMAGE_MAX}，滿血不會被直接秒殺）。 */
    static double slamDamage(final double maxLife, final double maxEnergyShield, final double damage) {
        return Math.max(0, maxLife + Math.max(0, maxEnergyShield)) * Math.max(0, Math.min(COUNTDOWN_DAMAGE_MAX, damage));
    }

    // ---- 元素弱點 ----

    static final List<DamageType> ELEMENTS = List.of(DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING);

    /**
     * 這一下打進 Boss 的倍率：弱點元素的部分照算，其他的只算 other 成。dealt 是這一下每種傷害多少（減免後）；
     * 沒有傷害（或 weak 是 null）回傳 1。
     */
    static double elementFactor(final Map<DamageType, Double> dealt, final DamageType weak, final double other) {
        if (weak == null || dealt == null) {
            return 1;
        }
        double total = 0;
        double matched = 0;
        for (final Map.Entry<DamageType, Double> entry : dealt.entrySet()) {
            final double amount = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            total += amount;
            if (entry.getKey() == weak) {
                matched += amount;
            }
        }
        if (total <= 0) {
            return 1;
        }
        return (matched + (total - matched) * other) / total;
    }

    /**
     * 可以輪到的元素：火、冰、電，排除 Boss 抗性最高的那一種（只有一種比其他都高時才排除；一樣高的不排除）。
     *
     * @param resists Boss 的火、冰、電抗性（沒寫的算 0）
     */
    static List<DamageType> allowedElements(final Map<DamageType, Double> resists) {
        DamageType highest = null;
        double best = Double.NEGATIVE_INFINITY;
        boolean tie = false;
        for (final DamageType type : ELEMENTS) {
            final double value = resists == null ? 0 : resists.getOrDefault(type, 0.0);
            if (value > best + 1e-9) {
                best = value;
                highest = type;
                tie = false;
            } else if (Math.abs(value - best) <= 1e-9) {
                tie = true;
            }
        }
        final List<DamageType> allowed = new ArrayList<>(ELEMENTS);
        if (!tie && highest != null) {
            allowed.remove(highest);
        }
        return allowed;
    }

    /** 下一種元素：可以輪到的裡面隨機一種，不跟現在的一樣（只剩一種時就是那一種）。 */
    static DamageType nextElement(final List<DamageType> allowed, final DamageType current, final RandomGenerator random) {
        final List<DamageType> choices = new ArrayList<>(allowed);
        if (choices.size() > 1) {
            choices.remove(current);
        }
        return choices.get(random.nextInt(choices.size()));
    }

    /** 2026-11-04：這個機制要打掉這一階段血量的幾成（元素弱點、破綻；其他 0）。 */
    public static double shareOf(final Spec spec) {
        if (spec == null) {
            return 0;
        }
        return switch (spec.kind()) {
            case ELEMENT -> spec.element().share();
            case OPENING -> spec.opening().share();
            default -> 0;
        };
    }

    /** 要打掉的血（最大生命的比例）：這個階段（門檻到下一個門檻，最後一個階段到 0）的 share 成。 */
    public static double requiredFraction(final List<MapConfig.Phase> phases, final int index, final double share) {
        if (index < 0 || index >= phases.size()) {
            return 0;
        }
        final double next = index + 1 < phases.size() ? phases.get(index + 1).at() : 0;
        return Math.max(0.01, (phases.get(index).at() - next) * share);
    }

    /** 進度（0～1）：從開始到現在掉了多少血 ÷ 要打掉的。 */
    static double elementProgress(final double startFraction, final double nowFraction, final double required) {
        if (required <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, (startFraction - nowFraction) / required));
    }

    /** 元素的顯示（顏色、符號、名字）。 */
    static String elementLabel(final DamageType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case FIRE -> "<#ff7a3d>🔥 火焰";
            case COLD -> "<#7fd6ff>❄ 冰冷";
            case LIGHTNING -> "<#ffe45c>⚡ 閃電";
            default -> type.displayName();
        };
    }

    /** 抗性表（火、冰、電）。 */
    static Map<DamageType, Double> resists(final double fire, final double cold, final double lightning) {
        final Map<DamageType, Double> map = new EnumMap<>(DamageType.class);
        map.put(DamageType.FIRE, fire);
        map.put(DamageType.COLD, cold);
        map.put(DamageType.LIGHTNING, lightning);
        return map;
    }

    // ---- 跑起來的狀態（MapBosses 用） ----

    /** 一個正在進行的鎖血機制（結晶另外存在 MapInstance 的 shield）。 */
    static final class Active {
        final Spec spec;
        final long startTick;
        final long untilTick;
        /** 站位圈的圓心、倒數大招的安全區（世界座標 x、z）。 */
        final List<double[]> spots = new ArrayList<>();
        /** 站位圈的充能（0～1）。 */
        double[] charges = new double[0];
        /** 元素弱點：現在的元素、下一種、下次換的 tick、提示過了沒、開始時的血量比例、要打掉的比例、可以輪到的元素。 */
        DamageType element;
        DamageType upcoming;
        long swapTick;
        boolean warned;
        double startFraction;
        double required;
        List<DamageType> allowed = ELEMENTS;
        /** 倒數大招：上一次顯示的秒數；打到幾個人。 */
        long shownSecond = -1;
        int slamHits;
        /** 2026-11-04 破綻：每個人的破綻到哪一 tick、這個破綻已經剝了多少（絕對血量）；整個鎖剝了多少、要剝多少（絕對血量）、鎖住時的血。 */
        final Map<java.util.UUID, long[]> openUntil = new java.util.HashMap<>();
        final Map<java.util.UUID, Double> openUsed = new java.util.HashMap<>();
        double peeled;
        double requiredHealth;
        double lockedHealth;
        int layersDone;
        /** 上一次更新、上一次粒子與聲音、上一次提示列的 tick（{@link PhaseLocks} 照實際過了多久算）。 */
        long lastTick;
        long lastPulse = Long.MIN_VALUE / 4;
        long lastNotice = Long.MIN_VALUE / 4;

        Active(final Spec spec, final long startTick) {
            this.spec = spec;
            this.startTick = startTick;
            this.untilTick = startTick + Math.round(spec.seconds() * 20);
        }

        /** 這是第幾個鎖血機制的鍵（Fx 的持續範圍用）。 */
        Object key(final int index) {
            return new tw.exilecore.skill.Fx.Part(this, index);
        }

        /** 2026-11-03：第幾個圈的地面圖代號（不用 Boss 的 UUID 開頭：Boss 停手清場時不會被一起收掉）。 */
        String markId(final int index) {
            return "phaselock:" + System.identityHashCode(this) + ":" + index;
        }
    }
}
