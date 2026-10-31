package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.PinKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeConditions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 的兩座弩台（poe2db Siege Ballista、Artillery Ballista）。 */
class BallistaPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static final Set<String> CROSSBOW = Set.of(Tags.CROSSBOW, Tags.RANGED, Tags.TWO_HAND);

    private static SkillBuild build(final String skill, final int level, final StatSheet sheet) {
        return SkillBuild.of(config, config.skill(skill), level, 90, false, List.of(), CROSSBOW, sheet);
    }

    private static SkillBuild withQuality(final String skill, final int quality, final boolean advanced) {
        final SkillDefinition definition = config.skill(skill);
        final List<Modifier> mods = SkillService.qualityModifiers(definition, quality);
        return SkillBuild.of(config, definition, 20, 90, false, List.of(), CROSSBOW, new StatSheet(), Set.of(),
                advanced ? tw.exilecore.ascendancy.AscBuild.advanced(skill, quality, mods) : mods);
    }

    @Test
    void siegeBallistaFiresThreeBoltsThatLandThenExplode() {
        final SkillBuild siege = build("siege_ballista", 20, new StatSheet());
        assertEquals(3, siege.projectiles(), "每次攻擊 3 發弩彈");
        assertEquals(tw.exilecore.core.Units.metres(0.5), siege.radius(), 1e-9, "落地 0.5 公尺"); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(0.66, siege.profile().effectiveness(), 1e-9, "落地 66%");
        assertEquals(0.6, siege.profile().conversion().get(DamageType.FIRE), 1e-9, "落地 60% 轉火焰");
        assertEquals(0.25, build("siege_ballista", 1, new StatSheet()).profile().effectiveness(), 1e-9);
        assertEquals(1.27, config.skill("siege_ballista").effectiveness().at(40), 1e-9);
        final SkillConfig.CascadeSpec cascade = config.cascade("siege_ballista");
        assertNotNull(cascade);
        assertEquals(tw.exilecore.core.Units.metres(1.4), cascade.radius(), 1e-9, "爆炸 1.4 公尺");
        assertEquals(0.5, cascade.effectiveness().at(1), 1e-9);
        assertEquals(1.32, cascade.effectiveness().at(20), 1e-9);
        assertEquals(2.53, cascade.effectiveness().at(40), 1e-9);
        final SkillProfile blast = SkillExecutor.cascadeProfile(siege.profile(), cascade, 20);
        assertEquals(1.32, blast.effectiveness(), 1e-9);
        assertEquals(1.0, blast.conversion().get(DamageType.FIRE), 1e-9, "爆炸 100% 轉火焰");
        assertEquals(siege.profile().local(), blast.local(), "輔助照樣吃");
        assertEquals(siege.profile().tags(), blast.tags());
        assertNull(config.cascade("artillery_ballista"));
        final StatSheet more = new StatSheet().add(Modifier.flat(StatKeys.SKILL_PROJECTILES, 1, "測試"));
        assertEquals(4, build("siege_ballista", 20, more).projectiles(), "多一發投射物＝多一發弩彈");
    }

    @Test
    void artilleryBallistaRainsFourHitsPerSalvo() {
        final SkillBuild artillery = build("artillery_ballista", 20, new StatSheet());
        assertEquals(4, artillery.projectiles(), "一輪 4 擊");
        assertEquals(tw.exilecore.core.Units.metres(2.5), artillery.radius(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(0.4, artillery.profile().effectiveness(), 1e-9);
        assertTrue(artillery.profile().conversion().isEmpty());
        final StatSheet more = new StatSheet().add(Modifier.flat(StatKeys.SKILL_PROJECTILES, 2, "測試"));
        assertEquals(6, build("artillery_ballista", 20, more).projectiles(), "投射物數量加在每輪的擊中次數上");
    }

    @Test
    void ballistasAttackAtAQuarterOfWeaponSpeed() {
        for (final String id : List.of("siege_ballista", "artillery_ballista")) {
            final SkillDefinition definition = config.skill(id);
            assertEquals(0.25, definition.attackSpeed(), 1e-9, id);
            assertTrue(definition.deploy().weaponSpeed(), id);
            final StatSheet sheet = new StatSheet();
            sheet.setBase(StatKeys.WEAPON_APS, 1.6);
            assertEquals(50, SkillDeployables.totemTicks(build(id, 20, sheet), sheet, 0), id + "：1.6 × 25% = 0.4 次／秒");
        }
        assertEquals(8, config.skill("siege_ballista").deploy().duration(), 1e-9);
        assertEquals(12, config.skill("artillery_ballista").deploy().duration(), 1e-9);
    }

    @Test
    void tagsFollowTheGems() {
        final SkillDefinition siege = config.skill("siege_ballista");
        assertTrue(siege.tags().containsAll(Set.of(Tags.ATTACK, Tags.TOTEM, Tags.AREA, Tags.PROJECTILE, Tags.FIRE, Tags.DURATION)));
        assertEquals(9, siege.tier());
        final SkillDefinition artillery = config.skill("artillery_ballista");
        assertTrue(artillery.tags().containsAll(Set.of(Tags.ATTACK, Tags.TOTEM, Tags.AREA, Tags.PROJECTILE, Tags.DURATION)));
        assertEquals(7, artillery.tier());
    }

    @Test
    void artilleryHitsArePinningAsThoughSevenTimesTheDamage() {
        final SkillBuild artillery = build("artillery_ballista", 20, new StatSheet());
        final StatSheet effective = artillery.profile().effective(new StatSheet());
        final Set<String> tags = artillery.profile().tags();
        assertTrue(StatKeys.flag(effective, PinKeys.FLAG_HITS_PIN));
        assertEquals(0, effective.flat(StatKeys.PIN_CHANCE, tags), 1e-9, "拿掉 pin.chance 30 的頂替");
        assertEquals(7, PinKeys.asThoughMultiplier(effective.flat(PinKeys.PIN_AS_THOUGH, tags)), 1e-9, "更多 600%");
        assertEquals(100, effective.flat(StatKeys.MAIM_CHANCE, tags), 1e-9, "擊中癱瘓");
        final SkillBuild quality = withQuality("artillery_ballista", 20, false);
        assertEquals(9, PinKeys.asThoughMultiplier(quality.profile().effective(new StatSheet()).flat(PinKeys.PIN_AS_THOUGH, tags)), 1e-9,
                "20% 品質：600% + 200% = 800%");
        final DamagePacket packet = new DamagePacket();
        packet.set(DamageType.PHYSICAL, 40);
        packet.set(DamageType.FIRE, 60);
        assertEquals(100, PinKeys.pinDamage(true, packet), 1e-9, "擊中必定定身：全部傷害都算");
        assertEquals(40, PinKeys.pinDamage(false, packet), 1e-9, "一般的只算物理");
    }

    @Test
    void siegeHasNoExtraDamageVsImmobilised() { // 2026-10-28 站長：拿掉 0.4.0 才有的「對被禁錮的敵人更多 25%」
        final SkillBuild siege = build("siege_ballista", 20, new StatSheet());
        final StatSheet effective = siege.profile().effective(new StatSheet());
        final Set<String> tags = new HashSet<>(siege.profile().tags());
        assertEquals(1.0, effective.more(StatKeys.DAMAGE, tags), 1e-9);
        tags.add(TreeConditions.VS_IMMOBILISED);
        assertEquals(1.0, effective.more(StatKeys.DAMAGE, tags), 1e-9, "對被禁錮的敵人沒有額外加成");
    }

    @Test
    void qualityFollowsPoe2() {
        final SkillBuild plain = build("siege_ballista", 20, new StatSheet());
        final SkillBuild siege = withQuality("siege_ballista", 20, false);
        assertEquals(Math.sqrt(1.2), siege.areaScale() / plain.areaScale(), 1e-9, "20% 品質：更多 20% 範圍");
        assertEquals(1, siege.totems());
        assertEquals(2, withQuality("siege_ballista", 20, true).totems(), "高階奇術：+1 圖騰上限");
        assertEquals(2, withQuality("artillery_ballista", 20, true).totems(), "高階奇術：+1 圖騰上限");
    }
}
