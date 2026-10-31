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

    /** 2026-10-27 照 PoE2：血脈輔助（supports_lineage.yml，source 是 lineage、lineage_rare、lineage_pinnacle）：切不出來，但不算專屬輔助的名額。 */
    public boolean lineage() {
        return source != null && source.startsWith(LineageRules.SOURCE_PREFIX);
    }

    /** 2026-10-27：共鳴石用在這顆上為什麼不行（可以回傳 null）：一般輔助、血脈輔助、已經共鳴的不行（技能面板拿著共鳴石時亮不亮照這個）。 */
    public String resonanceRefusal(final boolean alreadyResonant) {
        return lineage() ? "不能用在血脈輔助寶石上" : cuttable() ? "只能用在專屬掉落的輔助寶石上" : alreadyResonant ? "已經共鳴了" : null;
    }

    /** 2026-10-27：換掉掉落來源（supports_lineage.yml 的 existing：把 supports.yml 裡已有的血脈輔助標成血脈掉落）。 */
    public SupportDefinition withSource(final String newSource) {
        return new SupportDefinition(id, name, description, icon, tier, requires, excludes, manaMultiplier, modifiers, notes, newSource);
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
        if (adding != null && adding.lineage()) {
            return null; // 2026-10-28 照 PoE2（站長）：不同的血脈輔助可以掛在同一個技能上，不互相衝突；這次之前一個技能只能一顆
        }
        if (adding == null || adding.cuttable() || addingResonant) { // 2026-10-27：血脈輔助不算專屬的名額（照 PoE2）
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
            if (other != null && !other.cuttable() && !other.lineage()) {
                return "一個技能只能掛一顆專屬掉落的輔助寶石（已經掛著" + other.name() + "）";
            }
        }
        return null;
    }

    /**
     * 2026-10-27 照站長：一個技能只能掛一顆血脈輔助寶石（跟專屬輔助的名額分開算；血脈輔助不能用共鳴石，舊的共鳴標記也不算）。
     * 可以掛回傳 null，不行回傳原因。
     */
    private static String lineageConflict(final tw.exilecore.character.SkillLoadout.Entry entry, final int socket,
                                          final SupportDefinition adding, final java.util.function.Function<String, SupportDefinition> lookup) {
        for (int i = 0; i < entry.sockets(); i++) {
            if (i == socket) {
                continue;
            }
            final String id = entry.support(i);
            if (id.isEmpty() || id.equals(adding.id())) {
                continue; // 同一種掛兩顆是另一條規則
            }
            final SupportDefinition other = lookup.apply(id);
            if (other != null && other.lineage()) {
                return "一個技能只能掛一顆血脈輔助寶石（已經掛著" + other.name() + "）";
            }
        }
        return null;
    }

    /** 這顆是不是從這個來源掉的專屬寶石。 */
    public boolean droppedBy(final String from) {
        return source != null && source.equals(from);
    }

    /** 2026-10-22：技能放在自己孔裡的宿主寶石（英靈圖騰、星咒圖騰、引星；每一孔都能放）。 */
    public static boolean hostsSkill(final SkillDefinition skill) {
        return skill != null && (skill.tags().contains(Tags.META) || flagged(skill, StatKeys.FLAG_TOTEM_LINKED)
                || DeadeyeMetas.hosts(skill)); // 2026-10-28 照 PoE2：預告射擊、幻影銳眼也是主要寶石；這次之前孔裡不能放技能
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
     * 2026-10-22：宿主寶石其他孔的輔助全部套在放進去的技能上；其中這幾種也套到宿主本身
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
        // 2026-10-30 照 PoE2（SupportMirageDeadeye 讓孔裡的技能變成 UsedByProxy；迅鋒、衝勢、法術回響…的 excluded_types 有 UsedByProxy）：
        // 幻影代放的技能掛不上；這次之前掛得上，迅鋒的移動條件對幻影沒作用，只剩更多 40% 技能速度
        if (host != null && DeadeyeMetas.MIRAGE.equals(host.id()) && RefSupportRules.proxyExcluded(id)) {
            return name + "不能掛在" + host.name() + "上（幻影代放的技能不能用）";
        }
        // 2026-10-30 照 PoE2：觸發型主要寶石（暴擊引星…加 Triggered）擋 Triggered 的輔助（蓄詠…）、法術圖騰（加 UsedByProxy）擋 UsedByProxy 的；
        // 這次之前都掛得上，蓄詠插在暴擊引星裡照樣更多 35% 傷害
        final String metaWhy = host == null ? null : RefSupportRules.metaHostRefusal(id, host.id());
        if (metaWhy != null && !worksOnHost()) {
            return name + "不能掛在" + host.name() + "上（" + metaWhy + "）";
        }
        // 2026-10-28 照 PoE2（MirageDeadeyeSpawnPlayer 有 Duration、Cooldown）：迅返、長留這種只改冷卻／持續時間的，套在幻影銳眼自己身上
        // （幻影的冷卻、持續時間），孔裡放什麼都可以掛；這次之前掛不上去
        // 2026-10-28 照 PoE2：常駐增益（泉命、凝準…）與預告射擊的範圍輔助也套在它自己身上
        if (DeadeyeMetas.hosts(host) && DeadeyeMetas.appliesToHost(this, host) && refusal(host) == null) {
            return null;
        }
        // 2026-10-28 照 PoE2 Hollow Form：恆星、倍星套在空無型態自己身上（它用掉銳星多 2 個幻影），孔裡放什麼都可以掛
        if (HollowForm.hosts(host) && HollowForm.appliesToHost(this) && refusal(host) == null) {
            return null;
        }
        // 2026-10-29 照 PoE2 Hand of Chayula：夏烏拉之手自己是攻擊，能輔助這一掌的輔助孔裡放什麼都可以掛（套在這一掌上）
        if (ChayulaHand.hosts(host) && refusal(host) == null) {
            return null;
        }
        if (inner == null) {
            return "先把技能寶石放進它的孔";
        }
        // 2026-10-24：寫了「圖騰除外」（excludes: totem）的輔助不能掛在圖騰宿主上（裂虛回響：英靈每次只打一下，連打吃不到）
        if (excludes.contains(Tags.TOTEM) && host.tags().contains(Tags.TOTEM)) {
            return name + "不能掛在" + host.name() + "上（圖騰除外）";
        }
        if (requires.contains(Tags.TOTEM) && host.tags().contains(Tags.TOTEM)) {
            return null;
        }
        // 2026-10-28 照 PoE2 過剩：要「上限」的輔助掛在本身有上限的宿主（星咒圖騰：圖騰的上限）上，照宿主驗（SkillService.innerSupportsOf 帶過去）
        if (requires.contains(SkillLimits.TAG) && host.tags().contains(SkillLimits.TAG) && refusal(host) == null) {
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

    /** 同上，還有掛著 others 這些輔助（別的輔助解開的組合）。 */
    public boolean supports(final SkillDefinition skill, final java.util.Collection<String> others) {
        return refusal(skill, others) == null;
    }

    private boolean unlockedBy(final SkillDefinition skill, final java.util.Collection<String> others) {
        if (others == null || others.isEmpty()) {
            return false;
        }
        final SkillConfig config = SkillConfig.current();
        for (final String other : RefSupportRules.unlockers(id, skill.id())) {
            if (others.contains(other)) {
                final SupportDefinition unlocker = config == null ? null : config.support(other);
                // 那一顆自己要掛得上（它也可以被再別顆解開：終式 → 鐘星 → 滿星；拿掉自己避免繞圈）
                final java.util.List<String> rest = new java.util.ArrayList<>(others);
                rest.remove(id);
                rest.remove(other);
                if (unlocker != null && unlocker.refusal(skill, rest) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 能不能掛在這個技能上（看技能自己的標籤）。普攻（隱藏的攻擊）不能掛；捷的擊殺效果（隱藏的法術）照標籤算。 */
    public boolean supports(final SkillDefinition skill) {
        return refusal(skill) == null;
    }

    /**
     * 不能掛在這個技能上的原因（接在「X 不能輔助 Y」後面的括號裡）：「召喚物技能不行」「要攻擊技能或法術技能」；可以掛回傳 null。
     * 以前一律寫「要 requires」，被 excludes 擋下來的也這樣寫——聚銳不能輔助召喚骷髏（要攻擊技能或法術技能），召喚骷髏明明是法術。
     * <p>
     * 2026-10-29 照 PoE2 的 Minion Types：技能自己的標籤不行時，召喚技能再照召喚物的技能標籤驗一次（{@link MinionTypes#check}），
     * 可以的話掛上去、效果給召喚物；requires 也不符合召喚物的技能時照舊回傳技能自己的原因。這次之前只看技能自己的標籤。
     */
    public String refusal(final SkillDefinition skill) {
        return refusal(skill, java.util.List.of());
    }

    /**
     * 2026-10-30 照 PoE2（輔助的 added_types）：同一個技能還掛著 others 這些輔助時——其中有一顆讓技能多了原作要的類型
     * （終式讓技能累積連擊 → 鐘星；鐘星讓技能產生戰星 → 滿星），原作的擋就不算（那一顆自己要掛得上）。
     */
    public String refusal(final SkillDefinition skill, final java.util.Collection<String> others) {
        final String ref = RefSupportRules.refusal(id, skill.id()); // 2026-10-30：照 PoE2 的技能類型（support_ref.yml）
        if (ref != null && !unlockedBy(skill, others)) {
            return ref;
        }
        if (Unearth.ID.equals(skill.id()) && Unearth.BLOCKED.contains(id)) {
            return "掘骸不能掛"; // 2026-10-30 照 PoE2
        }
        if (Unearth.ID.equals(skill.id()) && Unearth.PERSISTENT_ONLY.contains(id)) {
            return "骨構體不是常駐的召喚物"; // 2026-10-30 照 PoE2（只能輔助常駐召喚物）
        }
        final String own = ownRefusal(skill);
        if (own == null) {
            return null;
        }
        // 2026-10-30 照 PoE2：掘骸也是召喚技能（CreatesMinion）：召喚物輔助掛得上，效果只給叫出來的骨構體（SkillBuild 算骨刺時略過）
        if (unearthMinionSupport(skill)) {
            return null;
        }
        // 2026-10-29 照 PoE2：一顆寶石兩個技能（雷縛長棍＝增益＋電震波攻擊），能輔助其中一個就掛得上（SubSkills）
        final SkillDefinition sub = SubSkills.of(skill);
        if (sub != null && ownRefusal(sub) == null) {
            return null;
        }
        if (MinionTypes.of(skill).isEmpty()) {
            return own;
        }
        final MinionTypes.Verdict viaMinions = MinionTypes.check(this, skill);
        return viaMinions.ok() ? null : viaMinions.matched() ? viaMinions.reason() : own;
    }

    /** 2026-10-30：掛在掘骸上的召喚物輔助（要 minion 的；只能輔助常駐召喚物的不算）。 */
    boolean unearthMinionSupport(final SkillDefinition skill) {
        return Unearth.ID.equals(skill.id()) && requires.contains(Tags.MINION) && !excludes.contains(Tags.MINION)
                && !Unearth.PERSISTENT_ONLY.contains(id);
    }

    /** 只看技能自己的標籤能不能掛（{@link #refusal} 再補上召喚物的技能標籤）；可以回傳 null。 */
    String ownRefusal(final SkillDefinition skill) {
        if (skill.hidden() && skill.isAttack() && !SubSkills.isSub(skill.id())) {
            return "普攻不能掛輔助寶石";
        }
        for (final String tag : excludes) {
            if (skill.tags().contains(tag)) {
                return ModifierText.tagName(tag) + "技能不行";
            }
        }
        // 2026-10-27 照站長（防呆）：PoE2 0.5 補上的一般輔助，掛了完全沒作用的技能不讓掛（SupportGuards）
        final String guard = SupportGuards.refusal(this, skill);
        if (guard != null) {
            return guard;
        }
        // 2026-10-27 照 PoE2 Burgeon（漸熾）：只能掛在引導的技能（有 channelling 標籤：引導、蓄力、按住一直放的）
        if (flags(Burgeon.PER_SECOND) && !skill.tags().contains(tw.exilecore.stats.TreeMechanicKeys.CHANNELLING)) {
            return "要引導或蓄力的技能";
        }
        // 2026-10-27 照 PoE2 Efficiency（節流）：減少消耗的輔助不能掛在保留精魂的技能（光環、捷光環、常駐、精魂召喚都是 mechanic: aura）
        if (skill.mechanic() == Mechanic.AURA && reducesCost()) {
            return "保留精魂的技能不行";
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
        if (flags(StatKeys.CHANNEL_STAGES) && channels(skill)) {
            return "本身會蓄力的技能不行"; // 2026-10：蓄勢不能疊在本來就會蓄力的技能上（熾息、蓄力射擊…）
        }
        // 2026-10-28 照 PoE2 Spell Totem：圖騰照 TotemRules.spellTotemCastable（雷遁、骸爆、詛咒也行）；這次之前圖騰也只看 deployable
        if (flags(tw.exilecore.stats.StatKeys.FLAG_SPELL_TOTEM) ? !TotemRules.spellTotemCastable(skill)
                : flags(tw.exilecore.stats.StatKeys.FLAG_TRAP) && !SkillBuild.deployable(skill)) {
            return "這種技能不能交給圖騰或陷阱放"; // 2026-10：掘墳、號召這些只有 spell 標籤，以前掛得上去卻沒效果
        }
        if (!matchesRequires(skill)) {
            return "要" + requiresText();
        }
        // 強勢戰吼、不竭嘯、決嘯、碎晶嘯（2026-10-26 移到 requires 之後：不是戰吼的先說「要戰吼技能」）：要戰吼本身會強化攻擊，加次數才有意義
        if ((flags(StatKeys.WARCRY_EXERTS) || flags(StatKeys.WARCRY_RAGE_EXERT) || flags(tw.exilecore.stats.SupportKeys.WARCRY_TIRELESS)
                || flags(tw.exilecore.stats.SupportKeys.FLAG_EMPOWERED_CULL) || flags(tw.exilecore.stats.SupportKeys.WARCRY_CRYSTAL_EMPOWERS))
                && (skill.warcry() == null || !skill.warcry().empowers())) { // 2026-10-26：照強度強化的也算
            return "要會強化攻擊的戰吼";
        }
        // 2026-10-26 照 PoE2：怒嘯要可以用磐星跳過冷卻的戰吼；震嘯、血譽不能掛在本來就要榮耀才能用的戰吼（熔魂嘯）
        if (flags(tw.exilecore.stats.SupportKeys.WARCRY_RAGE_BYPASS) && skill.cooldownBypass() == null) {
            return "要可以花磐星跳過冷卻的戰吼";
        }
        if ((flags(tw.exilecore.stats.SupportKeys.WARCRY_ECHOES) || flags(tw.exilecore.stats.SupportKeys.GLORY_COST))
                && skill.warcry() != null && skill.warcry().extras().glory() > 0) {
            return "本來就要榮耀才能用的技能不行";
        }
        final String charge = chargeRefusal(skill);
        if (charge != null) {
            return charge; // 2026-10-24：戰星輔助
        }
        final String lineage = LineageRules.refusal(this, skill);
        if (lineage != null) {
            return lineage; // 2026-10-27 照 PoE2：血脈輔助各自的限制（LineageRules）
        }
        // 2026-10：觸發輔助（暴擊、受擊、擊殺、異常、翻滾、召喚物死亡觸發）不能掛在增益型的技能上（連珠、霜鏃、舉盾…）：
        // 照 PoE2 這些不是能被觸發的法術；以前掛得上去，觸發時卻略過，又因為掛了觸發不能手動放，整顆技能變廢
        if (triggers() && skill.mechanic() == Mechanic.GUARD) {
            return "增益型的技能（連珠、霜鏃、舉盾…）不能被觸發";
        }
        // 2026-10：光環、詛咒、印記、護體、戰吼、純位移、召喚本身不會打到敵人：只有擊中才有用的輔助（引火、致命、各種穿透、趁 X…）
        // 以前掛得上去，一條都不生效還白付魔力倍率。捷的輔助會傳給擊殺效果（隱藏的法術），所以照算
        if (!skill.dealsDamage() && !skill.tags().contains(Tags.HERALD) && !worksWithoutHits(skill)
                && !(requires.contains(Tags.META) && skill.tags().contains(Tags.META))) { // 2026-10-26：骰運掛在主要寶石上給觸發的技能
            return "不會造成傷害的技能不行";
        }
        // 2026-10：投射物的機制（多射幾發、穿透、分裂、連鎖、飛行速度…）只有真的射出投射物的技能才吃得到：
        // 落釘弩台是 impact 機制帶 projectile 標籤（定身用）；召喚技能本身不射投射物
        // （2026-10-29 照 PoE2：遠程召喚物的投射物真的會飛，從召喚物這邊掛的照 MinionTypes.check，連投射物速度都吃得到；這次之前「只是畫一道彈道」）
        // 2026-10-24：從天上掉下來的彈（弩台這種延遲落下的投射物）吃投射物速度（落下更快）：只動速度的輔助（疾矢…）可以掛
        // 2026-10-27：射到地上再落下的投射物技能（召霆矢…）多的投射物各落一道（SkillExecutor.impact）：動到投射物數量的輔助也可以掛
        // 2026-10-28 照 PoE2 Falling Thunder：猛擊之後另外射投射物的（skills.yml stages.thunder，SkillExecutor.thunderBolts）也算
        if (projectileMechanics() && skill.mechanic() != Mechanic.PROJECTILE && skill.mechanic() != Mechanic.CHAIN
                && SkillStages.get(skill.id(), SkillExecutor.THUNDER) == null
                && !((onlyProjectileSpeed() || addsProjectiles()) && skill.mechanic() == Mechanic.IMPACT && SkillExecutor.fallsAsProjectile(skill))
                && !(onlyChains() && chainingBolts(skill))) {
            return "要真的射出投射物的技能";
        }
        return null;
    }

    /** 不用擊中敵人也有作用的屬性：消耗、等級、保留精魂、給予的增益、觸發、施法速度、灌注…（範圍、持續、冷卻另外看技能有沒有那個標籤；召喚物的看 minion 標籤）。 */
    private static final Set<String> UTILITY_STATS = Set.of(StatKeys.SKILL_ADDED_COOLDOWN, StatKeys.SKILL_COST, StatKeys.SKILL_LEVEL,
            StatKeys.SKILL_MINIONS, StatKeys.SKILL_TOTEMS, StatKeys.SUPPORT_SPIRIT, StatKeys.CURSE_EFFECT, StatKeys.MARK_EFFECT, StatKeys.CAST_SPEED, StatKeys.FLAG_ECHO,
            StatKeys.FLAG_BLOOD_MAGIC, StatKeys.MANA_ON_MINION_DEATH, StatKeys.MINION_DEATH_HEAL,
            tw.exilecore.stats.KeystoneKeys.SKILL_LIMIT, // 2026-10-28 照 PoE2 過剩：+x 上限不用擊中（印記、祭品、折光之鏡也有用）
            StatKeys.FLAG_SPELL_TOTEM); // 2026-10-28 照 PoE2 Spell Totem：交給圖騰放不用擊中（詛咒也能交給圖騰）；這次之前只靠星咒圖騰的施法速度那條
    private static final List<String> UTILITY_PREFIXES = List.of(StatKeys.GRANT_PREFIX, "warcry.", "aura.", "herald.", "trigger.", "seal.", "meta.",
            "infusion.", "proc.use", "proc.minion_death", "charge.sup.",
            // 2026-10-26：照 PoE2 重做的輔助：戰吼輔助、榮耀、凍結時的增益、印記、詛咒、圖騰、召喚物、獻祭生命
            tw.exilecore.stats.SupportKeys.FLAG_BATTERSHOUT, tw.exilecore.stats.SupportKeys.FLAG_PAQUATE, "glory.",
            tw.exilecore.stats.SupportKeys.ICE_BITE, tw.exilecore.stats.SupportKeys.SHATTER_FANG,
            tw.exilecore.stats.SupportKeys.FLAG_EMPOWERED_CULL, tw.exilecore.stats.SupportKeys.FLAG_UNERRING, "cost.",
            tw.exilecore.stats.SupportKeys.FLAG_EXTRA_SKELETON, tw.exilecore.stats.SupportKeys.FLAG_MARK_ETERNAL, "mark.", "curse.", "totem.",
            "minion.", "trigger_cost.", "plant.", // 2026-10-30：催綻（植物繁茂時觸發）不用擊中
            "surging_chant.", // 2026-10-26 照 PoE2 湧詠：花魔力就給秘術增強（不用擊中）
            "helbrym.", "storm.", // 2026-10-26 照 PoE2：蟄甲（變身時）、行嵐與掠嵐（風暴移動）不用擊中
            // 2026-10-27 照 PoE2 0.5 補上的一般輔助：放技能時的增益、殘骸、先祖強化、用掉的資源、地面、詛咒、印記…不用擊中（能不能掛另外看 SupportGuards）
            tw.exilecore.stats.SupportExtraKeys.USING_PREFIX, tw.exilecore.stats.SupportExtraKeys.USING_QUARTER_PREFIX,
            tw.exilecore.stats.SupportExtraKeys.CHANNELLING_PREFIX, tw.exilecore.stats.SupportExtraKeys.REMNANT_PREFIX, "ancestral.",
            "flask.consume", "volatility.", "conditional.", "cooldown.", "corpse.", "banner.", "leap.", "travel.", "es.recharge",
            "thorns.mana", "channel.", "ground.", "hazard.", "wall.", "fissure.", "rage.relentless", "charge.endurance_life", "spell.cascade",
            "nova.", "persistent.", "objects.",
            tw.exilecore.stats.LineageKeys.UTILITY); // 2026-10-27 照 PoE2：血脈輔助不用擊中的效果（lineage.u.）
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

    /**
     * 2026-10-29 照 PoE2 Chain I（Supports Projectile skills and any other skills that Chain）：動到的投射物機制只有連鎖次數
     * （連環這種）。這次之前跟分裂、穿透一起被當成「要真的射出投射物」，雷雲掛不上去。
     */
    boolean onlyChains() {
        boolean chains = false;
        for (final Modifier modifier : modifiers) {
            if (StatKeys.SKILL_CHAINS.equals(modifier.stat())) {
                chains = true;
            }
        }
        if (!chains) {
            return false;
        }
        for (final Modifier modifier : modifiers) {
            if (!StatKeys.SKILL_CHAINS.equals(modifier.stat()) && projectileMechanic(modifier.stat())) {
                return false; // 還動到別的投射物機制（分裂、穿透、多射…）：雷雲吃不到
            }
        }
        return true;
    }

    /** 2026-10-29：放會連鎖的雷的區域技能（雷雲：skills.yml zone.bolts，SkillExecutor 把 skill.chains 加到每一道雷的連鎖次數）。 */
    private static boolean chainingBolts(final SkillDefinition skill) {
        return skill.mechanic() == Mechanic.ZONE && skill.tags().contains("chain")
                && skill.params().zone() != null && skill.params().zone().bolts() != null;
    }

    /** 這顆輔助有沒有動到投射物本身的機制（數量、穿透、分裂、連鎖、速度、環射、回返、封矢）。 */
    public boolean projectileMechanics() {
        for (final Modifier modifier : modifiers) {
            if (projectileMechanic(modifier.stat())) {
                return true;
            }
        }
        return false;
    }

    /** 這個屬性是不是投射物本身的機制（{@link #projectileMechanics}）。 */
    private static boolean projectileMechanic(final String stat) {
        return switch (stat) {
            case StatKeys.SKILL_PROJECTILES, StatKeys.SKILL_PIERCE, StatKeys.SKILL_FORK, StatKeys.SKILL_CHAINS,
                 StatKeys.SKILL_PROJECTILE_SPEED, StatKeys.FLAG_PROJECTILE_NOVA, StatKeys.FLAG_RETURNING, StatKeys.SEAL_PROJECTILES,
                 tw.exilecore.stats.SupportKeys.PIERCE_IGNITED, tw.exilecore.stats.SupportKeys.FORK_FROZEN,
                 tw.exilecore.stats.SupportKeys.CHAIN_SHOCKED, // 2026-10-26：穿燼、霜岔、躍霆
                 tw.exilecore.stats.SupportExtraKeys.FLAG_GAMBLESHOT, tw.exilecore.stats.SupportExtraKeys.FLAG_RICOCHET,
                 tw.exilecore.stats.SupportExtraKeys.ALIGNMENT, tw.exilecore.stats.SupportExtraKeys.FLAG_SPECTRAL,
                 tw.exilecore.stats.SupportExtraKeys.CHARGED_SHOTS -> true; // 2026-10-27：隨機、跳彈、指向、光影齊射、充能射擊
            default -> false;
        };
    }

    /**
     * 2026-10-27：會多射投射物（數量 +x），而且沒有落地型技能做不到的特殊機制（環射、回返、封矢、跳彈、光影齊射…）。
     * 穿透、分裂、連鎖對落地型技能沒有作用，但跟多射綁在一起的（星辰萬象、分光這種）照原作可以掛。
     */
    boolean addsProjectiles() {
        boolean more = false;
        for (final Modifier modifier : modifiers) {
            switch (modifier.stat()) {
                case StatKeys.SKILL_PROJECTILES -> more = more || modifier.value() > 0;
                case StatKeys.FLAG_PROJECTILE_NOVA, StatKeys.FLAG_RETURNING, StatKeys.SEAL_PROJECTILES,
                     tw.exilecore.stats.SupportExtraKeys.FLAG_GAMBLESHOT, tw.exilecore.stats.SupportExtraKeys.FLAG_RICOCHET,
                     tw.exilecore.stats.SupportExtraKeys.ALIGNMENT, tw.exilecore.stats.SupportExtraKeys.FLAG_SPECTRAL,
                     tw.exilecore.stats.SupportExtraKeys.CHARGED_SHOTS -> {
                    return false;
                }
                default -> { }
            }
        }
        return more;
    }

    /** 2026-10-24：動到的投射物機制只有速度（沒有數量、穿透、分裂、連鎖…）。 */
    boolean onlyProjectileSpeed() {
        boolean speed = false;
        for (final Modifier modifier : modifiers) {
            switch (modifier.stat()) {
                case StatKeys.SKILL_PROJECTILE_SPEED -> speed = true;
                case StatKeys.SKILL_PROJECTILES, StatKeys.SKILL_PIERCE, StatKeys.SKILL_FORK, StatKeys.SKILL_CHAINS,
                     StatKeys.FLAG_PROJECTILE_NOVA, StatKeys.FLAG_RETURNING, StatKeys.SEAL_PROJECTILES,
                     tw.exilecore.stats.SupportKeys.PIERCE_IGNITED, tw.exilecore.stats.SupportKeys.FORK_FROZEN,
                     tw.exilecore.stats.SupportKeys.CHAIN_SHOCKED -> {
                    return false;
                }
                default -> { }
            }
        }
        return speed;
    }

    /**
     * 技能本身會不會蓄力（技能的修飾器有 channel.stages，或是有引導標籤的蓄力／引導技能）。
     * 2026-10-27 照站長：以前只看 channel.stages，炎核爆、碎骨飛矢這類寫在 charge 的蓄力技能擋不到，蓄勢掛得上去。
     */
    static boolean channels(final SkillDefinition skill) {
        if (skill.tags().contains("channelling")) {
            return true;
        }
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(StatKeys.CHANNEL_STAGES) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-29 照 PoE2（Supports Persistent Buff skills：泉魔、泉命、凝準…）：requires 寫這個＝光環、捷光環，或同時是常駐、增益的技能
     * （僕骸替、骸餘饗、引星…）。這次之前這些輔助寫 [aura, herald]，常駐增益靠「一律算光環」才掛得上。
     */
    public static final String PERSISTENT_BUFF = "persistent_buff";

    /** 光環、捷光環，或同時是常駐、增益的技能（{@link #PERSISTENT_BUFF}）。 */
    public static boolean persistentBuff(final SkillDefinition skill) {
        final Set<String> tags = skill.tags();
        return tags.contains(Tags.AURA) || tags.contains(Tags.HERALD) || tags.contains(Tags.PERSISTENT) && tags.contains(Tags.BUFF);
    }

    private boolean matchesRequires(final SkillDefinition skill) {
        if (requires.isEmpty()) {
            return true;
        }
        for (final String tag : requires) {
            if (PERSISTENT_BUFF.equals(tag)) {
                if (persistentBuff(skill)) {
                    return true;
                }
                continue;
            }
            if (Tags.META.equals(tag) && !metaHost(skill)) {
                continue; // 2026-10-26：英靈圖騰補了 meta 標籤（照 PoE2），能量輔助、骰運還是不能掛在圖騰上
            }
            if (skill.tags().contains(tag) || skill.weapons().contains(tag)) { // 2026-10：也可以寫武器（crossbow：弩的技能）
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-26 照 PoE2：要 meta 的輔助能不能把這個技能當主要寶石：英靈圖騰（照 PoE2 有 Meta 標籤，但不觸發、沒有能量）不算；
     * 星咒圖騰沒有能量，能量輔助（meta.energy_*）不算（骰運照舊可以）。
     */
    private boolean metaHost(final SkillDefinition skill) {
        if (flagged(skill, StatKeys.FLAG_TOTEM_LINKED) || HollowForm.hosts(skill) || ChayulaHand.hosts(skill)) {
            return false; // 2026-10-28：空無型態也是（不觸發、沒有能量）；2026-10-29：夏烏拉之手也是
        }
        if (skill.tags().contains(Tags.TOTEM)) {
            for (final Modifier modifier : modifiers) {
                if (modifier.stat().startsWith("meta.energy")) {
                    return false;
                }
            }
        }
        return true;
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
    /** 2026-10-27：有沒有「消耗更少」（skill.cost 更多／增加是負的；節流）。 */
    boolean reducesCost() {
        for (final Modifier modifier : modifiers) {
            if (modifier.stat().equals(StatKeys.SKILL_COST) && modifier.value() < 0) {
                return true;
            }
        }
        return false;
    }

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
        // 2026-10-28：雙詠、虛星分身、連瀑只能掛可以連瀑的（SupportGuards）；這次之前寫「法術技能（…除外）」，看起來碎骨飛矢這類也能掛
        if (flags(StatKeys.FLAG_ECHO) || flags(tw.exilecore.stats.SupportExtraKeys.CASCADE)) {
            return "可以連瀑的範圍法術（落在目標區域、身邊一圈的，或詛咒）";
        }
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
        final String text = names.isEmpty() ? requiresText() : requiresText() + "（" + String.join("、", names) + "除外）";
        return skills != null && minionCapable(skills) ? text + "；召喚物的技能符合時也能掛在召喚技能上（效果給召喚物）" : text;
    }

    /** 2026-10-29 照 PoE2 的 Minion Types：有沒有召喚技能是只靠召喚物的技能標籤才掛得上這顆的（說明用）。 */
    private boolean minionCapable(final java.util.Collection<SkillDefinition> skills) {
        for (final SkillDefinition skill : skills) {
            if (!skill.hidden() && MinionTypes.throughMinions(this, skill)) {
                return true;
            }
        }
        return false;
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
            "power", Set.of("instant_shadow_strike", "corpse_summon", "star_spell_totem", "startle_thunder_chop", // 2026-10-28 照 PoE2：＋驚雷劈（用掉所有的銳星）
                    HollowForm.ID), // 2026-10-28 照 PoE2 Hollow Form：用掉銳星多 2 個幻影
            "frenzy", Set.of("thunder_lance_throw", "thunderfall_spear", "strung_pearls"),
            "endurance", Set.of("star_spell_totem"));
    /** 2026-10-24：一定要消耗戰星才放得出來的技能（抑星不能掛）。 */
    static final Set<String> REQUIRES_CHARGES = Set.of("star_spell_totem");
    /** 2026-10-24：有完美時機的技能（完磐只能掛這些）。 */
    static final Set<String> PERFECT_TIMING = Set.of("charged_blow", "cloud_pierce_arrow");

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
