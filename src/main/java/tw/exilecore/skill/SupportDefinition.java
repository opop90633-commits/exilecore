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

    /** 2026-10-22：技能放在自己第 1 孔裡的宿主寶石（英靈圖騰、星咒圖騰、暴擊引星）。 */
    public static boolean hostsSkill(final SkillDefinition skill) {
        return skill != null && (skill.tags().contains(Tags.META) || flagged(skill, StatKeys.FLAG_TOTEM_LINKED));
    }

    private static boolean flagged(final SkillDefinition skill, final String flag) {
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(flag) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-22：宿主寶石第 2～5 孔的輔助全部套在放進去的技能上；其中這幾種也套到宿主本身
     * （放圖騰、圖騰生命、叫英靈的速度）：要圖騰才能掛的、只加攻擊／施法速度或持續時間的。
     */
    public boolean worksOnHost() {
        if (requires.contains(Tags.TOTEM)) {
            return true;
        }
        if (modifiers.isEmpty()) {
            return false;
        }
        for (final Modifier modifier : modifiers) {
            if (!modifier.stat().equals(StatKeys.CAST_SPEED) && !modifier.stat().equals(StatKeys.ATTACK_SPEED)
                    && !modifier.stat().equals(StatKeys.SKILL_DURATION)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 2026-10-22：能不能掛在宿主寶石上給放進去的技能用：照那顆技能驗（{@link #refusal}），
     * 要圖騰才能掛的輔助（疾樁、樁怒…）因為技能是圖騰放的所以也可以；觸發輔助、圖騰施法／陷阱施法不行（已經是宿主在放）。
     * 可以回傳 null。
     */
    public String hostRefusal(final SkillDefinition host, final SkillDefinition inner) {
        if (triggers() || flags(StatKeys.FLAG_SPELL_TOTEM) || flags(StatKeys.FLAG_TRAP)) {
            return "觸發、圖騰施法、陷阱施法的輔助不能掛在" + host.name() + "上";
        }
        if (inner == null) {
            return "先把技能寶石放進第 1 孔";
        }
        // 2026-10-24：寫了「圖騰除外」（excludes: totem）的輔助不能掛在圖騰宿主上（裂虛回響：英靈每次只打一下，連打吃不到）
        if (excludes.contains(Tags.TOTEM) && host.tags().contains(Tags.TOTEM)) {
            return name + "不能掛在" + host.name() + "上（圖騰除外）";
        }
        if (requires.contains(Tags.TOTEM) && host.tags().contains(Tags.TOTEM)) {
            return null;
        }
        // 2026-10-25：能量輔助（要 meta／invocation 的）套在引星本身：照宿主驗，不管裡面放什麼法術
        if ((requires.contains("meta") || requires.contains("invocation")) && refusal(host) == null) {
            return null;
        }
        // 2026-10-22：只加速度、持續時間的輔助也套到宿主本身（worksOnHost）：放進去的技能吃不到（例如持久配熾星彈），
        // 宿主吃得到就可以掛——不然星咒圖騰放法術時，加圖騰持續時間的持久掛不上去
        if (worksOnHost() && refusal(host) == null) {
            return null;
        }
        final String why = refusal(inner);
        return why == null ? null : name + "不能輔助" + inner.name() + "（" + why + "）";
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
        final String charge = chargeRefusal(skill);
        if (charge != null) {
            return charge; // 2026-10-24：戰星輔助
        }
        // 2026-10：觸發輔助（暴擊、受擊、擊殺、異常、翻滾、召喚物死亡觸發）不能掛在增益型的技能上（連珠、霜鏃、舉盾…）：
        // 照 PoE2 這些不是能被觸發的法術；以前掛得上去，觸發時卻略過，又因為掛了觸發不能手動放，整顆技能變廢
        if (triggers() && skill.mechanic() == Mechanic.GUARD) {
            return "增益型的技能（連珠、霜鏃、舉盾…）不能被觸發";
        }
        // 2026-10：光環、詛咒、印記、護體、戰吼、純位移、召喚本身不會打到敵人：只有擊中才有用的輔助（引火、致命、各種穿透、趁 X…）
        // 以前掛得上去，一條都不生效還白付魔力倍率。捷的輔助會傳給擊殺效果（隱藏的法術），所以照算
        if (!skill.dealsDamage() && !skill.tags().contains(Tags.HERALD) && !worksWithoutHits(skill)) {
            return "不會造成傷害的技能不行";
        }
        // 2026-10：投射物的機制（多射幾發、穿透、分裂、連鎖、飛行速度…）只有真的射出投射物的技能才吃得到：
        // 落釘弩台是 impact 機制帶 projectile 標籤（定身用），遠程召喚物也只是畫一道彈道
        // 2026-10-24：從天上掉下來的彈（弩台這種延遲落下的投射物）吃投射物速度（落下更快）：只動速度的輔助（疾矢…）可以掛
        if (projectileMechanics() && skill.mechanic() != Mechanic.PROJECTILE && skill.mechanic() != Mechanic.CHAIN
                && !(onlyProjectileSpeed() && skill.mechanic() == Mechanic.IMPACT && SkillExecutor.fallsAsProjectile(skill))) {
            return "要真的射出投射物的技能";
        }
        return null;
    }

    /** 不用擊中敵人也有作用的屬性：消耗、等級、保留精魂、給予的增益、觸發、施法速度、灌注…（範圍、持續、冷卻另外看技能有沒有那個標籤；召喚物的看 minion 標籤）。 */
    private static final Set<String> UTILITY_STATS = Set.of(StatKeys.SKILL_ADDED_COOLDOWN, StatKeys.SKILL_COST, StatKeys.SKILL_LEVEL,
            StatKeys.SKILL_MINIONS, StatKeys.SKILL_TOTEMS, StatKeys.SUPPORT_SPIRIT, StatKeys.CURSE_EFFECT, StatKeys.MARK_EFFECT, StatKeys.CAST_SPEED, StatKeys.FLAG_ECHO,
            StatKeys.FLAG_BLOOD_MAGIC, StatKeys.MANA_ON_MINION_DEATH, StatKeys.MINION_DEATH_HEAL);
    private static final List<String> UTILITY_PREFIXES = List.of(StatKeys.GRANT_PREFIX, "warcry.", "aura.", "herald.", "trigger.", "seal.", "meta.",
            "infusion.", "proc.use", "proc.minion_death", "charge.sup.");
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

    /** 2026-10-24：動到的投射物機制只有速度（沒有數量、穿透、分裂、連鎖…）。 */
    boolean onlyProjectileSpeed() {
        boolean speed = false;
        for (final Modifier modifier : modifiers) {
            switch (modifier.stat()) {
                case StatKeys.SKILL_PROJECTILE_SPEED -> speed = true;
                case StatKeys.SKILL_PROJECTILES, StatKeys.SKILL_PIERCE, StatKeys.SKILL_FORK, StatKeys.SKILL_CHAINS,
                     StatKeys.FLAG_PROJECTILE_NOVA, StatKeys.FLAG_RETURNING, StatKeys.SEAL_PROJECTILES -> {
                    return false;
                }
                default -> { }
            }
        }
        return speed;
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

    /**
     * 2026-10-24：本身就會消耗某種戰星的技能（照 PoE2：消耗那種戰星的輔助不能掛在本來就消耗它的技能上）。
     * 冷卻中用戰星跳過的另外看 {@link SkillDefinition#cooldownBypass()}。
     */
    static final Map<String, Set<String>> CHARGE_CONSUMERS = Map.of(
            "power", Set.of("flicker_strike", "raise_zombie", "totem_casting"),
            "frenzy", Set.of("lightning_spear", "storm_javelin", "linked_volley"),
            "endurance", Set.of("totem_casting"));
    /** 2026-10-24：一定要消耗戰星才放得出來的技能（抑星不能掛）。 */
    static final Set<String> REQUIRES_CHARGES = Set.of("totem_casting");
    /** 2026-10-24：有完美時機的技能（完磐只能掛這些）。 */
    static final Set<String> PERFECT_TIMING = Set.of("true_strike", "snipe");

    static boolean consumes(final SkillDefinition skill, final String chargeId) {
        return chargeId.equals(skill.cooldownBypass()) || CHARGE_CONSUMERS.getOrDefault(chargeId, Set.of()).contains(skill.id());
    }

    private boolean has(final String stat) {
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().equals(stat) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 2026-10-24：恆星、倍星這種「技能消耗戰星時才有用」的輔助。 */
    public boolean needsChargeSpend() {
        return has(tw.exilecore.charge.ChargeService.SUP_KEEP) || has(tw.exilecore.charge.ChargeService.SUP_DOUBLE);
    }

    /** 2026-10-24：掛上去會讓技能消耗戰星的輔助（狂速、綿咒、碎地、蔓寒）。 */
    public boolean spendsCharges() {
        return has(tw.exilecore.charge.ChargeService.SUP_FEROCITY) || has(tw.exilecore.charge.ChargeService.SUP_ABIDING_HEX)
                || has(tw.exilecore.charge.ChargeService.SUP_JAGGED) || has(tw.exilecore.charge.ChargeService.SUP_CREEPING);
    }

    /** 2026-10-24：這個技能本身會消耗戰星（不看掛的輔助）。 */
    public static boolean consumesAny(final SkillDefinition skill) {
        return consumes(skill, "power") || consumes(skill, "frenzy") || consumes(skill, "endurance");
    }

    /** 2026-10-24：戰星輔助的限制（照 PoE2）；可以掛回傳 null。 */
    private String chargeRefusal(final SkillDefinition skill) {
        if (has(tw.exilecore.charge.ChargeService.SUP_INHIBIT) && REQUIRES_CHARGES.contains(skill.id())) {
            return "一定要消耗戰星才放得出來的技能不行";
        }
        if (has(tw.exilecore.charge.ChargeService.SUP_FEROCITY) && consumes(skill, "frenzy")) {
            return "本來就會消耗怒星的技能不行";
        }
        if ((has(tw.exilecore.charge.ChargeService.SUP_ABIDING_HEX) || has(tw.exilecore.charge.ChargeService.SUP_CREEPING)) && consumes(skill, "power")) {
            return "本來就會消耗銳星的技能不行";
        }
        if (has(tw.exilecore.charge.ChargeService.SUP_JAGGED) && consumes(skill, "endurance")) {
            return "本來就會消耗磐星的技能不行";
        }
        if (has(tw.exilecore.charge.ChargeService.SUP_OFFERING_POWER) && (skill.warcry() == null || !skill.warcry().spike())) {
            return "要祭柱技能（嗜血祭、骸骨祭、魂火祭）";
        }
        if (has(tw.exilecore.charge.ChargeService.SUP_PERFECT_ENDURANCE) && !PERFECT_TIMING.contains(skill.id())) {
            return "要有完美時機的技能（蓄勁一擊、穿雲箭）";
        }
        return null;
    }
}
