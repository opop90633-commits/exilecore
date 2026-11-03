package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03 照站長：主人身上「增加 x% 召喚物暴擊率」（珠寶 j2_minion_critical_strike_chance_increase、權杖 minion_crit）
 * 這類 [minion] 的修飾器，召喚物的屬性表（召喚物說明、/stats、MinionService 戰鬥都用 MinionSheets.build）吃得到。
 */
class MinionCritTooltipTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static MonsterConfig monsters;
    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
        repository = new ItemRepository();
        repository.load(DATA);
    }

    /** 一顆藍寶石珠寶，帶這條詞綴的修飾器（照物品資料算，跟天賦樹珠寶孔 JewelSockets 一樣）。 */
    private static List<Modifier> jewel(final String affixId) {
        final Affix affix = repository.affix(affixId);
        final ItemBase base = repository.bases().stream()
                .filter(b -> b.slot() == ItemSlot.JEWEL && b.tags().contains("sapphire") && !b.isRadiusJewel()).findFirst().orElseThrow();
        final ItemData data = new ItemData(base.id(), Rarity.MAGIC, 80);
        data.affixes().add(new RolledAffix(affix.id(), affix.tiers().getFirst().tier(), affix.tiers().getFirst().roll(new Random(7))));
        final List<Modifier> mods = ItemStats.modifiers(repository, data);
        assertTrue(mods.stream().allMatch(mod -> mod.tags().contains(Tags.MINION)), "珠寶的召喚物詞綴帶 minion 標籤：" + mods);
        return mods;
    }

    /** 跟 SkillService.summonLines／minionDps、MinionService.rebuild 同一套：主人的 [minion] 修飾器 → 召喚物屬性表。 */
    private static StatSheet minionSheet(final SkillBuild build, final StatSheet owner, final boolean inPresence) {
        final SummonSpec spec = build.definition().summon();
        final MinionSheets.Base base = MinionSheets.base(config, spec, build.level(), 80, monsters::lifeAt, monsters::damageAt,
                monsters::accuracyAt, monsters::evasionAt, monsters::armourAt, monsters.critChance());
        final List<Modifier> mods = new ArrayList<>(MinionSheets.forMinion(MinionSheets.ownerModifiers(owner),
                MinionSheets.conditions(build.definition(), inPresence)));
        return MinionSheets.build(spec, base, mods);
    }

    private static SkillBuild skeletons(final StatSheet owner) {
        return SkillBuild.of(config, config.skill("summon_skeletons"), 20, 80, false, List.of(), Set.of(), owner);
    }

    @Test
    void jewelMinionCritRaisesMinionCritChance() {
        final StatSheet plain = new StatSheet();
        final List<Modifier> mods = jewel("j2_minion_critical_strike_chance_increase");
        final double rolled = mods.getFirst().value();
        final StatSheet withJewel = new StatSheet().addAll(mods);
        for (final boolean inPresence : new boolean[] {true, false}) { // 說明／每秒傷害當作在範圍內；戰鬥照距離
            final SkillBuild build = skeletons(plain);
            final SkillProfile profile = MinionSheets.profile(build);
            final double before = DamageCalculator.critChance(minionSheet(build, plain, inPresence), profile);
            final double after = DamageCalculator.critChance(minionSheet(skeletons(withJewel), withJewel, inPresence), profile);
            assertTrue(after > before, "帶珠寶的召喚物暴擊率要比較高：" + before + " → " + after);
            assertEquals(before * (1 + rolled), after, 1e-9, "增加 x% 召喚物暴擊率照擲出來的值");
        }
    }

    @Test
    void sceptreMinionCritRaisesMinionCritChance() {
        final Affix affix = repository.affix("minion_crit");
        final Modifier mod = affix.mods().getFirst().toModifier(java.util.Map.of(affix.mods().getFirst().variable(), 30), "權杖");
        final StatSheet owner = new StatSheet().add(mod);
        final SkillProfile profile = MinionSheets.profile(skeletons(owner));
        final double before = DamageCalculator.critChance(minionSheet(skeletons(new StatSheet()), new StatSheet(), true), profile);
        final double after = DamageCalculator.critChance(minionSheet(skeletons(owner), owner, true), profile);
        assertEquals(before * 1.30, after, 1e-9);
    }

    @Test
    void jewelMinionCritBonusSpeedAndDamageApply() {
        final SkillBuild build = skeletons(new StatSheet());
        final SkillProfile profile = MinionSheets.profile(build);
        final StatSheet plainMinion = minionSheet(build, new StatSheet(), true);
        // 增加 x% 召喚物暴擊傷害加成
        final List<Modifier> bonus = jewel("j2_minion_critical_strike_multiplier");
        final StatSheet bonusOwner = new StatSheet().addAll(bonus);
        final double multiBefore = DamageCalculator.critMultiplier(profile.effective(plainMinion), profile.tags(), null);
        final double multiAfter = DamageCalculator.critMultiplier(profile.effective(minionSheet(skeletons(bonusOwner), bonusOwner, true)), profile.tags(), null);
        assertEquals(1 + (multiBefore - 1) * (1 + bonus.getFirst().value()), multiAfter, 1e-9, "暴擊傷害加成 +70% 再增加 x%");
        // 增加 x% 召喚物攻擊速度
        final StatSheet speedOwner = new StatSheet().addAll(jewel("j2_minion_attack_speed_and_cast_speed"));
        assertTrue(MinionSheets.attacksPerSecond(profile.effective(minionSheet(skeletons(speedOwner), speedOwner, true)), profile)
                > MinionSheets.attacksPerSecond(profile.effective(plainMinion), profile));
        // 增加 x% 召喚物傷害
        final StatSheet damageOwner = new StatSheet().addAll(jewel("j2_minion_damage"));
        assertTrue(DamageCalculator.expectedOutgoing(minionSheet(skeletons(damageOwner), damageOwner, true), profile).total()
                > DamageCalculator.expectedOutgoing(plainMinion, profile).total());
    }

    @Test
    void ownerOwnCritDoesNotReachMinions() {
        final StatSheet owner = new StatSheet().add(Modifier.increased(tw.exilecore.stats.StatKeys.CRIT_CHANCE, 1.0, "主人自己的"));
        final SkillProfile profile = MinionSheets.profile(skeletons(owner));
        assertEquals(DamageCalculator.critChance(minionSheet(skeletons(new StatSheet()), new StatSheet(), true), profile),
                DamageCalculator.critChance(minionSheet(skeletons(owner), owner, true), profile), 1e-9, "沒有 minion 標籤的不給召喚物");
    }
}
