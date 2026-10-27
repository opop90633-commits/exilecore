package tw.exilecore.ascendancy;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.Rarity;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-26 照 PoE2 0.5：昇華天賦在「算屬性表」時的效果（PlayerStateService、SkillService 算技能時呼叫；純計算，不碰玩家）。
 * 這次之前這些天賦寫著（尚未生效）。
 */
public final class AscBuild {

    /** 寶石顏色（data/gem_colours.yml）：技能、輔助寶石 → 0 紅（力量）、1 綠（敏捷）、2 藍（智慧）。 */
    private static Map<String, Integer> skillColours = Map.of();
    private static Map<String, Integer> supportColours = Map.of();

    /** 煉獄之炎的上限（兩倍的最大魔力），建表時記在這個基礎值上。 */
    static final String INFERNAL_MAX = "asc.base.infernal_max";
    /** 主手武器上的命中值（穿透用），建表時記在這個基礎值上。 */
    static final String WEAPON_ACCURACY = "asc.base.weapon_accuracy";

    private AscBuild() {
    }

    /** 讀 data/gem_colours.yml（沒有這個檔就全部當作沒有顏色）。 */
    static void loadColours(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("gem_colours.yml");
        if (!Files.exists(file)) {
            skillColours = Map.of();
            supportColours = Map.of();
            return;
        }
        final YamlNode root = YamlNode.load(file);
        skillColours = colours(root.node("skills"));
        supportColours = colours(root.node("supports"));
    }

    private static Map<String, Integer> colours(final YamlNode node) {
        final Map<String, Integer> out = new HashMap<>();
        if (node == null) {
            return out;
        }
        for (final String key : node.keys()) {
            final String value = node.string(key, "");
            final int colour = switch (value.trim()) {
                case "r" -> 0;
                case "g" -> 1;
                case "b" -> 2;
                default -> -1;
            };
            if (colour >= 0) {
                out.put(key, colour);
            }
        }
        return Map.copyOf(out);
    }

    /** 測試用。 */
    static void setColours(final Map<String, Integer> skills, final Map<String, Integer> supports) {
        skillColours = Map.copyOf(skills);
        supportColours = Map.copyOf(supports);
    }

    /** 2026-10-27：技能寶石的顏色以 GemAttributes（RePoE 的屬性分配）為準，表上沒有的才看 gem_colours.yml（這次之前只看 yml，兩邊不一樣）。 */
    public static int skillColour(final String id) {
        final int listed = tw.exilecore.skill.GemAttributes.colourIndex(id);
        return listed >= 0 ? listed : skillColours.getOrDefault(id, -1);
    }

    /** 2026-10-27：輔助寶石的顏色以 SupportColours 為準（白色的再看 gem_colours.yml；這次之前只看 yml）。 */
    public static int supportColour(final String id) {
        final int listed = switch (tw.exilecore.skill.SupportColours.colour(id)) {
            case tw.exilecore.skill.SupportColours.RED -> 0;
            case tw.exilecore.skill.SupportColours.GREEN -> 1;
            case tw.exilecore.skill.SupportColours.BLUE -> 2;
            default -> -1;
        };
        return listed >= 0 ? listed : supportColours.getOrDefault(id, -1);
    }

    /** 面板上全部技能鑲嵌的輔助寶石，各顏色幾顆（紅、綠、藍）。 */
    static int[] socketed(final PlayerState state) {
        final int[] count = new int[3];
        if (state == null || state.skills() == null) {
            return count;
        }
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry == null) {
                continue;
            }
            for (final String support : entry.supports()) {
                final int colour = support == null ? -1 : supportColour(support);
                if (colour >= 0) {
                    count[colour]++;
                }
            }
        }
        return count;
    }

    /** 鑲嵌的寶石：數量最多的顏色（平手的都算）。 */
    static boolean[] mostNumerous(final int[] count) {
        final int max = Math.max(count[0], Math.max(count[1], count[2]));
        return new boolean[] {max > 0 && count[0] == max, max > 0 && count[1] == max, max > 0 && count[2] == max};
    }

    /** 技能消耗倍率（鑲嵌的寶石・藍：技能消耗更少 30%）。 */
    static double costScale(final AscendancyEffects effects, final Player player) {
        final PlayerState state = effects.plugin.players().get(player);
        if (!AscendancyEffects.has(state.sheet(), AscKeys.GEM_STUDDED)) {
            return 1;
        }
        return mostNumerous(socketed(state))[2] ? 0.7 : 1;
    }

    // =====================================================================
    // 技能（SkillService.buildFresh 呼叫）
    // =====================================================================

    /**
     * 一個技能的嵌晶效果：品質加多少、品質效果算兩次（進階奇術）、技能等級加多少（鑲嵌物）、
     * 這個技能自己的修飾器（整合效率：每個相連的紅／綠／藍輔助寶石）。
     */
    public record Gem(int quality, boolean twice, int levels, List<Modifier> extra) {
        public static final Gem NONE = new Gem(0, false, 0, List.of());
    }

    public static Gem gem(final StatSheet sheet, final SkillDefinition definition, final List<SupportDefinition> supports) {
        if (sheet == null || definition == null) {
            return Gem.NONE;
        }
        final int quality = (int) Math.round(sheet.flat(AscKeys.SKILL_QUALITY, Set.of()));
        final boolean twice = sheet.flat(AscKeys.ADVANCED_QUALITY, Set.of()) > 0;
        int levels = 0;
        final int colour = skillColour(definition.id());
        if (colour == 0) {
            levels += (int) Math.round(sheet.flat(AscKeys.LEVEL_STR, Set.of()));
        } else if (colour == 1) {
            levels += (int) Math.round(sheet.flat(AscKeys.LEVEL_DEX, Set.of()));
        } else if (colour == 2) {
            levels += (int) Math.round(sheet.flat(AscKeys.LEVEL_INT, Set.of()));
        }
        final List<Modifier> extra = new ArrayList<>();
        if (sheet.flat(AscKeys.INTEGRATED, Set.of()) > 0 && supports != null) {
            final int[] count = new int[3];
            for (final SupportDefinition support : supports) {
                final int c = support == null ? -1 : supportColour(support.id());
                if (c >= 0) {
                    count[c]++;
                }
            }
            if (count[0] > 0) {
                extra.add(Modifier.increased(StatKeys.DAMAGE, 0.20 * count[0], "整合效率"));
            }
            if (count[1] > 0) {
                extra.add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.06 * count[1], "整合效率"));
                extra.add(Modifier.increased(StatKeys.CAST_SPEED, 0.06 * count[1], "整合效率"));
            }
            if (count[2] > 0) {
                extra.add(Modifier.increased(StatKeys.CRIT_CHANCE, 0.20 * count[2], "整合效率"));
            }
        }
        if (quality == 0 && !twice && levels == 0 && extra.isEmpty()) {
            return Gem.NONE;
        }
        return new Gem(quality, twice, levels, List.copyOf(extra));
    }

    /**
     * 2026-10-27 照 PoE2 進階奇術（Gem Quality grants Socketed Skills an additional effect）：品質另外給這個技能的「額外品質效果」
     * （gem_quality.yml 的 advanced 區塊，照 poe2db 寶石頁）。表上沒有的技能（額外效果的機制我們還沒有）照舊把品質效果再算一次（平衡等效）。
     * 這次之前全部都是品質效果再算一次。
     */
    public static List<Modifier> advanced(final String skillId, final int quality, final List<Modifier> normal) {
        // 來源要用「品質 」開頭（SkillBuild 照這個認出品質給的增益 grant.*）
        final List<Modifier> extra = tw.exilecore.currency.GemQuality.advancedModifiers(skillId, quality, "品質 " + quality + "%（進階奇術）");
        if (extra == null) {
            return twice(normal);
        }
        final List<Modifier> out = new ArrayList<>(normal);
        out.addAll(extra);
        return out;
    }

    /** 品質的修飾器算兩次（進階奇術：額外品質效果還沒做的技能，平衡等效成效果加倍）。 */
    public static List<Modifier> twice(final List<Modifier> quality) {
        final List<Modifier> out = new ArrayList<>(quality);
        for (final Modifier mod : quality) {
            out.add(new Modifier(mod.stat(), mod.type(), mod.value(), mod.tags(), "進階奇術"));
        }
        return out;
    }

    // =====================================================================
    // 屬性表（PlayerStateService 呼叫）
    // =====================================================================

    /** 這個角色配的昇華天賦裡，某個屬性加起來（屬性表還沒算好時用；沒配是 0）。 */
    public static double ascFlat(final ExileCore plugin, final PlayerState state, final String stat) {
        if (plugin == null || plugin.ascendancy() == null || state == null || !state.hasCharacter()) {
            return 0;
        }
        double total = 0;
        for (final Modifier mod : plugin.ascendancy().ascension(state).modifiers()) {
            if (stat.equals(mod.stat()) && mod.type() == ModType.FLAT) {
                total += mod.value();
            }
        }
        return total;
    }

    /** 同上，從一串修飾器裡加。 */
    public static double flatOf(final List<Modifier> mods, final String stat) {
        double total = 0;
        for (final Modifier mod : mods) {
            if (stat.equals(mod.stat()) && mod.type() == ModType.FLAT) {
                total += mod.value();
            }
        }
        return total;
    }

    /**
     * 屬性需求（減少能力值需求、應變能力）：需求不符時再看一次。
     *
     * @return 實際拿來比的力量、敏捷、智慧
     */
    public static double[] requirementAttributes(final List<Modifier> ascendancy, final double str, final double dex, final double intelligence) {
        double s = str;
        double d = dex;
        double i = intelligence;
        if (flatOf(ascendancy, AscKeys.ADAPTIVE) > 0) {
            final double best = Math.max(s, Math.max(d, i));
            s = best;
            d = best;
            i = best;
        }
        final double reduced = Math.min(90, -flatOf(ascendancy, AscKeys.ATTRIBUTE_REQUIREMENT));
        if (reduced > 0) {
            final double scale = 1 / (1 - reduced / 100.0);
            s *= scale;
            d *= scale;
            i *= scale;
        }
        return new double[] {s, d, i};
    }

    /** 鐵匠傑作：只能使用普通（白色）胸甲（其他的不生效）。回傳不能用的原因，可以用回傳 null。 */
    public static String itemProblem(final List<Modifier> ascendancy, final ItemSlot slot, final ItemData data) {
        if (slot == ItemSlot.CHEST && flatOf(ascendancy, AscKeys.NORMAL_CHEST) > 0 && data != null && data.rarity() != Rarity.NORMAL) {
            return "鐵匠傑作只能使用普通胸甲";
        }
        return null;
    }

    /**
     * 屬性的基礎值算完之後（等級、屬性的固有加成）：強化效果（固有加成加倍）、無魂型態（沒有固有魔力再生、每秒再生最大生命 6% 的魔力）、
     * 火祭協議（最大魔力換成兩倍的煉獄之炎）。
     */
    public static void afterBase(final StatSheet sheet, final double str, final double dex, final double intelligence, final boolean altStr,
                                 final boolean altDex, final boolean altInt) {
        if (sheet.flat(AscKeys.INHERENT_DOUBLE, Set.of()) > 0) {
            if (!altStr) {
                sheet.add(Modifier.flat(StatKeys.LIFE_MAX, Formulas.lifeFromStrength(str), "強化效果"));
                sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, Formulas.meleePhysicalIncreaseFromStrength(str),
                        Set.of(Tags.MELEE, Tags.PHYSICAL), "強化效果"));
            }
            if (!altInt) {
                sheet.add(Modifier.flat(StatKeys.MANA_MAX, Formulas.manaFromIntelligence(intelligence), "強化效果"));
                sheet.add(Modifier.increased(StatKeys.ES_MAX, Formulas.esIncreaseFromIntelligence(intelligence), "強化效果"));
            }
            if (!altDex) {
                sheet.add(Modifier.flat(StatKeys.ACCURACY, Formulas.accuracyFromDexterity(dex), "強化效果"));
                sheet.add(Modifier.increased(StatKeys.EVASION, Formulas.evasionIncreaseFromDexterity(dex), "強化效果"));
            }
        }
        if (sheet.flat(AscKeys.INFERNAL, Set.of()) > 0) {
            sheet.setBase(INFERNAL_MAX, sheet.get(StatKeys.MANA_MAX) * 2);
            sheet.add(Modifier.more(StatKeys.MANA_MAX, -1.0, "火祭協議"));
            sheet.setBase(StatKeys.MANA_REGEN, 0);
        }
        if (sheet.flat(AscKeys.NO_MANA_REGEN, Set.of()) > 0) {
            sheet.setBase(StatKeys.MANA_REGEN, 0);
        }
        final double fromLife = sheet.flat(AscKeys.MANA_REGEN_LIFE, Set.of());
        if (fromLife > 0) {
            sheet.add(Modifier.flat(StatKeys.MANA_REGEN, sheet.get(StatKeys.LIFE_MAX) * fromLife / 100.0, "無魂型態"));
        }
    }

    static double infernalMax(final StatSheet sheet) {
        return sheet == null ? 0 : sheet.base(INFERNAL_MAX);
    }

    /** 一件生效的裝備（PlayerStateService 傳進來）。 */
    public record Gear(ItemSlot slot, ItemBase base, ItemData data, List<Modifier> modifiers) {
    }

    /**
     * 裝備算完之後：添煤工、烈焰鑄造、引我行恩典之途、神聖流向、聖賢智慧（符文效果）、
     * 鑲嵌的寶石、奇術注入、穿透（記下主手武器的命中值）、力量之器（法器加成減少）。
     */
    public static void equipment(final ExileCore plugin, final StatSheet sheet, final PlayerState state, final List<Gear> gear) {
        if (sheet.flat(AscKeys.COAL_STOKER, Set.of()) > 0) {
            // 火焰抗性的修飾器也給一半的冰冷、閃電抗性（不含抗性懲罰）
            double fire = 0;
            for (final Modifier mod : sheet.modifiersOf(StatKeys.RESIST_FIRE)) {
                if (mod.type() == ModType.FLAT && !"抗性懲罰".equals(mod.source()) && !"添煤工".equals(mod.source())) {
                    fire += mod.value();
                }
            }
            final double share = fire * sheet.flat(AscKeys.COAL_STOKER, Set.of()) / 100.0;
            if (share != 0) {
                sheet.add(Modifier.flat(StatKeys.RESIST_COLD, share, "添煤工"));
                sheet.add(Modifier.flat(StatKeys.RESIST_LIGHTNING, share, "添煤工"));
            }
        }
        if (sheet.flat(AscKeys.FORGED_FLAME, Set.of()) > 0) {
            final double max = sheet.flat(StatKeys.resistMax(tw.exilecore.stats.DamageType.FIRE), Set.of());
            if (max != 0) {
                sheet.add(Modifier.flat(StatKeys.resistMax(tw.exilecore.stats.DamageType.COLD), max, "烈焰鑄造"));
                sheet.add(Modifier.flat(StatKeys.resistMax(tw.exilecore.stats.DamageType.LIGHTNING), max, "烈焰鑄造"));
            }
        }
        double weaponAccuracy = 0;
        final boolean bonded = sheet.flat(AscKeys.BONDED, Set.of()) > 0;
        final boolean grace = sheet.flat(AscKeys.GRACE, Set.of()) > 0;
        double gearSpirit = 0;
        for (final Gear item : gear) {
            if (item.slot() == ItemSlot.MAIN_HAND) {
                for (final Modifier mod : item.modifiers()) {
                    if (StatKeys.ACCURACY.equals(mod.stat()) && mod.type() == ModType.FLAT && mod.tags().isEmpty()) {
                        weaponAccuracy += mod.value();
                    }
                }
            }
            // 2026-10-26：展露之指照 PoE2 改成第三個戒指欄（PlayerState.RING3_SLOT）；這次之前是戒指的詞綴效果增加 50%
            // 2026-10-27 照 PoE2 聖賢智慧：嵌著的符文的羈絆詞綴生效（runes.yml 的 bonded）；這次之前沒有羈絆詞綴，平衡等效成符文效果增加 50%
            if (bonded && plugin != null && plugin.items() != null && item.base() != null && item.data() != null) {
                for (final Modifier mod : tw.exilecore.item.Runes.bondedModifiers(plugin.items().repository(), item.base(), item.data())) {
                    sheet.add(mod);
                }
            }
            if (grace) {
                for (final Modifier mod : item.modifiers()) {
                    if (StatKeys.SPIRIT_MAX.equals(mod.stat()) && mod.type() == ModType.FLAT) {
                        gearSpirit += mod.value();
                    }
                }
                if (item.base() != null && item.base().tags().contains("sceptre")) {
                    gearSpirit += 100; // 權杖本身的精魂（含本地的增加，見 PlayerStateService）
                    for (final Modifier mod : item.modifiers()) {
                        if (StatKeys.SPIRIT_MAX.equals(mod.stat()) && mod.type() == ModType.INCREASED) {
                            gearSpirit += 100 * mod.value();
                        }
                    }
                }
                if (item.slot() == ItemSlot.CHEST) {
                    // 引我行恩典之途：每 8 點胸甲的能量護盾、每 20 點胸甲的閃避 +1 精魂
                    double es = 0;
                    double evasion = 0;
                    for (final Modifier mod : item.modifiers()) {
                        if (mod.type() == ModType.FLAT && mod.tags().isEmpty()) {
                            if (StatKeys.ES_MAX.equals(mod.stat())) {
                                es += mod.value();
                            } else if (StatKeys.EVASION.equals(mod.stat())) {
                                evasion += mod.value();
                            }
                        }
                    }
                    sheet.add(Modifier.flat(StatKeys.SPIRIT_MAX, Math.floor(es / 8) + Math.floor(evasion / 20), "引我行恩典之途"));
                }
            }
            if (item.slot() == ItemSlot.OFF_HAND && sheet.flat(AscKeys.STAFF_FOCUS, Set.of()) > 0 && item.base() != null
                    && item.base().tags().contains("focus") && hasStaff(gear)) {
                // 力量之器：持用長杖時裝備的法器，加成減少 50%
                final double less = sheet.flat(AscKeys.STAFF_FOCUS, Set.of()) / 100.0;
                for (final Modifier mod : item.modifiers()) {
                    if (mod.type() != ModType.MORE && !mod.stat().startsWith("flag.")) {
                        sheet.add(new Modifier(mod.stat(), mod.type(), -mod.value() * less, mod.tags(), "力量之器"));
                    }
                }
            }
        }
        if (grace && gearSpirit > 0) {
            sheet.add(Modifier.flat(StatKeys.SPIRIT_MAX, -gearSpirit, "引我行恩典之途")); // 無法從裝備獲得精魂
        }
        sheet.setBase(WEAPON_ACCURACY, weaponAccuracy);
        // 神聖流向：每個空的護符欄位 +40 精魂
        final double perCharm = sheet.flat(AscKeys.SPIRIT_PER_EMPTY_CHARM, Set.of());
        if (perCharm > 0 && state != null) {
            final int open = (int) Math.max(0, Math.min(PlayerState.CHARM_SLOTS, Math.round(sheet.get(tw.exilecore.flask.CharmService.STAT_SLOTS))));
            int empty = 0;
            for (int i = 0; i < open; i++) {
                final org.bukkit.inventory.ItemStack charm = tw.exilecore.flask.CharmService.charmAt(state, i);
                if (charm == null || charm.isEmpty()) {
                    empty++;
                }
            }
            if (empty > 0) {
                sheet.add(Modifier.flat(StatKeys.SPIRIT_MAX, perCharm * empty, "神聖流向"));
            }
        }
        // 嵌晶：鑲嵌的寶石（紅：對你的擊中沒有暴擊傷害加成；綠：增加 10% 移動速度）、奇術注入（每 3 個某色輔助 +1% 最大抗性）
        if (state != null && (sheet.flat(AscKeys.GEM_STUDDED, Set.of()) > 0 || sheet.flat(AscKeys.THAUMATURGICAL, Set.of()) > 0)) {
            final int[] count = socketed(state);
            if (sheet.flat(AscKeys.GEM_STUDDED, Set.of()) > 0) {
                final boolean[] most = mostNumerous(count);
                if (most[0]) {
                    sheet.add(Modifier.increased(StatKeys.CRIT_BONUS_TAKEN, -1.0, "鑲嵌的寶石"));
                }
                if (most[1]) {
                    // 2026-10-27 照站長「放技能減速全部移除」：原作「移動中使用技能的移動速度懲罰更少 40%」改回平衡等效的增加 10% 移動速度
                    // （懲罰 70% × 40% = 放技能時快 28 個百分點 × 放技能約佔移動中的 36%）；這次之前（2026-10-26）照原作減 SkillMovePenalty
                    sheet.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.10, "鑲嵌的寶石"));
                }
            }
            if (sheet.flat(AscKeys.THAUMATURGICAL, Set.of()) > 0) {
                addMaxResist(sheet, tw.exilecore.stats.DamageType.FIRE, count[0] / 3);
                addMaxResist(sheet, tw.exilecore.stats.DamageType.LIGHTNING, count[1] / 3);
                addMaxResist(sheet, tw.exilecore.stats.DamageType.COLD, count[2] / 3);
            }
        }
        // 衝刺突擊：原作「移動中使用技能的移動速度懲罰更少 30%」。2026-10-27 照站長「放技能減速全部移除」：改回 2026-10-26 之前的平衡等效，
        // 每 1% 懲罰 ≈ 增加 0.27% 移動速度（30 → 增加 8% 移動速度）；這次之前（2026-10-26）照原作減 SkillMovePenalty
        final double penalty = sheet.flat(AscKeys.MOVE_PENALTY, Set.of());
        if (penalty > 0) {
            sheet.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, penalty / 100.0 * 0.27, "衝刺突擊"));
        }
        // 為齒輪拋光：等同 20% 護甲的偏斜值、100% 閃避值當作額外異常門檻
        final double deflect = sheet.flat(AscKeys.DEFLECT_FROM_ARMOUR, Set.of());
        if (deflect > 0) {
            sheet.add(Modifier.flat(StatKeys.DEFLECTION, sheet.get(StatKeys.ARMOUR) * deflect / 100.0, "為齒輪拋光"));
        }
        final double threshold = sheet.flat(AscKeys.THRESHOLD_FROM_EVASION, Set.of());
        if (threshold > 0) {
            sheet.add(Modifier.flat(StatKeys.AILMENT_THRESHOLD, sheet.get(StatKeys.EVASION) * threshold / 100.0, "為齒輪拋光"));
        }
        // 2026-10-27 照 PoE2 0.5 補上的昇華：偶像崇敬、石拳之道、符文子午線
        idolatry(plugin, sheet, gear);
        stonefist(sheet, state, gear);
        meridians(plugin, sheet, state, gear);
    }

    /**
     * 2026-10-27 照 PoE2 偶像崇敬（Idolatry）：每個神像夥伴增加 10% 傷害、技能保留效率增加 2%，每個不是神像的嵌入物 -4% 全部元素抗性。
     * 還沒有神像：裝備上空著的符文孔當作神像，嵌著的符文算其他嵌入物。
     */
    static void idolatry(final ExileCore plugin, final StatSheet sheet, final List<Gear> gear) {
        final double damage = sheet.flat(AscKeys.IDOL_COMPANION_DAMAGE, Set.of());
        final double reservation = sheet.flat(AscKeys.IDOL_RESERVATION, Set.of());
        final double resist = sheet.flat(AscKeys.AUGMENT_RESIST, Set.of());
        if (damage == 0 && reservation == 0 && resist == 0) {
            return;
        }
        int idols = 0;
        int runes = 0;
        for (final Gear item : gear) {
            if (item.base() == null || item.data() == null) {
                continue;
            }
            final int slots = plugin != null && plugin.items() != null ? tw.exilecore.item.Runes.slots(plugin.items().repository(), item.base(), item.data())
                    : tw.exilecore.item.Runes.slots(item.base());
            final int used = Math.min(slots, item.data().runes().size());
            idols += Math.max(0, slots - used);
            runes += item.data().runes().size();
        }
        idolatryModifiers(sheet, idols, runes, damage, reservation, resist);
    }

    /** 同上（純計算，測試用）。 */
    static void idolatryModifiers(final StatSheet sheet, final int idols, final int runes, final double damage, final double reservation,
                                  final double resist) {
        if (idols > 0 && damage != 0) {
            sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, damage / 100.0 * idols,
                    Set.of(Tags.MINION, tw.exilecore.stats.TreeMechanicKeys.COMPANION), "偶像崇敬"));
        }
        if (idols > 0 && reservation != 0) {
            sheet.add(Modifier.increased(StatKeys.AURA_RESERVATION, -reservation / 100.0 * idols, "偶像崇敬")); // 保留效率增加 = 保留減少
        }
        if (runes > 0 && resist != 0) {
            for (final String stat : new String[] {StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD, StatKeys.RESIST_LIGHTNING}) {
                sheet.add(Modifier.flat(stat, resist * runes, "偶像崇敬"));
            }
        }
    }

    /** 石拳之道：腿甲（原作的手套）不需要能力值（PlayerStateService 檢查裝備需求時呼叫）。 */
    public static boolean ignoresRequirements(final List<Modifier> ascendancy, final ItemSlot slot) {
        return slot == ItemSlot.LEGS && flatOf(ascendancy, AscKeys.STONEFIST) > 0;
    }

    /** 能力值需求不看的時候拿來比的能力值（夠大就好）。 */
    public static final double[] NO_REQUIREMENT = {1e9, 1e9, 1e9};

    /**
     * 2026-10-27 照 PoE2 石拳之道（Way of the Stonefist）：裝備的手套變成石拳（Fists of Stone：固定詞綴每角色等級 +3 閃避、+1 能量護盾），
     * 詞綴換成更強的相關詞綴。原作的手套在這裡是腿甲；腿甲本身的防禦換成石拳的固定詞綴，詞綴（不含固定詞綴、符文）的數值增加 x%（50）。
     */
    static void stonefist(final StatSheet sheet, final PlayerState state, final List<Gear> gear) {
        final double boost = sheet.flat(AscKeys.STONEFIST, Set.of());
        if (boost <= 0) {
            return;
        }
        for (final Gear item : gear) {
            if (item.slot() != ItemSlot.LEGS || item.base() == null) {
                continue;
            }
            final String name = item.base().name();
            for (final Modifier mod : item.modifiers()) {
                final String source = mod.source() == null ? "" : mod.source();
                if (source.equals(name) && mod.type() == ModType.FLAT && mod.tags().isEmpty()
                        && (StatKeys.ARMOUR.equals(mod.stat()) || StatKeys.EVASION.equals(mod.stat()) || StatKeys.ES_MAX.equals(mod.stat()))) {
                    sheet.add(Modifier.flat(mod.stat(), -mod.value(), "石拳之道")); // 腿甲本身的防禦換掉
                } else if (source.startsWith(name + " ") && !source.contains("固定詞綴") && mod.type() != ModType.MORE
                        && !mod.stat().startsWith("flag.")) {
                    sheet.add(new Modifier(mod.stat(), mod.type(), mod.value() * boost / 100.0, mod.tags(), "石拳之道")); // 詞綴更強
                }
            }
            final int level = state == null ? 1 : Math.max(1, state.level());
            sheet.add(Modifier.flat(StatKeys.EVASION, 3.0 * level, "石拳之道"));
            sheet.add(Modifier.flat(StatKeys.ES_MAX, 1.0 * level, "石拳之道"));
        }
    }

    /** 2026-10-27 照 PoE2 符文子午線（Runic Meridians）：紋在身上的符文（角色進度 asc.meridians：頭、胸、胸、腿、腳，「|」分開）。 */
    public static final String MERIDIANS = "asc.meridians";
    /** 每一孔是哪個部位。 */
    public static final ItemSlot[] MERIDIAN_SLOTS = {ItemSlot.HEAD, ItemSlot.CHEST, ItemSlot.CHEST, ItemSlot.LEGS, ItemSlot.FEET};

    /** 角色進度裡紋的符文（5 孔，空的是 ""）。 */
    public static String[] meridianRunes(final PlayerState state) {
        final String[] out = {"", "", "", "", ""};
        final String value = state == null ? null : state.progress().get(MERIDIANS);
        if (value != null) {
            final String[] parts = value.split("\\|", -1);
            for (int i = 0; i < Math.min(out.length, parts.length); i++) {
                out[i] = parts[i].trim();
            }
        }
        return out;
    }

    /**
     * 符文子午線：紋在身上的符文照防具上的符文生效；護甲、閃避、能量護盾的增加（防具本地的）套用在那一格穿著的防具上
     * （那一格沒穿、那件沒有那種防禦就沒效果，跟嵌在防具上一樣）。
     */
    static void meridians(final ExileCore plugin, final StatSheet sheet, final PlayerState state, final List<Gear> gear) {
        if (sheet.flat(AscKeys.MERIDIANS, Set.of()) <= 0 || state == null || plugin == null || plugin.items() == null) {
            return;
        }
        final String[] runes = meridianRunes(state);
        for (int i = 0; i < runes.length; i++) {
            final tw.exilecore.item.Runes.Rune rune = runes[i].isEmpty() ? null : plugin.items().repository().rune(runes[i]);
            if (rune == null) {
                continue;
            }
            final ItemSlot slot = MERIDIAN_SLOTS[i];
            Gear worn = null;
            for (final Gear item : gear) {
                if (item.slot() == slot) {
                    worn = item;
                }
            }
            for (final tw.exilecore.item.Runes.Line line : rune.armour()) {
                for (final tw.exilecore.item.AffixMod affix : line.mods()) {
                    final Modifier mod = affix.toModifier(Map.of(), "符文子午線 " + rune.name());
                    final boolean defence = mod.type() == ModType.INCREASED && mod.tags().isEmpty()
                            && (StatKeys.ARMOUR.equals(mod.stat()) || StatKeys.EVASION.equals(mod.stat()) || StatKeys.ES_MAX.equals(mod.stat()));
                    if (!defence) {
                        sheet.add(mod);
                        continue;
                    }
                    if (worn == null || worn.base() == null) {
                        continue;
                    }
                    for (final Modifier own : worn.modifiers()) {
                        if (own.stat().equals(mod.stat()) && own.type() == ModType.FLAT && own.tags().isEmpty() && worn.base().name().equals(own.source())) {
                            sheet.add(Modifier.flat(mod.stat(), own.value() * mod.value(), mod.source()));
                        }
                    }
                }
            }
        }
    }

    private static void addMaxResist(final StatSheet sheet, final tw.exilecore.stats.DamageType type, final int amount) {
        if (amount > 0) {
            sheet.add(Modifier.flat(StatKeys.resistMax(type), amount, "奇術注入"));
        }
    }

    private static boolean hasStaff(final List<Gear> gear) {
        for (final Gear item : gear) {
            if (item.slot() == ItemSlot.MAIN_HAND && item.base() != null && item.base().tags().contains(Tags.STAFF)) {
                return true;
            }
        }
        return false;
    }

    /** 力量之器：持用長杖時可以裝備法器（Gear.pairProblem 呼叫）。 */
    public static boolean staffFocus(final ExileCore plugin, final PlayerState state, final ItemBase main, final ItemBase off) {
        return main != null && off != null && main.tags().contains(Tags.STAFF) && off.hasTag("focus")
                && ascFlat(plugin, state, AscKeys.STAFF_FOCUS) > 0;
    }

    /** 主手武器的命中值（穿透：附加物理傷害 = 武器命中值的 25%）。 */
    static double weaponAccuracy(final ExileCore plugin, final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        return sheet == null ? 0 : sheet.base(WEAPON_ACCURACY);
    }

    /**
     * 血紅之潮：生命藥劑充能全滿時，消耗 fraction 的充能；回傳消耗了幾點（沒滿、沒有生命藥劑 0）。
     */
    static int consumeFullLifeFlask(final ExileCore plugin, final Player player, final double fraction) {
        return plugin.flasks() == null ? 0 : plugin.flasks().consumeIfFull(player, tw.exilecore.flask.FlaskKind.LIFE, fraction);
    }

    /** 巴雅地脈：非傳奇的遺時珠寶半徑增加 x%（JewelSockets 呼叫）。 */
    public static double jewelRadiusScale(final ExileCore plugin, final PlayerState state, final ItemData jewel) {
        if (jewel == null || jewel.rarity() == Rarity.UNIQUE) {
            return 1;
        }
        final double increase = ascFlat(plugin, state, AscKeys.LEYLINES);
        return increase > 0 ? 1 + increase / 100.0 : 1;
    }

    /** 昇華賦予的技能（grant_skill.代號）：等級照角色等級能用的最高那一級（PlayerStateService 呼叫）。 */
    public static void grantedSkills(final ExileCore plugin, final List<Modifier> extra, final int level, final Map<String, Integer> granted) {
        if (plugin.skills() == null) {
            return;
        }
        for (final Modifier mod : extra) {
            if (mod.stat().startsWith(AscKeys.GRANT_SKILL) && mod.value() > 0) {
                final String id = mod.stat().substring(AscKeys.GRANT_SKILL.length());
                if (plugin.skills().config().skill(id) != null) {
                    granted.merge(id, plugin.skills().config().maxLevelFor(Math.max(1, level)), Math::max);
                }
            }
        }
    }
}
