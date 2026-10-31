package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.PlayerStateService;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Runes;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.stats.CostKeys;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照 PoE2 的消耗效率：消耗 ＝ 基礎 ×（1 ＋ 增加消耗）× 更多消耗 ÷（1 ＋ 消耗效率）。
 * 這次之前「增加 x% 消耗效率」近似成 skill.cost 減少 x%。
 */
class CostEfficiencyTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        repository = new ItemRepository();
        repository.load(DATA);
    }

    @Test
    void efficiencyDividesTheCost() {
        assertEquals(20, CostKeys.manaCost(30, 1.0, 0.5), "基礎 30、效率 +50% → 30 ÷ 1.5 = 20");
        assertEquals(19, CostKeys.manaCost(30, 0.8, 0.25), "減少 20% 消耗、效率 +25% → 30 × 0.8 ÷ 1.25 = 19.2 → 19");
        assertEquals(15, CostKeys.manaCost(30, 1.0, 1.0), "效率 +100% → 一半（不會像減少 100% 那樣變成 0）");
        assertEquals(40, CostKeys.manaCost(30, 1.0, -0.25), "效率也可以是負的：÷ 0.75");
        assertEquals(300, CostKeys.manaCost(30, 1.0, -5), "效率最低算到 −90%（最多 10 倍），不會除以 0");
    }

    @Test
    void manaEfficiencyAndCostEfficiencyAddUpAndFollowTags() {
        final Set<String> attack = Set.of(Tags.ATTACK);
        final Set<String> spell = Set.of(Tags.SPELL);
        final StatSheet sheet = new StatSheet()
                .add(Modifier.increased(StatKeys.SKILL_COST, -0.2, "測試"))
                .add(Modifier.increased(CostKeys.MANA_COST_EFFICIENCY, 0.1, "測試"))
                .add(new Modifier(CostKeys.COST_EFFICIENCY, ModType.INCREASED, 0.15, attack, "測試"));
        assertEquals(0.25, CostKeys.manaEfficiency(sheet, attack), 1e-9, "攻擊：10% 魔力效率 + 15% 攻擊的消耗效率");
        assertEquals(0.1, CostKeys.manaEfficiency(sheet, spell), 1e-9, "法術只有魔力效率");
        assertEquals(19, CostKeys.manaCost(30, sheet.apply(StatKeys.SKILL_COST, 1.0, attack), CostKeys.manaEfficiency(sheet, attack)));
        assertEquals(0.15, CostKeys.lifeEfficiency(sheet, attack), 1e-9, "生命消耗不吃魔力消耗效率");
    }

    @Test
    void skillBuildUsesTheDivisor() {
        final SkillDefinition fireball = config.skill("fireball");
        final StatSheet plainSheet = new StatSheet().setBase(StatKeys.MANA_MAX, 1000);
        final int plain = SkillBuild.of(config, fireball, 20, 90, false, List.of(), Set.of(), plainSheet).manaCost();
        assertTrue(plain > 0);
        final StatSheet efficient = new StatSheet().setBase(StatKeys.MANA_MAX, 1000)
                .add(Modifier.increased(CostKeys.MANA_COST_EFFICIENCY, 0.5, "測試"));
        assertEquals(Math.round(fireball.mana().at(20) * SkillConfig.manaCostScale() / 1.5),
                SkillBuild.of(config, fireball, 20, 90, false, List.of(), Set.of(), efficient).manaCost(), "效率 50% → 2/3");
        final StatSheet both = new StatSheet().setBase(StatKeys.MANA_MAX, 1000)
                .add(Modifier.increased(StatKeys.SKILL_COST, -0.2, "測試"))
                .add(Modifier.increased(CostKeys.COST_EFFICIENCY, 0.25, "測試"));
        assertEquals(Math.round(fireball.mana().at(20) * SkillConfig.manaCostScale() * 0.8 / 1.25),
                SkillBuild.of(config, fireball, 20, 90, false, List.of(), Set.of(), both).manaCost());
        final StatSheet attackOnly = new StatSheet().setBase(StatKeys.MANA_MAX, 1000)
                .add(new Modifier(CostKeys.COST_EFFICIENCY, ModType.INCREASED, 1.0, Set.of(Tags.ATTACK), "測試"));
        assertEquals(plain, SkillBuild.of(config, fireball, 20, 90, false, List.of(), Set.of(), attackOnly).manaCost(), "攻擊的消耗效率不管法術");
    }

    @Test
    void lifeCostsUseLifeEfficiencyNotManaEfficiency() {
        final StatSheet sheet = new StatSheet().add(Modifier.increased(CostKeys.MANA_COST_EFFICIENCY, 0.25, "測試"));
        // 魔力消耗 20 已經除過 1.25（原本 25）；改扣生命的那份不吃魔力效率 → 生命 25
        assertArrayEquals(new int[] {25, 0}, SkillService.lifeCostSplit(20, true, sheet, Set.of(Tags.SPELL)), "血魔法：魔力消耗效率不算");
        final StatSheet all = new StatSheet().add(Modifier.increased(CostKeys.COST_EFFICIENCY, 0.25, "測試"));
        assertArrayEquals(new int[] {20, 0}, SkillService.lifeCostSplit(20, true, all, Set.of(Tags.SPELL)), "消耗效率生命也吃");
        final StatSheet life = new StatSheet().add(Modifier.increased(CostKeys.LIFE_COST_EFFICIENCY, 1.0, "測試"));
        assertArrayEquals(new int[] {10, 0}, SkillService.lifeCostSplit(20, true, life, Set.of(Tags.SPELL)), "生命消耗效率 100% → 一半");
    }

    @Test
    void textShowsEfficiency() {
        assertEquals("增加 16% 魔力消耗效率", ModifierText.describe(Modifier.increased(CostKeys.MANA_COST_EFFICIENCY, 0.16, "測試")));
        assertEquals("減少 30% 消耗效率", ModifierText.describe(Modifier.increased(CostKeys.COST_EFFICIENCY, -0.3, "測試")));
        assertEquals("增加 5% 消耗效率", tw.exilecore.ascendancy.StatLines.describe(Modifier.increased(CostKeys.COST_EFFICIENCY, 0.05, "測試")));
        assertTrue(tw.exilecore.stats.Glossary.terms().get("消耗效率").contains("÷"), "名詞解釋照 PoE2 的公式");
    }

    @Test
    void sourcesUseTheEfficiencyKeys() throws IOException {
        // 天賦樹：所有寫「消耗效率」的行都是效率的鍵，沒有一條還是 skill.cost 的減少
        final PassiveTree tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
        int efficiencyNodes = 0;
        for (final PassiveNode node : tree.nodes()) {
            final boolean says = String.join("", node.description()).contains("消耗效率");
            boolean usesKey = false;
            for (final Modifier modifier : node.modifiers()) {
                final String stat = tw.exilecore.stats.Recently.stat(modifier.stat());
                usesKey |= stat.equals(CostKeys.COST_EFFICIENCY) || stat.equals(CostKeys.MANA_COST_EFFICIENCY);
                if (says) {
                    assertFalse(stat.equals(StatKeys.SKILL_COST) && modifier.value() < 0, node.id() + " 還是 skill.cost 的減少");
                }
                if (usesKey) {
                    assertTrue(modifier.value() > 0 || !stat.contains("efficiency"), node.id() + "：效率是正的");
                }
            }
            if (says && usesKey) {
                efficiencyNodes++;
            }
        }
        assertTrue(efficiencyNodes >= 18, "天賦樹有 18 個消耗效率的節點：" + efficiencyNodes);
        final String timeless = Files.readString(DATA.resolve("passives/timeless.json"), StandardCharsets.UTF_8);
        assertTrue(timeless.contains("\"skill.mana_cost_efficiency\""), "恆時珠寶：每 10 點貢禮增加 2% 魔力消耗效率");
        // 符文（泉思符文的羈絆詞綴）
        final Runes.Rune inspiration = repository.rune("inspiration");
        boolean rune = false;
        for (final Runes.Line line : inspiration.bondedCaster()) {
            for (final AffixMod mod : line.mods()) {
                rune |= mod.stat().equals(CostKeys.MANA_COST_EFFICIENCY) && mod.type() == ModType.INCREASED;
            }
        }
        assertTrue(rune, "泉思符文：增加 16% 魔力消耗效率");
        // 詞綴：完美施速星魄、深淵詞綴、珠寶
        assertEquals(CostKeys.MANA_COST_EFFICIENCY, repository.affix("ess_cost_wand").mods().getFirst().stat());
        assertEquals(CostKeys.MANA_COST_EFFICIENCY, repository.affix("ess_cost_staff").mods().getFirst().stat());
        assertEquals(CostKeys.MANA_COST_EFFICIENCY, repository.affix("unveiled_d_cost").mods().getFirst().stat());
        assertEquals(CostKeys.COST_EFFICIENCY, repository.affix("unveiled_d_attack_cost").mods().getFirst().stat());
        assertEquals(CostKeys.MANA_COST_EFFICIENCY + "@dodge", repository.affix("unveiled_d_boots_cost_dodge").mods().getFirst().stat());
        assertEquals(StatKeys.RADIUS_PREFIX + CostKeys.MANA_COST_EFFICIENCY, repository.affix("unveiled_jr_mana_cost_efficiency").mods().getFirst().stat());
        // 旅者的智慧：屬性點改給消耗效率
        final List<Modifier> wisdom = PlayerStateService.choiceModifiers("cost", 5, Set.of(), "測試");
        assertEquals(CostKeys.COST_EFFICIENCY, wisdom.getFirst().stat());
        assertEquals(0.05, wisdom.getFirst().value(), 1e-9);
    }
}
