package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * data/boss_skills.yml：Boss 招式庫、各種生物預設的招式組、通用設定。純資料，不依賴 Bukkit。
 * 檔案不存在時是空的（Boss 只會普攻，跟以前一樣）。
 */
public final class BossSkillConfig {

    public static final String FILE = "boss_skills.yml";
    /** Boss 專用的外觀樣式（SkillVisuals）。 */
    public static final Set<String> VISUALS = Set.of("boss_physical", "boss_fire", "boss_cold", "boss_lightning", "boss_chaos");
    /** defaults 裡「其他生物」的鍵。 */
    public static final String DEFAULT_KEY = "default";

    /**
     * @param gap            一般怪物（詞綴、種類給的招式）兩招之間至少隔幾秒
     * @param firstDelay     一般怪物出現後幾秒才開始用招
     * @param warnRange      幾格內的玩家會收到預警
     * @param gapCloser      追擊用的招式代號（null = 不追）
     * @param chaseDistance  目標在幾格外算打不到
     * @param chaseSeconds   連續幾秒打不到才追
     * @param phasePause     Boss 換階段後幾秒不出新招（喊話、召喚、護盾先出來）
     * @param phaseUnlock    換階段後幾秒，這個階段才會用的招才開始用（不會一換階段就接大招）
     * @param bossGap        Boss 兩招之間至少隔幾秒（2026-10 起跟一般怪物分開：1.5 → 1.0）
     * @param bossFirstDelay Boss 出現、重置、一陣子沒人之後重新看到人，幾秒才開始用招（2.0 → 1.5）
     * @param bossCooldown   Boss 招式冷卻的倍率（1 → 0.8；場地危害照 maps.yml 的 every，不吃這個）
     */
    public record Settings(double gap, double firstDelay, double warnRange, String gapCloser, double chaseDistance, double chaseSeconds,
                           double phasePause, double phaseUnlock, double bossGap, double bossFirstDelay, double bossCooldown,
                           double bossMinTelegraph) {
        public static final Settings DEFAULT = new Settings(1.5, 2.0, 24, null, 9, 2.0, 1.5, 3.0, 1.0, 1.5, 0.8, 1.0);

        /** 這一招的前搖（tick）：Boss 至少 boss_min_telegraph 秒（2026-10：不要有幾乎瞬發、來不及反應的招）。 */
        public long telegraphTicks(final BossSkill skill, final boolean boss) {
            return Math.round(Math.max(skill.telegraph(), boss ? bossMinTelegraph : 0) * 20);
        }

        /** 兩招之間的空檔：Boss 用 boss_gap，一般怪物用 gap。 */
        public double gapFor(final boolean boss) {
            return boss ? bossGap : gap;
        }

        /** 出現後（Boss 還有重新看到人後）幾秒才開始用招。 */
        public double firstDelayFor(final boolean boss) {
            return boss ? bossFirstDelay : firstDelay;
        }

        /** 招式冷卻的倍率：Boss 用 boss_cooldown，一般怪物照招式寫的。 */
        public double cooldownFor(final boolean boss) {
            return boss ? bossCooldown : 1.0;
        }
    }

    /**
     * 招式組裡解析好的一項。
     *
     * @param with 放這一招的同時一起落下的招（2026-10；只留 slam、zone、orbs、mark，其他的略過）
     */
    public record Entry(BossSkill skill, int fromPhase, List<BossSkill> with) {
        public Entry {
            with = with == null ? List.of() : List.copyOf(with);
        }

        public Entry(final BossSkill skill, final int fromPhase) {
            this(skill, fromPhase, List.of());
        }
    }

    /**
     * 從第 from 階段換到第 to 階段時，這個階段才會用的招（招式組裡 phase 寫 from+1 到 to 的）：
     * 換階段之後晚一點（{@link Settings#phaseUnlock}）才開始用。
     */
    public static List<String> unlockedBetween(final List<Entry> kit, final int from, final int to) {
        final List<String> unlocked = new ArrayList<>();
        for (final Entry entry : kit) {
            if (entry.fromPhase() > from && entry.fromPhase() <= to && !unlocked.contains(entry.skill().id())) {
                unlocked.add(entry.skill().id());
            }
        }
        return unlocked;
    }

    private Settings settings = Settings.DEFAULT;
    private Map<String, BossSkill> skills = Map.of();
    private Map<String, List<BossSkillUse>> defaults = Map.of();
    /** 哪幾種 Boss 出招的節奏倍率（2026-10）：種類 → 倍率（見 {@link #TEMPO_KINDS}）。 */
    private Map<String, Double> tempo = Map.of();

    /** 可以設節奏的 Boss 種類：終局、極境、昇華試煉、金庫之主、世界 Boss。 */
    public static final Set<String> TEMPO_KINDS = Set.of("final", "uber", "pinnacle", "trial", "vault", "world");

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            settings = Settings.DEFAULT;
            skills = Map.of();
            defaults = Map.of();
            return;
        }
        load(YamlNode.load(file));
    }

    /** 核心邏輯（單元測試直接餵 YAML）。 */
    public void load(final YamlNode root) {
        final Set<String> visuals = new HashSet<>(SkillConfig.VISUALS);
        visuals.addAll(VISUALS);
        final Map<String, BossSkill> parsedSkills = new LinkedHashMap<>();
        final YamlNode section = root.node("skills");
        for (final String id : section.keys()) {
            final BossSkill skill = BossSkill.parse(id, section.node(id));
            if (!visuals.contains(skill.visual())) {
                throw new IllegalArgumentException(section.node(id).path() + ".visual：不認得「" + skill.visual() + "」，可用："
                        + String.join("、", visuals.stream().sorted().toList()));
            }
            parsedSkills.put(id, skill);
        }
        final YamlNode settingsNode = root.node("settings");
        // 沒寫 gap_closer 時用 pounce（檔案裡有這招才算；寫了但不存在就是錯）
        String closer = settingsNode.string("gap_closer", "pounce").trim();
        if (!settingsNode.has("gap_closer") && !parsedSkills.containsKey(closer)) {
            closer = "";
        }
        final Settings parsedSettings = new Settings(
                range(settingsNode, "gap", settingsNode.decimal("gap", Settings.DEFAULT.gap()), 0, 30),
                range(settingsNode, "first_delay", settingsNode.decimal("first_delay", Settings.DEFAULT.firstDelay()), 0, 60),
                range(settingsNode, "warn_range", settingsNode.decimal("warn_range", 24), 4, 128),
                closer.isEmpty() || closer.equalsIgnoreCase("none") ? null : closer,
                range(settingsNode, "chase_distance", settingsNode.decimal("chase_distance", Settings.DEFAULT.chaseDistance()), 2, 64),
                range(settingsNode, "chase_seconds", settingsNode.decimal("chase_seconds", Settings.DEFAULT.chaseSeconds()), 0.5, 60),
                range(settingsNode, "phase_pause", settingsNode.decimal("phase_pause", Settings.DEFAULT.phasePause()), 0, 10),
                range(settingsNode, "phase_unlock", settingsNode.decimal("phase_unlock", Settings.DEFAULT.phaseUnlock()), 0, 30),
                range(settingsNode, "boss_gap", settingsNode.decimal("boss_gap", Settings.DEFAULT.bossGap()), 0, 30),
                range(settingsNode, "boss_first_delay", settingsNode.decimal("boss_first_delay", Settings.DEFAULT.bossFirstDelay()), 0, 60),
                range(settingsNode, "boss_cooldown", settingsNode.decimal("boss_cooldown", Settings.DEFAULT.bossCooldown()), 0.25, 4),
                range(settingsNode, "boss_min_telegraph", settingsNode.decimal("boss_min_telegraph", Settings.DEFAULT.bossMinTelegraph()), 0, 5));
        if (parsedSettings.gapCloser() != null) {
            final BossSkill skill = parsedSkills.get(parsedSettings.gapCloser());
            if (skill == null) {
                throw new IllegalArgumentException(FILE + ".settings.gap_closer：沒有這個招式「" + parsedSettings.gapCloser() + "」");
            }
            if (skill.kind() != BossSkill.Kind.BLINK && skill.kind() != BossSkill.Kind.CHARGE) {
                throw new IllegalArgumentException(FILE + ".settings.gap_closer：追擊的招式要是 blink 或 charge");
            }
        }
        final Map<String, List<BossSkillUse>> parsedDefaults = new LinkedHashMap<>();
        final YamlNode defaultsNode = root.node("defaults");
        for (final String key : defaultsNode.keys()) {
            final List<BossSkillUse> uses = BossSkillUse.parseList(defaultsNode, key);
            for (final BossSkillUse use : uses) {
                if (!parsedSkills.containsKey(use.id())) {
                    throw new IllegalArgumentException(defaultsNode.path() + "." + key + "：沒有這個招式「" + use.id() + "」");
                }
            }
            parsedDefaults.put(key.equals(DEFAULT_KEY) ? DEFAULT_KEY : key.trim().toUpperCase(Locale.ROOT), uses);
        }
        // 2026-10：這幾種 Boss 出招再緊一點（有翻滾之後）：兩招之間的空檔與招式冷卻乘這個倍率，前搖不動
        final Map<String, Double> parsedTempo = new LinkedHashMap<>();
        final YamlNode tempoNode = settingsNode.node("tempo");
        for (final String kind : tempoNode.keys()) {
            if (!TEMPO_KINDS.contains(kind)) {
                throw new IllegalArgumentException(tempoNode.path() + "：不認得「" + kind + "」，可用：" + String.join("、", TEMPO_KINDS.stream().sorted().toList()));
            }
            parsedTempo.put(kind, range(tempoNode, kind, tempoNode.decimal(kind, 1.0), 0.3, 2));
        }
        this.skills = parsedSkills;
        this.settings = parsedSettings;
        this.defaults = parsedDefaults;
        this.tempo = Map.copyOf(parsedTempo);
    }

    /** 這種 Boss 出招的節奏倍率（乘在兩招之間的空檔與招式冷卻上；1 = 不變，越小越快；沒寫、不是這幾種就是 1）。 */
    public double tempo(final String kind) {
        return kind == null ? 1.0 : tempo.getOrDefault(kind, 1.0);
    }

    private static double range(final YamlNode node, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要在 " + min + " 到 " + max + " 之間");
        }
        return value;
    }

    public Settings settings() {
        return settings;
    }

    public BossSkill skill(final String id) {
        return skills.get(id);
    }

    public Map<String, BossSkill> skills() {
        return skills;
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    /** 這種生物預設的招式組（沒有就是 default 那組；都沒有就是空的）。 */
    public List<BossSkillUse> defaultsFor(final String entityType) {
        final List<BossSkillUse> typed = entityType == null ? null : defaults.get(entityType.trim().toUpperCase(Locale.ROOT));
        if (typed != null) {
            return typed;
        }
        return defaults.getOrDefault(DEFAULT_KEY, List.of());
    }

    /**
     * 一隻 Boss 的招式組：有寫 skills 就用寫的，不然用這種生物的預設。不認得的代號略過（用 {@link #unknown} 事先警告）。
     */
    public List<Entry> kitFor(final String entityType, final List<BossSkillUse> configured) {
        final List<BossSkillUse> uses = configured == null || configured.isEmpty() ? defaultsFor(entityType) : configured;
        final List<Entry> kit = new ArrayList<>();
        for (final BossSkillUse use : uses) {
            final BossSkill skill = skills.get(use.id());
            if (skill != null) {
                final List<BossSkill> with = new ArrayList<>();
                for (final String id : use.with()) {
                    final BossSkill extra = skills.get(id);
                    if (extra != null && extra.kind().hazardCapable()) {
                        with.add(extra);
                    }
                }
                kit.add(new Entry(use.resolve(skill), use.fromPhase(), with));
            }
        }
        return kit;
    }

    /** 追擊用的招式（沒設定就是 null）。 */
    public BossSkill gapCloser() {
        return settings.gapCloser() == null ? null : skills.get(settings.gapCloser());
    }

    /** 這些項目裡不存在的招式代號（給 maps.yml／zones.yml 載入時警告）。 */
    public List<String> unknown(final List<BossSkillUse> uses) {
        final List<String> missing = new ArrayList<>();
        for (final BossSkillUse use : uses) {
            if (!skills.containsKey(use.id()) && !missing.contains(use.id())) {
                missing.add(use.id());
            }
            for (final String id : use.with()) {
                if (!skills.containsKey(id) && !missing.contains(id)) {
                    missing.add(id);
                }
            }
        }
        return missing;
    }

    /** {@code with:} 寫了要 Boss 站著放的招（只能用 slam、zone、orbs、mark）：回傳說明，沒問題是空的。 */
    public List<String> comboProblems(final List<BossSkillUse> uses) {
        final List<String> problems = new ArrayList<>();
        for (final BossSkillUse use : uses) {
            for (final String id : use.with()) {
                final BossSkill extra = skills.get(id);
                if (extra != null && !extra.kind().hazardCapable()) {
                    problems.add(use.id() + " 的 with「" + id + "」是 " + extra.kind().name().toLowerCase(Locale.ROOT)
                            + "，同時放只能用 slam、zone、orbs、mark");
                }
            }
        }
        return problems;
    }
}
