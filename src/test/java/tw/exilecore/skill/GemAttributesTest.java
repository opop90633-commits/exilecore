package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeEffectKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26 照 PoE2 0.5：技能寶石的屬性需求、輔助寶石加的需求、需求不足不能用、輔助寶石的顏色（{@link GemAttributes}）。 */
class GemAttributesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static StatSheet sheet(final double str, final double dex, final double intelligence) {
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.STR, str);
        sheet.setBase(StatKeys.DEX, dex);
        sheet.setBase(StatKeys.INT, intelligence);
        return sheet;
    }

    @Test
    void requirementsMatchPoe2db() {
        // poe2db Fireball：1 級 4、10 級（需求等級 36）65、20 級（90）157 智慧
        assertEquals(new GemAttributes.Requirement(0, 0, 4), GemAttributes.base(config, config.skill("fireball"), 1));
        assertEquals(new GemAttributes.Requirement(0, 0, 65), GemAttributes.base(config, config.skill("fireball"), 10));
        assertEquals(new GemAttributes.Requirement(0, 0, 157), GemAttributes.base(config, config.skill("fireball"), 20));
        assertEquals(new GemAttributes.Requirement(0, 0, 157), GemAttributes.base(config, config.skill("fireball"), 21), "21 級照 20 級");
        // poe2db Armour Piercing Rounds（50／50）：20 級 86／86；Time of Need（75／25）：20 級 122 力量／48 智慧
        assertEquals(new GemAttributes.Requirement(86, 86, 0), GemAttributes.base(config, config.skill("explosive_bolt"), 20));
        assertEquals(new GemAttributes.Requirement(122, 0, 48), GemAttributes.base(config, config.skill("time_of_need"), 20));
        assertEquals(new GemAttributes.Requirement(37, 37, 0), GemAttributes.base(config, config.skill("explosive_bolt"), 10));
        assertEquals(new GemAttributes.Requirement(157, 0, 0), GemAttributes.base(config, config.skill("boneshatter"), 20));
    }

    @Test
    void everyVisibleGemHasAttributes() {
        final List<String> missing = new ArrayList<>();
        for (final SkillDefinition definition : config.all()) {
            if (!GemAttributes.isGem(definition)) { // 2026-10-26：武器、昇華賦予的不是寶石
                continue;
            }
            if (!GemAttributes.listed(definition.id())) {
                missing.add(definition.id());
            }
            assertFalse(GemAttributes.weights(definition).none(), definition.id());
            assertFalse(GemAttributes.base(config, definition, 20).isEmpty(), definition.id());
        }
        assertTrue(missing.isEmpty(), "沒寫屬性分配的技能：" + missing);
        // 德魯伊的變身技能是力量＋智慧（原作）；自創的專屬法術是智慧
        assertEquals(new GemAttributes.Weights(50, 0, 50), GemAttributes.weights(config.skill("pounce")));
        assertEquals(new GemAttributes.Weights(0, 0, 100), GemAttributes.weights(config.skill("void_meteor")));
    }

    @Test
    void everySupportHasAColourExceptWhiteOnes() {
        final List<String> white = new ArrayList<>();
        for (final SupportDefinition support : config.supports()) {
            if (SupportColours.colour(support.id()) == 0 && !support.lineage()) { // 2026-10-27：血脈輔助另外看（下面）
                white.add(support.id());
            }
        }
        // 2026-10-27 照 RePoE：白色的血脈輔助
        final List<String> whiteLineage = new ArrayList<>();
        for (final SupportDefinition support : config.supports()) {
            if (SupportColours.colour(support.id()) == 0 && support.lineage()) {
                whiteLineage.add(support.id());
            }
        }
        assertEquals(List.of("arbiters_reach", "breachlords_amalgam", "breachlords_rift", "cathas_brilliance", "eshs_prowess", "her_declaration",
                "medveds_felling", "morrigans_insight", "olroths_conviction", "olroths_hubris", "prototype_seventeen", "seraphs_heart",
                "styrns_ferocity", "styrns_mountain", "tangmazus_thurible", "tricksters_shard", "tuls_avalanche", "uhtreds_constellation",
                "uhtreds_rite", "voranas_siege", "vruuns_aftermath"), whiteLineage.stream().sorted().toList());
        // 原作是白色（沒有屬性）的只有這幾顆
        assertEquals(List.of("scouring_flame"), white.stream().sorted().toList()); // 2026-10-27：碎晶嘯算血脈（上面）；2026-10-28 照站長刪掉震符印、符刃
        assertEquals(SupportColours.RED, SupportColours.colour("urgent_totems_iii"), "疾樁是紅（RePoE）");
        assertEquals(SupportColours.BLUE, SupportColours.colour("ice_bite_ii"));
        assertEquals(SupportColours.GREEN, SupportColours.colour("deliberation"));
    }

    @Test
    void supportsAddFivePerColour() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 20, 4, List.of("faster_casting", "heft", "pierce", "vruuns_aftermath"));
        assertEquals(new GemAttributes.Requirement(5, 5, 162), GemAttributes.of(config, entry));
        assertEquals(GemAttributes.Requirement.NONE, GemAttributes.of(config, entry.withGranted(true)), "武器賦予的不是寶石，沒有需求");
    }

    @Test
    void unmetGemsCannotBeUsed() {
        final SkillLoadout loadout = new SkillLoadout();
        final int position = loadout.add(new SkillLoadout.Entry("fireball", 20, 2, List.of("faster_casting", "")));
        loadout.bind(0, position);
        // 2026-10-27：等級會自動降，只有連 1 級（4＋藍色輔助 5＝9 智慧）都不夠才不能用（這次之前 150 智慧就算不足）
        final StatSheet weak = sheet(20, 20, 8);
        final String why = GemAttributes.problem(config, loadout.skill(position), weak);
        assertEquals("智慧不足（需要 9，你有 8）", why);
        final Map<String, String> unmet = GemAttributes.unmet(config, loadout, weak);
        assertEquals(List.of("fireball"), loadout.syncUnmet(unmet));
        assertTrue(loadout.unmet(loadout.skill(position)));
        assertTrue(loadout.dormant(loadout.skill(position)), "光環、常駐跟沒拿武器的賦予技能一樣關掉");
        assertTrue(loadout.entriesInKeyOrder().isEmpty());
        assertSame(loadout.skill(position), loadout.boundSkill(0), "按鍵還是指著它（按下去提示缺什麼，不是變成普攻）");
        assertTrue(loadout.syncUnmet(GemAttributes.unmet(config, loadout, sheet(20, 20, 150))).isEmpty());
        assertFalse(loadout.unmet(loadout.skill(position)));
        assertNull(GemAttributes.problem(config, loadout.skill(position), sheet(20, 20, 150)), "150 智慧：降級使用");
        assertEquals(18, GemAttributes.usableLevel(config, loadout.skill(position), sheet(20, 20, 150)));
    }

    /** 2026-10-27 照 PoE2：主要寶石孔裡屬性不足的法術不觸發（宿主照樣能用，只用夠的那幾個）。 */
    @Test
    void unmetSocketedSpellsAreSkipped() {
        final SkillLoadout.Entry high = new SkillLoadout.Entry("fireball", 20, 2, List.of("", ""));
        final SkillLoadout.Entry low = new SkillLoadout.Entry("fireball", 1, 2, List.of("", ""));
        final SkillLoadout.Entry host = new SkillLoadout.Entry("meta_crit", 1, 3, List.of("", "", ""))
                .withSpell(0, high).withSpell(1, low);
        assertEquals(2, host.spells().size());
        // 2026-10-27：20 級火球智慧不夠時降級使用，連 1 級都不夠才不觸發
        final StatSheet weak = sheet(200, 200, 30);
        assertEquals(2, GemAttributes.usable(config, host.spells(), weak).size());
        assertTrue(GemAttributes.usableLevel(config, high, weak) < 20);
        final StatSheet tiny = sheet(200, 200, 3); // 連 1 級火球（4 智慧）都不夠
        assertTrue(GemAttributes.usable(config, host.spells(), tiny).isEmpty(), "連 1 級都不夠的不觸發");
        assertNotNull(GemAttributes.problem(config, host, tiny));
        assertEquals(2, GemAttributes.usable(config, host.spells(), sheet(200, 200, 200)).size());
        assertEquals(2, GemAttributes.usable(config, host.spells(), null).size(), "沒有屬性表時照舊");
    }

    @Test
    void reductionsAndHighestAttributeApply() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 20, 2, List.of("", ""));
        // 嵌晶戰團、天賦樹：減少 4% ×3 → 157 × 0.88 = 138
        final StatSheet reduced = sheet(0, 0, 138);
        reduced.add(Modifier.increased(TreeEffectKeys.ITEM_ATTR_REQUIREMENT, -0.12, "昇華"));
        assertEquals(20, GemAttributes.usableLevel(config, entry, reduced)); // 2026-10-27：改看能用的等級
        final StatSheet notEnough = sheet(0, 0, 137);
        notEnough.add(Modifier.increased(TreeEffectKeys.ITEM_ATTR_REQUIREMENT, -0.12, "昇華"));
        assertTrue(GemAttributes.usableLevel(config, entry, notEnough) < 20);
        // 應變能力：智慧需求可以用最高的力量滿足
        final StatSheet strong = sheet(160, 10, 10);
        assertTrue(GemAttributes.usableLevel(config, entry, strong) < 20);
        strong.add(Modifier.flat(GemAttributes.HIGHEST, 1, "昇華"));
        assertEquals(20, GemAttributes.usableLevel(config, entry, strong));
    }

    @Test
    void displayUsesMinecraftColours() {
        assertEquals("<red>【力量】", GemAttributes.supportBadge("heft"));
        assertEquals("<green>【敏捷】", GemAttributes.supportBadge("pierce"));
        assertEquals("<blue>【智慧】", GemAttributes.supportBadge("faster_casting"));
        assertEquals("<white>【無屬性】", GemAttributes.supportBadge("vruuns_aftermath"));
        assertTrue(GemAttributes.supportLine("heft").startsWith("<red>■ 力量輔助寶石"));
        assertEquals("<blue>", GemAttributes.skillTag(config.skill("fireball")));
        assertTrue(GemAttributes.skillTag(config.skill("explosive_bolt")).startsWith("<gradient:#FF5555:#55FF55"));
        assertTrue(GemAttributes.colourSkill(config.skill("explosive_bolt"), "x").endsWith("</gradient>"));
        assertEquals("<red>【<red>力量<gray>／<green>敏捷<green>】", GemAttributes.skillBadge(config.skill("explosive_bolt")));
        assertEquals("red_dye", GemAttributes.dyeModel(SupportColours.RED));
        final List<String> lines = GemAttributes.lines(config, new SkillLoadout.Entry("fireball", 20, 2, List.of("heft", "")), sheet(0, 0, 10));
        assertTrue(lines.get(0).contains("智慧"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("輔助寶石 +力量 5")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("不能使用")), lines.toString());
    }

    @Test
    void levelDropsToWhatAttributesAllow() {
        // 2026-10-27 照站長：火球 20 級要 157 智慧，只有 65 智慧時自動降到 10 級（10 級剛好 65）；還能用
        final SkillLoadout.Entry fireball = new SkillLoadout.Entry("fireball", 20, 0, List.of());
        final StatSheet weak = sheet(0, 0, 65);
        assertEquals(10, GemAttributes.usableLevel(config, fireball, weak));
        assertNull(GemAttributes.problem(config, fireball, weak));
        assertEquals(20, GemAttributes.usableLevel(config, fireball, sheet(0, 0, 200)));
        // 連 1 級（4 智慧）都不夠才不能用
        assertNotNull(GemAttributes.problem(config, fireball, sheet(0, 0, 3)));
        // 裝備的 +技能等級不算需求：降到 10 級後再加上去
        final StatSheet boosted = weak.overlay(List.of(Modifier.flat(StatKeys.SKILL_LEVEL, 2, "測試")));
        final SkillBuild build = SkillBuild.of(config, config.skill("fireball"), 20, 100, false, List.of(), java.util.Set.of(), boosted);
        assertEquals(12, build.level());
        // 2026-10-27：子技能、照寶石等級的效果用降過的等級（gemLevel 留原本的，需求等級的提示用）
        assertEquals(10, build.usableGemLevel());
        assertEquals(20, build.gemLevel());
    }

    /**
     * 2026-10-27：gem_colours.yml（昇華・嵌晶的寶石顏色）跟 GemAttributes（技能）、SupportColours（輔助）一致——
     * 這次之前 yml 照標籤猜，長棍技能是紅、架盾和獵殺印記和招架是藍，跟屬性需求的表不一樣。
     */
    @Test
    void gemColoursFileMatchesAttributeTables() throws IOException {
        final List<String> wrong = new ArrayList<>();
        String section = "";
        int skills = 0;
        for (final String line : java.nio.file.Files.readAllLines(Path.of("src/main/resources/data/gem_colours.yml"))) {
            if (line.startsWith("skills:") || line.startsWith("supports:")) {
                section = line.substring(0, line.indexOf(':'));
                continue;
            }
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^  ([a-z0-9_]+): ([rgb])\\s*$").matcher(line);
            if (!m.matches()) {
                continue;
            }
            final String id = m.group(1);
            final char colour = m.group(2).charAt(0);
            if (section.equals("skills")) {
                skills++;
                final int index = GemAttributes.colourIndex(id);
                if (index >= 0 && "rgb".charAt(index) != colour) {
                    wrong.add("技能 " + id + "：yml " + colour + "、GemAttributes " + "rgb".charAt(index));
                }
            } else if (section.equals("supports")) {
                final char listed = SupportColours.colour(id);
                if (listed != 0 && listed != colour) {
                    wrong.add("輔助 " + id + "：yml " + colour + "、SupportColours " + listed);
                }
            }
        }
        assertTrue(skills > 200, "gem_colours.yml 的 skills 讀到 " + skills + " 個");
        assertTrue(wrong.isEmpty(), "gem_colours.yml 跟程式的顏色表不一樣（重跑 tools/gen_gem_colours.py）：" + wrong);
        // 照 RePoE 的屬性分配：長棍是敏捷／智慧（主要算綠）、架盾是力量、獵殺印記（Sniper's Mark）與招架是敏捷
        assertEquals(1, GemAttributes.colourIndex("tempest_flurry"));
        assertEquals(0, GemAttributes.colourIndex("raise_guard"));
        assertEquals(1, GemAttributes.colourIndex("hunters_mark"));
        assertEquals(1, GemAttributes.colourIndex("parry"));
        assertEquals(-1, GemAttributes.colourIndex("no_such_skill"));
    }
}
