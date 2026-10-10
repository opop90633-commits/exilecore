package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.balance.BalanceConfig;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapStats;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.ModType;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不會一瞬間疊死人（2026-10，終局 Boss 進場被秒殺）：同一招打同一個人的間隔、換階段的停頓、
 * 大範圍新星跑得掉、終局 Boss 前搖短的招一下打不死滿血的參考角色。
 */
class BossBurstTest {

    static final Path DATA = Path.of("src/main/resources/data");
    /** 跑步每秒大約幾格（按著衝刺往外跑）。 */
    static final double RUN_SPEED = 5.6;
    static SkillConfig config;
    static MapConfig maps;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("test.yml", new StringReader(text));
    }

    private static BossSkill skill(final String text) {
        return BossSkill.parse("x", yaml(text));
    }

    // ---- 同一招打同一個人 ----

    @Test
    void sameSkillHitsDoNotStack() {
        final HitGate gate = new HitGate();
        final UUID player = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        assertTrue(gate.allow("king_bone_volley", player, 10, 100));
        assertFalse(gate.allow("king_bone_volley", player, 10, 100), "同一個 tick 的第二發不算");
        assertFalse(gate.allow("king_bone_volley", player, 10, 109));
        assertTrue(gate.allow("king_bone_volley", player, 10, 110), "隔 0.5 秒可以再打");
        assertTrue(gate.allow("king_bone_volley", other, 10, 110), "別人不受影響");
        assertTrue(gate.allow("king_wailing_souls", player, 10, 111), "別招不受影響");
        for (int i = 0; i < 5; i++) {
            assertTrue(gate.allow("king_bone_ring", player, 0, 111), "不限的招每次都算");
        }
        assertEquals(3, gate.size(), "不限的招不記");
        gate.prune(120);
        assertEquals(1, gate.size(), "到期的丟掉（110 打的兩筆到 120），111 打的到 121 還留著");
        gate.prune(121);
        assertEquals(0, gate.size());
        assertTrue(gate.allow("king_wailing_souls", player, 160, 200), "光球照活多久算");
        assertFalse(gate.allow("king_wailing_souls", player, 160, 359));
        assertTrue(gate.allow("king_wailing_souls", player, 160, 360));
        gate.allow("x", player, 10, 500);
        gate.clear();
        assertTrue(gate.allow("x", player, 10, 501), "重置後重新算");
    }

    @Test
    void shippedVolleysAndOrbsHitOnceAtATime() {
        // 站在蝕骨之王旁邊：八發骨矢同一個 tick 打中——以前是八下，現在一下
        final BossSkillConfig bosses = config.bossSkills();
        for (final String id : List.of("king_bone_volley", "queen_ice_lances", "general_fire_volley", "star_lance_volley")) {
            final BossSkill skill = bosses.skill(id);
            assertNotNull(skill, id);
            final HitGate gate = new HitGate();
            final UUID player = UUID.randomUUID();
            int hits = 0;
            for (int i = 0; i < skill.count(); i++) {
                if (gate.allow(skill.id(), player, skill.hitGapTicks(), 1000 + i / 4)) {
                    hits++;
                }
            }
            assertEquals(1, hits, id + "：" + skill.count() + " 發幾乎同時打到只算一下");
        }
        // 光球：從 Boss 身邊一圈出發、各自追過來，前後差半秒多才碰到——站著不動也只會被一顆打到
        int orbSkills = 0;
        for (final BossSkill skill : bosses.skills().values()) {
            if (skill.kind() != BossSkill.Kind.ORBS) {
                continue;
            }
            orbSkills++;
            for (double distance = 3; distance <= 12; distance += 0.5) {
                final List<Long> contacts = orbContacts(skill, distance);
                assertEquals(skill.count(), contacts.size(), skill.id() + "：每顆都追得到站著不動的人");
                final HitGate gate = new HitGate();
                final UUID player = UUID.randomUUID();
                int hits = 0;
                for (final long tick : contacts) {
                    if (gate.allow(skill.id(), player, skill.hitGapTicks(), tick)) {
                        hits++;
                    }
                }
                assertEquals(1, hits, skill.id() + "：離 Boss " + distance + " 格，光球在 " + contacts + " tick 碰到");
            }
        }
        assertTrue(orbSkills >= 7, "光球的招（亡魂、冰魄、餘燼火球、場地危害、極境）");
        // 瘟疫池四塊疊在腳下：每 0.5 秒只照一塊算
        final BossSkill pool = bosses.skill("king_plague_pool");
        final HitGate gate = new HitGate();
        final UUID player = UUID.randomUUID();
        int hits = 0;
        for (long tick = 0; tick < 40; tick += 10) {
            for (int zone = 0; zone < pool.count(); zone++) {
                if (gate.allow(pool.id(), player, pool.hitGapTicks(), tick)) {
                    hits++;
                }
            }
        }
        assertEquals(4, hits, "四塊疊在一起、兩秒四波：四下（以前十六下）");
    }

    /**
     * 照 {@code BossSkills.spawnOrbs／tickOrbs} 的走法：光球繞著 Boss 一圈（半徑 1.5）出發，每 tick 往玩家飄 speed 格，
     * 離玩家的中心 size + 半個身寬 + 0.3 格以內就碰到。回傳每顆碰到的 tick。
     */
    private static List<Long> orbContacts(final BossSkill skill, final double distance) {
        final List<Long> contacts = new ArrayList<>();
        final double reach = skill.size() + 0.3 + 0.3;
        for (int i = 0; i < skill.count(); i++) {
            final double angle = Math.PI * 2 * i / skill.count();
            double x = Math.cos(angle) * 1.5;
            double z = Math.sin(angle) * 1.5;
            for (long tick = 1; tick <= Math.round(skill.duration() * 20); tick++) {
                final double dx = distance - x;
                final double dz = -z;
                final double length = Math.hypot(dx, dz);
                final double step = Math.min(skill.speed(), length);
                x += dx / length * step;
                z += dz / length * step;
                if (Math.hypot(distance - x, z) <= reach) {
                    contacts.add(tick);
                    break;
                }
            }
        }
        return contacts;
    }

    @Test
    void hitGapsDependOnTheKind() {
        assertEquals(BossSkill.SAME_SKILL_GAP_TICKS, skill("kind: volley\n").hitGapTicks());
        assertEquals(160, skill("kind: orbs\n").hitGapTicks(), "光球一次只被一顆打到：照光球活多久（預設 8 秒）");
        assertEquals(20, skill("kind: orbs\nduration: 0.5\n").hitGapTicks(), "至少 1 秒");
        assertEquals(10, skill("kind: slam\ncount: 12\n").hitGapTicks());
        assertEquals(9, skill("kind: zone\ninterval: 0.5\n").hitGapTicks(), "地面效果照自己的間隔：疊在一起也只照一塊算");
        assertEquals(19, skill("kind: zone\ninterval: 1\n").hitGapTicks());
        assertEquals(1, skill("kind: zone\ninterval: 0.1\n").hitGapTicks(), "最快 2 tick 一波");
        for (final String text : List.of("kind: nova\n", "kind: cone\ncount: 2\n", "kind: ring\ngaps: 2\n", "kind: beam\n",
                "kind: charge\n", "kind: pull\n", "kind: sanctuary\n", "kind: blink\ndamage: 1\n")) {
            assertEquals(0, skill(text).hitGapTicks(), text.trim() + "：一波本來就只打一次（或自己有間隔）");
        }
    }

    // ---- 換階段 ----

    @Test
    void phaseChangesPauseAndUnlockNewSkillsLater() {
        final BossSkillConfig bosses = config.bossSkills();
        assertEquals(1.5, bosses.settings().phasePause(), 1e-9, "2026-10：2 → 1.5 秒");
        assertEquals(3.0, bosses.settings().phaseUnlock(), 1e-9, "2026-10：4 → 3 秒");
        final MapConfig.Boss king = maps.find("bone_throne").boss();
        final List<BossSkillConfig.Entry> kit = bosses.kitFor(king.type(), king.skills());
        assertEquals(List.of("king_reap", "king_shadow_step", "king_bone_storm", "king_soul_vortex"),
                BossSkillConfig.unlockedBetween(kit, 0, 1));
        assertEquals(List.of("king_soul_harvest", "king_summon_wither", "king_last_ferry"), BossSkillConfig.unlockedBetween(kit, 1, 2));
        assertEquals(7, BossSkillConfig.unlockedBetween(kit, 0, 2).size(), "一次跨兩個階段：兩個階段的招都晚一點");
        assertEquals(List.of(), BossSkillConfig.unlockedBetween(kit, 2, 2));
        assertEquals(List.of(), BossSkillConfig.unlockedBetween(kit, 2, 0), "重置回第一階段");
        // 設定
        final BossSkillConfig custom = new BossSkillConfig();
        custom.load(yaml("settings: { phase_pause: 0, phase_unlock: 6 }\nskills:\n  slam: { kind: slam }\n"));
        assertEquals(0, custom.settings().phasePause(), 1e-9);
        assertEquals(6, custom.settings().phaseUnlock(), 1e-9);
        custom.load(yaml("skills:\n  slam: { kind: slam }\n"));
        assertEquals(1.5, custom.settings().phasePause(), 1e-9, "沒寫用預設");
        assertEquals(3.0, custom.settings().phaseUnlock(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> custom.load(yaml("settings: { phase_pause: 11 }\n")));
        assertThrows(IllegalArgumentException.class, () -> custom.load(yaml("settings: { phase_unlock: -1 }\n")));
    }

    // ---- 出貨的數值 ----

    @Test
    void bigNovasCanBeOutrun() {
        int checked = 0;
        for (final BossSkill skill : config.bossSkills().skills().values()) {
            if (skill.kind() != BossSkill.Kind.NOVA || skill.radius() < 12) {
                continue;
            }
            // 從 Boss 身邊（2 格）跑出半徑，再加 0.5 秒反應
            final double needed = (skill.radius() - 2) / RUN_SPEED + 0.5;
            assertTrue(skill.telegraph() >= needed - 1e-9, skill.id() + "：從 Boss 身邊跑出 " + skill.radius() + " 格要 "
                    + String.format("%.2f", needed) + " 秒，前搖只有 " + skill.telegraph());
            checked++;
        }
        assertTrue(checked >= 4, "大範圍的新星（曜帝、永凍女王、蝕骨之王、餘燼將軍）");
    }

    /**
     * 終局 Boss（四隻，不含極境）來不及躲的招——前搖加落下 1.5 秒內，大範圍新星照「跑得出去」的規則——
     * 在最後一個階段（加傷害）、身上有這隻 Boss 自己的詛咒時打中，參考角色滿血也不會死：
     * 同等級普通怪一下扣 balance.yml 的 hit_percent，Boss 普通一下是它的「傳奇倍率 × maps.yml 的 damage」倍，
     * 再乘詞綴的附加傷害；冰、火、電照 75% 抗性、物理照護甲擋兩成五（大約 5,000 護甲擋三千多的一下）、混沌沒有抗性。
     * 新星、扇形、擴散環一秒內打完的好幾波加起來算一下。不算暴擊（怪物 5%）。
     * 全場大招、漩渦（前搖長、跑得掉）不算；場地危害要算。
     */
    @Test
    void finalBossesCannotOneShotWithQuickHits() throws IOException {
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        final BalanceConfig balance = new BalanceConfig();
        balance.load(DATA);
        final BossSkillConfig bosses = config.bossSkills();
        final List<String> report = new ArrayList<>();
        int checked = 0;
        for (final MapConfig.MapDef map : maps.finals()) {
            final MapConfig.Boss boss = map.boss();
            final int level = MapStats.forTier(maps.tiers(), maps.tiers().max(), maps.finalLevelBonus(map.id())).monsterLevel();
            double added = 1;
            for (final String modId : boss.mods()) {
                final MonsterConfig.MonsterMod mod = monsters.mod(modId);
                if (mod != null) {
                    for (final double value : mod.addedFromPhysical().values()) {
                        added += value;
                    }
                }
            }
            final double hit = monsters.rarity(MonsterRarity.UNIQUE).damageMultiplier() * boss.damage() * added * balance.hitPercent(level) / 100;
            double more = 1;
            for (final MapConfig.Phase phase : boss.phases()) {
                for (final AffixMod stat : phase.stats()) {
                    if (stat.stat().equals("damage") && stat.type() == ModType.MORE) {
                        more *= 1 + stat.toModifier(Map.of(), "test").value();
                    }
                }
            }
            final List<BossSkill> skills = new ArrayList<>();
            double curse = 0;
            for (final BossSkillConfig.Entry entry : bosses.kitFor(boss.type(), boss.skills())) {
                skills.add(entry.skill());
                if (entry.skill().kind() == BossSkill.Kind.CURSE && entry.fromPhase() <= boss.phases().size()) {
                    curse = Math.max(curse, entry.skill().taken() / 100);
                }
            }
            for (final tw.exilecore.skill.BossHazard hazard : boss.hazards()) {
                skills.add(bosses.skill(hazard.skill()));
            }
            for (final BossSkill skill : skills) {
                if (!skill.dealsDamage() || skill.kind() == BossSkill.Kind.SANCTUARY || skill.kind() == BossSkill.Kind.PULL) {
                    continue;
                }
                final double warning = skill.telegraph() + (skill.kind() == BossSkill.Kind.SLAM ? skill.delay() : 0);
                final boolean escapable = skill.kind() == BossSkill.Kind.NOVA
                        ? skill.telegraph() >= (skill.radius() - 2) / RUN_SPEED + 0.5
                        : warning >= 1.5;
                if (escapable) {
                    continue;
                }
                final double taken = switch (skill.type()) {
                    case PHYSICAL -> 0.75;
                    case CHAOS -> 1.0;
                    default -> 0.25;
                };
                // 新星、扇形、擴散環一秒內打完的幾波：算成一下（第一波打到還來不及走出去、走進缺口）
                final boolean waves = skill.kind() == BossSkill.Kind.NOVA || skill.kind() == BossSkill.Kind.CONE
                        || skill.kind() == BossSkill.Kind.RING;
                final int inOneSecond = !waves ? 1
                        : skill.stagger() <= 0 ? skill.count() : Math.min(skill.count(), (int) Math.floor(1.0 / skill.stagger() + 1e-9) + 1);
                final double fraction = skill.damage() * hit * more * (1 + curse) * taken * inOneSecond;
                report.add(String.format("%s %s %.0f%%", map.id(), skill.id(), fraction * 100));
                assertTrue(fraction < 1.0, map.id() + " 的 " + skill.id() + "（前搖 " + warning + " 秒）打中一下就是參考角色 "
                        + Math.round(fraction * 100) + "% 的生命：" + report);
                checked++;
            }
        }
        assertTrue(checked >= 25, "四隻終局 Boss 前搖短的招至少 25 招，實際 " + checked + "：" + report);
    }
}
