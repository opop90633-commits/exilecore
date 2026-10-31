package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Corpses;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeEffectKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-29 照 PoE2：
 * <ul>
 *   <li>穢陣（Profane Ritual）要一具屍體：中心是屍體、撐到結束才用掉（品質 (0—10)% 機率不毀掉），同一具屍體同時只有一個穢陣；</li>
 *   <li>幻化武器（Manifest Weapon）用 PoE2 的召喚物表（召喚物等級 = 技能等級 × 2）、完全不會受傷，說明寫「幻化武器」，
 *       每 1 點力量增加 1% 傷害、每 3 點敏捷增加 1% 攻擊速度，品質 (0—20)% 增加召喚物攻擊速度。</li>
 * </ul>
 */
class ProfaneRitualManifestWeaponTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
    }

    private static SkillBuild build(final String id, final int level, final int quality) {
        final SkillDefinition definition = config.skill(id);
        return SkillBuild.of(config, definition, level, 90, false, List.of(), Set.of(), new StatSheet(), Set.of(),
                SkillService.qualityModifiers(definition, quality));
    }

    private static double sum(final List<Modifier> mods, final String stat, final ModType type) {
        return mods.stream().filter(m -> m.stat().equals(stat) && m.type() == type).mapToDouble(Modifier::value).sum();
    }

    // ---- 穢陣 ----

    @Test
    void profaneRitualNeedsACorpse() {
        final SkillBuild ritual = build("profane_ritual", 20, 0);
        assertTrue(ritual.params().zone().corpse(), "skills.yml 的 zone.corpse");
        assertTrue(SkillExecutor.needsCorpse(ritual), "花魔力之前就要檢查屍體（附近沒有屍體）");
        assertEquals(5, ritual.params().zone().max(), "照 PoE2 最多 5 個");
        assertTrue(config.skill("profane_ritual").description().contains("屍體"));
        // 其他地面效果照舊不用屍體
        for (final String id : List.of("flame_wall", "firestorm", "storm_orb")) {
            assertFalse(SkillExecutor.needsCorpse(build(id, 20, 0)), id);
        }
    }

    @Test
    void profaneRitualQualityKeepsTheCorpse() {
        // (0—10)% 機率不毀掉用掉的屍體：跟天賦樹、惜屍同一個鍵（corpse.save_chance）
        final List<Modifier> quality = SkillService.qualityModifiers(config.skill("profane_ritual"), 20);
        assertEquals(10, sum(quality, TreeEffectKeys.CORPSE_SAVE, ModType.FLAT), 1e-9);
        assertEquals("品質 +20%：儀式完成時 10% 機率不毀掉用掉的屍體", SkillService.qualityText(config.skill("profane_ritual"), 20));
        assertNotNull(GemQualityText.body("profane_ritual", GemQuality.modifiers("profane_ritual", 20, "q"), 20));
        // 額外的品質效果照舊：(0—100)% 更多技能持續時間
        assertEquals(1.0, sum(GemQuality.advancedModifiers("profane_ritual", 20, "q"), StatKeys.SKILL_DURATION, ModType.MORE), 1e-9);
        // 技能自己的屬性表讀得到（SkillZones.completed 用 TreeEffectKeys.corpseSaved 擲）
        final StatSheet effective = build("profane_ritual", 20, 20).profile().effective(new StatSheet());
        assertEquals(10, effective.flat(TreeEffectKeys.CORPSE_SAVE, Set.of()), 1e-9);
    }

    @Test
    void markedCorpseIsTakenAndCanBeRestored() {
        final Corpses corpses = new Corpses();
        final UUID world = UUID.randomUUID();
        corpses.add(world, 10, 64, 10, 500, 0, "ZOMBIE");
        final Corpses.Corpse corpse = corpses.nearest(world, 11, 10, 6, 0);
        assertNotNull(corpse);
        final Corpses.Contagion plague = new Corpses.Contagion(UUID.randomUUID(), 10, 1, 80, 3);
        assertTrue(corpses.markContagion(world, 10, 10, plague, 0));
        // 穢陣標住：從清單拿走，別的穢陣、骸爆找不到
        corpses.remove(corpse);
        assertNull(corpses.nearest(world, 10, 10, 6, 20));
        // 沒撐到結束（被擠掉）或不毀掉：放回去，原本的消失時間、帶著的瘟疫都在
        corpses.restore(corpse, plague, 40);
        final Corpses.Corpse back = corpses.nearest(world, 10, 10, 6, 40);
        assertSame(corpse, back);
        assertEquals(Corpses.LIFETIME, back.until());
        assertSame(plague, corpses.contagion(back));
        corpses.restore(corpse, plague, 40);
        assertEquals(1, corpses.size(), "不會放兩次");
        // 已經過期的不放回去
        corpses.remove(corpse);
        corpses.restore(corpse, null, Corpses.LIFETIME);
        assertEquals(0, corpses.size());
    }

    // ---- 幻化武器 ----

    @Test
    void manifestWeaponIsAnUndamageablePoe2Ally() {
        final SummonSpec spec = config.skill("manifest_weapon").summon();
        assertNotNull(spec);
        assertTrue(spec.ally(), "用 PoE2 的召喚物表");
        assertTrue(spec.immortal(), "MinionsAreUndamagable");
        assertTrue(spec.permanent());
        assertEquals("VEX", spec.kind(), "外觀照舊是惱鬼");
        assertEquals("幻形兵刃", spec.kindName(), "說明不再寫成焚魂");
        assertEquals("焚魂", config.skill("raging_spirits").summon().kindName(), "其他惱鬼照舊");
        assertEquals(1.0, spec.damage(), 1e-9, "Base Damage 100%");
        assertEquals(1.0, spec.life(), 1e-9, "不會受傷，不用再把生命放大 10 倍");
        assertTrue(spec.resists().isEmpty(), "不會受傷，不用再堆抗性");
        assertEquals(Set.of(Tags.ATTACK, Tags.MELEE), spec.minionTags(), "召喚物的技能標籤照舊");
        // 召喚物等級 = 技能等級 × 2（不再跟角色等級走）
        assertEquals(40, MinionSheets.displayLevel(spec, 20, 78));
        assertEquals(80, MinionSheets.displayLevel(spec, 40, 90));
        assertEquals(2, MinionSheets.displayLevel(spec, 1, 30));
        // 其他召喚物沒有 immortal
        assertFalse(config.skill("summon_skeletons").summon().immortal());
        assertFalse(config.skill("supporting_fire").summon().immortal(), "砲手照 MinionAbilities.COMMAND_ONLY");
    }

    @Test
    void manifestWeaponScalesWithStrengthAndDexterity() {
        final SkillBuild weapon = build("manifest_weapon", 20, 0);
        final StatSheet owner = new StatSheet();
        owner.setBase(StatKeys.STR, 150);
        owner.setBase(StatKeys.DEX, 90);
        final List<Modifier> derived = MinionEstimates.ownerDerived(weapon, owner, 1.0, true);
        assertEquals(1.5, sum(derived, StatKeys.DAMAGE, ModType.INCREASED), 1e-9, "每 1 點力量增加 1% 傷害");
        assertEquals(0.3, sum(derived, StatKeys.ATTACK_SPEED, ModType.INCREASED), 1e-9, "每 3 點敏捷增加 1% 攻擊速度");
        // 別的召喚物沒有
        assertTrue(MinionEstimates.ownerDerived(build("summon_skeletons", 20, 0), owner, 1.0, true).stream()
                .noneMatch(m -> m.stat().equals(StatKeys.ATTACK_SPEED)));
    }

    @Test
    void manifestWeaponQualityIsMinionAttackSpeed() {
        assertTrue(GemQuality.has("manifest_weapon"));
        final List<Modifier> quality = SkillService.qualityModifiers(config.skill("manifest_weapon"), 20);
        assertEquals(0.20, sum(quality, StatKeys.ATTACK_SPEED, ModType.INCREASED), 1e-9);
        assertEquals(0, sum(quality, StatKeys.DAMAGE, ModType.INCREASED), 1e-9, "不再照通用規則增加召喚物傷害");
        // 召喚技能自己的修飾器給召喚物（MinionSheets.profile 帶著 local）
        final SkillBuild weapon = build("manifest_weapon", 20, 20);
        assertEquals(0.20, sum(MinionSheets.profile(weapon).local(), StatKeys.ATTACK_SPEED, ModType.INCREASED), 1e-9);
    }
}
