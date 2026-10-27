package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26 照 PoE2：連擊、榮耀、灌注接回技能（這次之前用冷卻代替）。 */
class SkillResourceHookTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void tempestBellNeedsFourCombo() {
        assertEquals(4, config.resource("tempest_bell").combo());
        assertEquals(0.5, config.skill("tempest_bell").cooldown(), 1e-9, "照 PoE2 冷卻 0.5 秒（這次之前用 3 秒代替連擊）");
    }

    @Test
    void bannersNeedGloryFromAttackHits() {
        for (final String id : List.of("defiance_banner", "war_banner", "dread_banner")) {
            assertEquals(0, config.skill(id).cooldown(), 1e-9, id);
            assertEquals(200, config.skill(id).warcry().extras().glory(), id);
            assertEquals("attack_hit", config.resource(id).gloryFrom(), id);
        }
    }

    @Test
    void glorySpellsHaveNoCooldown() {
        assertEquals(20, config.resource("sunfire_spear").glory());
        assertEquals("ailment", config.resource("sunfire_spear").gloryFrom());
        assertEquals(0, config.skill("sunfire_spear").cooldown(), 1e-9);
        assertEquals(20, config.resource("hammer_of_gods").glory());
        assertEquals("heavy_stun", config.resource("hammer_of_gods").gloryFrom());
        assertEquals(0, config.skill("hammer_of_gods").cooldown(), 1e-9);
    }

    @Test
    void triggeredLifeDamageFollowsPoe2() {
        // 2026-10-26 照 PoE2：觸發技能的傷害照敵人最大生命
        assertEquals(10, config.lifeDamage("proc_fiery_death"), 1e-9);
        assertEquals(10, config.lifeDamage("proc_frozen_spite"), 1e-9);
        assertEquals(25, config.lifeDamage("proc_bone_burst"), 1e-9);
        assertEquals(0, config.lifeDamage("fireball"), 1e-9);
        // 燼使：每秒燒過量傷害的 16.67%（1 級）→ 23%（20 級）→ 29.67%（40 級）
        assertEquals(16.667, AuraService.ashPercent(1), 1e-3);
        assertEquals(23.0, AuraService.ashPercent(20), 1e-9);
        assertEquals(29.667, AuraService.ashPercent(40), 1e-3);
    }

    @Test
    void infusionsChangeTheCast() {
        assertEquals(List.of("lightning"), config.resource("arc").infusion());
        assertEquals(List.of("fire"), config.resource("comet").infusion());
        final List<Modifier> infused = config.resource("arc").infused();
        assertTrue(infused.stream().anyMatch(m -> m.stat().equals(StatKeys.SKILL_CHAINS) && m.value() == 6));
        assertTrue(config.resource("comet").infused().stream().anyMatch(m -> m.stat().equals(StatKeys.SKILL_AREA) && Math.abs(m.value() - 0.47) < 1e-9),
                "INCREASED 47% 讀成 0.47");
        // 2026-10-26：damage.gain 只算物理（隕星沒有物理）→ 改成 damage.extra
        assertTrue(config.resource("comet").infused().stream().anyMatch(m -> m.stat().equals("damage.extra.fire") && m.value() == 100));
    }

    @Test
    void infusionRulesFollowPoe2() {
        // 2026-10-26 照 PoE2（關鍵字 Elemental Infusions）：每種最多 3 層、撐 20 秒
        assertEquals(3, SkillResources.INFUSION_BASE_MAX);
        assertEquals(400, SkillResources.INFUSION_DURATION);
        // 會用灌注的原作技能都接上了
        for (final String id : List.of("fireball", "ball_lightning", "ember_volley", "frost_darts", "ice_wall", "frost_nova", "spark", "flame_stream")) {
            assertEquals(1, config.resource(id).infusion().size(), id);
            assertTrue(!config.resource(id).infused().isEmpty(), id);
        }
        // 流焰天幕：三種各用一層、各有自己的加成
        final ResourceSpec firestorm = config.resource("firestorm");
        assertTrue(firestorm.infusionEach());
        assertEquals(List.of("fire", "cold", "lightning"), firestorm.infusion());
        assertTrue(firestorm.infusedFor("cold").stream().anyMatch(m -> m.stat().equals("damage.extra.cold")));
        assertTrue(firestorm.infusedFor("fire").stream().anyMatch(m -> m.stat().equals(StatKeys.SKILL_AREA)));
        // 留下灌注殘骸的技能
        assertEquals("cold", config.resource("frost_mine").remnant());
        assertEquals(4, config.resource("frost_mine").remnantDelay(), 1e-9);
        // 2026-10-26：燼種的火焰殘骸改在火種爆開的地方由 OriginalSkills 掉（爆開時間不固定），不寫 resource.remnant
        assertEquals(null, config.resource("ember_seed").remnant());
        assertEquals("lightning", config.resource("storm_orb").remnant());
        // 2026-10-27 照 PoE2 Snap：殘骸改由 Snap.java 照用掉的異常留下那種元素的（這次之前 resource.remnant: random）
        assertEquals(null, config.resource("snap").remnant());
        assertEquals(1, SkillResources.infusionIndex("cold"));
    }

    @Test
    void surgesTriggerASeparateBlast() {
        // 2026-10-27 照 PoE2 亞馬遜「元素湧動」：每一發用掉湧動的投射物飛行結束時另外觸發一發湧動爆炸（照用掉的元素排好）；
        // 這次之前是投射物自己獲得「用掉的層數 ÷ 投射物數 × 100%」額外元素傷害
        assertEquals(List.of(0, 2, 2), SkillResources.surgeOrder(new int[] {1, 0, 2}));
        assertEquals(List.of(), SkillResources.surgeOrder(new int[] {0, 0, 0}));
        final List<Modifier> marker = SkillResources.surgeMarker();
        assertEquals(SkillResources.SURGE_BLAST, marker.getFirst().stat());
        for (final String element : List.of("fire", "cold", "lightning")) {
            final SkillDefinition blast = config.skill("asc_surge_" + element);
            assertNotNull(blast, element);
            assertTrue(blast.hidden() && blast.isAttack(), element);
            assertEquals(1.8, blast.params().radius().at(1), 1e-9, element + "：1.8 格");
        }
        final SkillBuild fire1 = SkillBuild.of(config, config.skill("asc_surge_fire"), 1, 1, false, List.of(), java.util.Set.of(), new tw.exilecore.stats.StatSheet());
        final SkillBuild fire20 = SkillBuild.of(config, config.skill("asc_surge_fire"), 20, 90, false, List.of(), java.util.Set.of(), new tw.exilecore.stats.StatSheet());
        assertEquals(0.5, fire1.profile().effectiveness(), 1e-9, "1 級 50%");
        assertEquals(1.81, fire20.profile().effectiveness(), 1e-9, "20 級 181%");
    }

    @Test
    void mountFollowsRhoaMount() {
        // 2026-10-26 照 PoE2 Rhoa Mount：拿弓或長矛才能騎；騎著只能放弓、投擲長矛與印記
        assertEquals(java.util.Set.of("bow", "spear"), config.skill("summon_mount").weapons());
        assertTrue(Mounts.mountWeapon(java.util.Set.of("bow")));
        assertTrue(!Mounts.mountWeapon(java.util.Set.of("mace")));
        assertTrue(!Mounts.usableWhileMounted(config.skill("fireball")));
        for (final SkillDefinition definition : config.all()) {
            if (definition.mechanic() == Mechanic.MARK) {
                assertTrue(Mounts.usableWhileMounted(definition), definition.id());
            }
            if (definition.isAttack() && definition.tags().contains("melee")) {
                assertTrue(!Mounts.usableWhileMounted(definition), definition.id() + "：近戰不能騎著放");
            }
        }
    }
}
