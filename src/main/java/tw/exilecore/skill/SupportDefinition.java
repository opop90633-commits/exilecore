package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一個輔助寶石的定義（data/supports.yml）。純資料，不依賴 Bukkit。
 * <p>
 * 輔助寶石掛在技能上（PoE2：不插裝備），把自己的修飾器疊到那個技能上；
 * 同一種輔助可以掛在不同技能上（各用一顆）；supports.yml 的 once_per_character 打開時照 PoE2，一個角色只能用一次。
 *
 * @param tier           階級：未切割輔助寶石的階級要 ≥ 這個才能切出它
 * @param requires       技能要有其中一個標籤才能掛（空的 = 什麼技能都能掛）
 * @param excludes       技能有其中一個標籤就不能掛
 * @param manaMultiplier 魔力消耗倍率（1.3 = 130%）
 * @param modifiers      疊到技能上的修飾器（已經換算成引擎的單位）
 * @param notes          額外的說明行（例如之後才生效的效果）
 * @param source         專屬的掉落來源（終局代號、uber…）：不能從未切割輔助寶石切出來，只有那裡會掉；null = 一般的
 */
public record SupportDefinition(String id, String name, String description, String icon, int tier,
                                Set<String> requires, Set<String> excludes, double manaMultiplier,
                                List<Modifier> modifiers, List<String> notes, String source) {

    public static final int MAX_TIER = 3;

    public SupportDefinition {
        requires = Collections.unmodifiableSet(new LinkedHashSet<>(requires));
        excludes = Collections.unmodifiableSet(new LinkedHashSet<>(excludes));
        modifiers = List.copyOf(modifiers);
        notes = List.copyOf(notes);
        source = source == null || source.isBlank() ? null : source.trim();
    }

    /** 一般的輔助寶石（能從未切割輔助寶石切出來）。 */
    public SupportDefinition(final String id, final String name, final String description, final String icon, final int tier,
                             final Set<String> requires, final Set<String> excludes, final double manaMultiplier,
                             final List<Modifier> modifiers, final List<String> notes) {
        this(id, name, description, icon, tier, requires, excludes, manaMultiplier, modifiers, notes, null);
    }

    /** 能從未切割輔助寶石切出來（專屬掉落的不行）。 */
    public boolean cuttable() {
        return source == null;
    }

    /**
     * 一個技能只能掛一顆專屬掉落的輔助寶石（2026-10）：把 adding 掛到 entry 的第 socket 孔（-1 = 第一個空孔）之後，
     * 會不會有兩顆專屬的。換掉的那一孔不算。可以掛回傳 null，不行回傳原因。
     *
     * @param lookup 輔助的代號 → 定義（找不到回傳 null）
     */
    public static String exclusiveConflict(final tw.exilecore.character.SkillLoadout.Entry entry, final int socket,
                                           final SupportDefinition adding, final java.util.function.Function<String, SupportDefinition> lookup) {
        return exclusiveConflict(entry, socket, adding, false, lookup);
    }

    /**
     * 2026-10-18：共鳴版。adding 本身共鳴（用過共鳴石）就不算；其他孔已掛的專屬輔助若共鳴（{@link tw.exilecore.character.SkillLoadout.Entry#resonant(int)}）也不算。
     *
     * @param addingResonant 要掛的這顆有沒有共鳴
     */
    public static String exclusiveConflict(final tw.exilecore.character.SkillLoadout.Entry entry, final int socket,
                                           final SupportDefinition adding, final boolean addingResonant,
                                           final java.util.function.Function<String, SupportDefinition> lookup) {
        if (adding == null || adding.cuttable() || addingResonant) {
            return null;
        }
        for (int i = 0; i < entry.sockets(); i++) {
            if (i == socket || entry.resonant(i)) {
                continue;
            }
            final String id = entry.support(i);
            if (id.isEmpty() || id.equals(adding.id())) {
                continue; // 同一種掛兩顆是另一條規則
            }
            final SupportDefinition other = lookup.apply(id);
            if (other != null && !other.cuttable()) {
                return "一個技能只能掛一顆專屬掉落的輔助寶石（已經掛著" + other.name() + "）";
            }
        }
        return null;
    }

    /** 這顆是不是從這個來源掉的專屬寶石。 */
    public boolean droppedBy(final String from) {
        return source != null && source.equals(from);
    }

    /**
     * 2026-10-22：掛在星咒圖騰（主要寶石）上有沒有作用：要圖騰才能掛的（帶到圖騰施法上），
     * 或只加施法速度（放圖騰更快）、持續時間（圖騰撐更久）的。
     */
    static boolean spellTotemMeta(final SkillDefinition skill) {
        return skill.mechanic() == Mechanic.META && skill.tags().contains(Tags.TOTEM);
    }

    public boolean worksOnSpellTotem() {
        if (requires.contains(Tags.TOTEM)) {
            return true;
        }
        if (modifiers.isEmpty()) {
            return false;
        }
        for (final Modifier modifier : modifiers) {
            if (!modifier.stat().equals(StatKeys.CAST_SPEED) && !modifier.stat().equals(StatKeys.SKILL_DURATION)) {
                return false;
            }
        }
        return true;
    }

    /** 能不能掛在這個技能上（看技能自己的標籤）。普攻（隱藏的攻擊）不能掛；捷的擊殺效果（隱藏的法術）照標籤算。 */
    public boolean supports(final SkillDefinition skill) {
        return refusal(skill) == null;
    }

    /**
     * 不能掛在這個技能上的原因（接在「X 不能輔助 Y」後面的括號裡）：「召喚物技能不行」「要攻擊技能或法術技能」；可以掛回傳 null。
     * 以前一律寫「要 requires」，被 excludes 擋下來的也這樣寫——聚銳不能輔助召喚骷髏（要攻擊技能或法術技能），召喚骷髏明明是法術。
     */
    public String refusal(final SkillDefinition skill) {
        if (skill.hidden() && skill.isAttack()) {
            return "普攻不能掛輔助寶石";
        }
        // 2026-10-22：星咒圖騰（主要寶石）本身只負責放圖騰：只收會帶到圖騰施法的（要圖騰才能掛的）、加施法速度的（放得快）、
        // 加持續時間的（圖騰撐更久）。以前持久、久地、灌元這些都掛得上去，卻什麼都沒做
        if (spellTotemMeta(skill) && !worksOnSpellTotem()) {
            return "星咒圖騰上只能掛要圖騰的、加施法速度或持續時間的輔助，其他的請掛在插槽法術上";
        }
        for (final String tag : excludes) {
            if (skill.tags().contains(tag)) {
                return ModifierText.tagName(tag) + "技能不行";
            }
        }
        // 2026-10-17 標籤整理：以前 excludes 寫 channel，但沒有技能有這個標籤（蓄力看 channel.stages），等於沒擋
        if (excludes.contains("channel") && channels(skill)) {
            return "本身會蓄力的技能不行";
        }
        // 掃擊：只有原本只打一個的打擊（strike 機制）會多打；突刺、爪擊這種衝過去打的、本來就打好幾個的沒作用只剩代價
        if (flags(StatKeys.STRIKE_TARGETS) && (skill.mechanic() != Mechanic.STRIKE || skill.params().targets() != 1)) {
            return "要原本只打一個目標的打擊技能";
        }
        // 碎界擴散這類近戰擴散：只有打擊（strike 機制）會擴散
        if (flags(StatKeys.MELEE_SPLASH) && skill.mechanic() != Mechanic.STRIKE) {
            return "要打擊技能（衝過去、扇形的不行）";
        }
        // 強勢戰吼、怒嘯：要戰吼本身會強化攻擊，加次數才有意義
        if ((flags(StatKeys.WARCRY_EXERTS) || flags(StatKeys.WARCRY_RAGE_EXERT))
                && (skill.warcry() == null || skill.warcry().exerts() <= 0)) {
            return "要會強化攻擊的戰吼";
        }
        if (flags(StatKeys.CHANNEL_STAGES) && channels(skill)) {
            return "本身會蓄力的技能不行"; // 2026-10：蓄勢不能疊在本來就會蓄力的技能上（熾息、蓄力射擊…）
        }
        if ((flags(tw.exilecore.stats.StatKeys.FLAG_SPELL_TOTEM) || flags(tw.exilecore.stats.StatKeys.FLAG_TRAP)) && !SkillBuild.deployable(skill)) {
            return "這種技能不能交給圖騰或陷阱放"; // 2026-10：掘墳、號召這些只有 spell 標籤，以前掛得上去卻沒效果
        }
        if (!matchesRequires(skill)) {
            return "要" + requiresText();
        }
        // 2026-10：觸發輔助（暴擊、受擊、擊殺、異常、翻滾、召喚物死亡觸發）不能掛在增益型的技能上（連珠、霜鏃、舉盾…）：
        // 照 PoE2 這些不是能被觸發的法術；以前掛得上去，觸發時卻略過，又因為掛了觸發不能手動放，整顆技能變廢
        if (triggers() && skill.mechanic() == Mechanic.GUARD) {
            return "增益型的技能（連珠、霜鏃、舉盾…）不能被觸發";
        }
        // 2026-10：光環、詛咒、印記、護體、戰吼、純位移、召喚本身不會打到敵人：只有擊中才有用的輔助（引火、致命、各種穿透、趁 X…）
        // 以前掛得上去，一條都不生效還白付魔力倍率。捷的輔助會傳給擊殺效果（隱藏的法術），所以照算
        // 2026-10-22：星咒圖騰本身不打人，但要圖騰才能掛的輔助（樁怒、堅樁、厚樁、怨燼）會帶到圖騰施法上：以前被這條擋掉
        final boolean carriedToTotem = spellTotemMeta(skill) && requires.contains(Tags.TOTEM);
        if (!skill.dealsDamage() && !skill.tags().contains(Tags.HERALD) && !carriedToTotem && !worksWithoutHits(skill)) {
            return "不會造成傷害的技能不行";
        }
        // 2026-10：投射物的機制（多射幾發、穿透、分裂、連鎖、飛行速度…）只有真的射出投射物的技能才吃得到：
        // 落釘弩台是 impact 機制帶 projectile 標籤（定身用），遠程召喚物也只是畫一道彈道
        if (projectileMechanics() && skill.mechanic() != Mechanic.PROJECTILE && skill.mechanic() != Mechanic.CHAIN) {
            return "要真的射出投射物的技能";
        }
        return null;
    }

    /** 不用擊中敵人也有作用的屬性：消耗、等級、保留精魂、給予的增益、觸發、施法速度、灌注…（範圍、持續、冷卻另外看技能有沒有那個標籤；召喚物的看 minion 標籤）。 */
    private static final Set<String> UTILITY_STATS = Set.of(StatKeys.SKILL_ADDED_COOLDOWN, StatKeys.SKILL_COST, StatKeys.SKILL_LEVEL,
            StatKeys.SKILL_MINIONS, StatKeys.SKILL_TOTEMS, StatKeys.SUPPORT_SPIRIT, StatKeys.CURSE_EFFECT, StatKeys.CAST_SPEED, StatKeys.FLAG_ECHO,
            StatKeys.FLAG_BLOOD_MAGIC, StatKeys.MANA_ON_MINION_DEATH, StatKeys.MINION_DEATH_HEAL);
    private static final List<String> UTILITY_PREFIXES = List.of(StatKeys.GRANT_PREFIX, "warcry.", "aura.", "herald.", "trigger.", "seal.",
            "infusion.", "proc.use", "proc.minion_death");
    /** 技能有這個標籤時才有作用的屬性。 */
    private static final Map<String, String> TAGGED_UTILITY_STATS = Map.of(StatKeys.SKILL_AREA, Tags.AREA, StatKeys.SKILL_DURATION, Tags.DURATION,
            StatKeys.SKILL_COOLDOWN, Tags.COOLDOWN);

    /** 這顆輔助有沒有任何一條不用擊中敵人也對這個技能有作用的修飾器（沒有 = 純傷害的輔助，不會造成傷害的技能掛了沒效果）。 */
    public boolean worksWithoutHits(final SkillDefinition skill) {
        // 2026-10：「傷害變高，代價是範圍變小／冷卻變久／持續變短／施法變慢」的輔助（凝聚、爆發、劇痛、沙漏…）：
        // 掛在不會造成傷害的技能上，好處吃不到、只剩代價（以前因為「有動到範圍」就能掛在光環上）——代價那幾條不算有作用
        final boolean tradeoff = damageUpside();
        for (final Modifier modifier : modifiers) {
            if (tradeoff && penalty(modifier)) {
                continue;
            }
            if ((modifier.tags().contains(Tags.MINION) && skill.tags().contains(Tags.MINION)) || UTILITY_STATS.contains(modifier.stat())) {
                return true;
            }
            final String tag = TAGGED_UTILITY_STATS.get(modifier.stat());
            if (tag != null && skill.tags().contains(tag)) {
                return true;
            }
            for (final String prefix : UTILITY_PREFIXES) {
                if (modifier.stat().startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 這條修飾器要擊中敵人才有作用（不是消耗、範圍、持續、冷卻、給予的增益這些）。 */
    private static boolean hitOnly(final Modifier modifier) {
        if (modifier.tags().contains(Tags.MINION) || UTILITY_STATS.contains(modifier.stat()) || TAGGED_UTILITY_STATS.containsKey(modifier.stat())) {
            return false;
        }
        for (final String prefix : UTILITY_PREFIXES) {
            if (modifier.stat().startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** 這顆輔助的好處是「打得更痛」（有一條要擊中才有作用、數值是正的修飾器：更多傷害、暴擊、異常機率…）。 */
    private boolean damageUpside() {
        for (final Modifier modifier : modifiers) {
            if (modifier.value() > 0 && hitOnly(modifier)) {
                return true;
            }
        }
        return false;
    }

    /** 這條是代價：範圍變小、持續變短、冷卻變久（含多加幾秒冷卻）、施法變慢。 */
    private static boolean penalty(final Modifier modifier) {
        return switch (modifier.stat()) {
            case StatKeys.SKILL_AREA, StatKeys.SKILL_DURATION, StatKeys.CAST_SPEED -> modifier.value() < 0;
            case StatKeys.SKILL_COOLDOWN, StatKeys.SKILL_ADDED_COOLDOWN -> modifier.value() > 0;
            default -> false;
        };
    }

    /** 每一條修飾器都要擊中敵人才有作用（說明用）。 */
    public boolean hitsOnly() {
        for (final Modifier modifier : modifiers) {
            if (modifier.tags().contains(Tags.MINION) || UTILITY_STATS.contains(modifier.stat()) || TAGGED_UTILITY_STATS.containsKey(modifier.stat())) {
                return false;
            }
            for (final String prefix : UTILITY_PREFIXES) {
                if (modifier.stat().startsWith(prefix)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 這顆輔助有沒有動到投射物本身的機制（數量、穿透、分裂、連鎖、速度、環射、回返、封矢）。 */
    public boolean projectileMechanics() {
        for (final Modifier modifier : modifiers) {
            switch (modifier.stat()) {
                case StatKeys.SKILL_PROJECTILES, StatKeys.SKILL_PIERCE, StatKeys.SKILL_FORK, StatKeys.SKILL_CHAINS,
                     StatKeys.SKILL_PROJECTILE_SPEED, StatKeys.FLAG_PROJECTILE_NOVA, StatKeys.FLAG_RETURNING, StatKeys.SEAL_PROJECTILES -> {
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    /** 技能本身會不會蓄力（技能的修飾器有 channel.stages）。 */
    static boolean channels(final SkillDefinition skill) {
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(StatKeys.CHANNEL_STAGES) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRequires(final SkillDefinition skill) {
        if (requires.isEmpty()) {
            return true;
        }
        for (final String tag : requires) {
            if (skill.tags().contains(tag) || skill.weapons().contains(tag)) { // 2026-10：也可以寫武器（crossbow：弩的技能）
                return true;
            }
        }
        return false;
    }

    /** 2026-10：這顆是不是觸發輔助（modifiers 有 trigger. 開頭、大於 0 的）。 */
    public boolean triggers() {
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().startsWith("trigger.") && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 2026-10：要保留精魂的輔助（泉魔、厲宣這類）：一個角色只能掛一顆。 */
    public boolean spiritSupport() {
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().equals(tw.exilecore.stats.StatKeys.SUPPORT_SPIRIT)) {
                return true;
            }
        }
        return false;
    }

    /** 這顆輔助有沒有某個開關（flag.spell_totem、flag.trap…）。 */
    public boolean flags(final String stat) {
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().equals(stat) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 給玩家看的說明：{@code {charge:代號}} 換成戰星的名字與每顆的效果（照 charges.yml）。 */
    public String descriptionText() {
        return tw.exilecore.charge.ChargeText.expand(description);
    }

    /** 要哪種技能（只看 requires：「攻擊技能或法術技能」）。完整的說明用 {@link #supportsText}。 */
    public String requiresText() {
        if (requires.isEmpty()) {
            return "任何技能";
        }
        final List<String> names = new ArrayList<>();
        for (final String tag : requires) {
            names.add(ModifierText.tagName(tag) + "技能");
        }
        return String.join("或", names);
    }

    /**
     * 可以掛在哪種技能上（寶石說明、切割選單的「可以輔助：」）：「攻擊技能或法術技能（召喚物、光環除外）」。
     * 2026-10：以前只寫 requires，聚銳寫「攻擊技能或法術技能」，召喚物、光環的法術卻掛不上去。
     *
     * @param skills 全部技能：排除的種類只列真的會碰到的（玩家學得到、本來符合 requires、又有那個標籤）；null = excludes 全部列
     */
    public String supportsText(final java.util.Collection<SkillDefinition> skills) {
        final List<String> names = new ArrayList<>();
        for (final String tag : relevantExcludes(skills)) {
            names.add(ModifierText.tagName(tag));
        }
        if (flags(StatKeys.CHANNEL_STAGES) || excludes.contains("channel")) {
            names.add("本身會蓄力的技能");
        }
        // 2026-10-17：跟 refusal 的新條件一致
        if (flags(StatKeys.STRIKE_TARGETS)) {
            names.add("本來就打好幾個目標、或衝過去打的");
        } else if (flags(StatKeys.MELEE_SPLASH)) {
            names.add("衝過去、扇形打的");
        }
        if (flags(StatKeys.WARCRY_EXERTS) || flags(StatKeys.WARCRY_RAGE_EXERT)) {
            names.add("不會強化攻擊的戰吼");
        }
        if (hitsOnly() && !Set.of(Tags.ATTACK, Tags.MELEE, Tags.PROJECTILE, Tags.STRIKE, Tags.SLAM, Tags.GRENADE).containsAll(requires)) {
            names.add("不會造成傷害的技能"); // 只要攻擊的（攻擊一定會打到敵人）就不用寫
        }
        return names.isEmpty() ? requiresText() : requiresText() + "（" + String.join("、", names) + "除外）";
    }

    /** 排除的標籤裡，真的有技能會碰到的（玩家學得到、本來符合 requires、又有這個標籤），照 excludes 的順序；skills 是 null 回傳全部。 */
    public List<String> relevantExcludes(final java.util.Collection<SkillDefinition> skills) {
        if (skills == null) {
            return List.copyOf(excludes);
        }
        final List<String> relevant = new ArrayList<>();
        for (final String tag : excludes) {
            for (final SkillDefinition skill : skills) {
                if (!skill.hidden() && skill.tags().contains(tag) && matchesRequires(skill)) {
                    relevant.add(tag);
                    break;
                }
            }
        }
        return relevant;
    }

    /** 魔力消耗倍率變成修飾器（skill.cost 的更多），跟其他輔助相乘。 */
    public List<Modifier> allModifiers() {
        if (Math.abs(manaMultiplier - 1.0) < 1e-9) {
            return modifiers;
        }
        final List<Modifier> list = new ArrayList<>(modifiers);
        list.add(new Modifier(StatKeys.SKILL_COST, ModType.MORE, manaMultiplier - 1.0, Set.of(), name));
        return list;
    }

    static SupportDefinition parse(final String id, final YamlNode node) {
        final String where = node.path();
        final int tier = node.integer("tier", 1);
        if (tier < 1 || tier > MAX_TIER) {
            throw new IllegalArgumentException(where + ".tier：要在 1 到 " + MAX_TIER + " 之間");
        }
        final double manaMultiplier = node.decimal("mana_multiplier", 1.0);
        if (manaMultiplier < 0.1 || manaMultiplier > 5) {
            throw new IllegalArgumentException(where + ".mana_multiplier：要在 0.1 到 5 之間");
        }
        final String name = node.requireString("name");
        final List<Modifier> modifiers = new ArrayList<>();
        for (final YamlNode child : node.list("modifiers")) {
            modifiers.add(AffixMod.parse(child).toModifier(Map.of(), name));
        }
        if (modifiers.isEmpty()) {
            throw new IllegalArgumentException(where + "：輔助寶石至少要有一條 modifiers");
        }
        return new SupportDefinition(id, name, node.string("description", ""), node.string("icon", "GHAST_TEAR"), tier,
                lower(node.strings("requires")), lower(node.strings("excludes")), manaMultiplier, modifiers,
                node.strings("notes"), node.string("source", null));
    }

    private static Set<String> lower(final List<String> list) {
        final Set<String> set = new LinkedHashSet<>();
        for (final String text : list) {
            final String trimmed = text.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }
}
