package tw.exilecore.ailment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.item.AffixMod;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.monster.MonsterService;
import tw.exilecore.monster.MonsterSheets;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03 照站長：星潮祭世界 Boss「深潮海妖」免疫所有控場——monsters.yml 的 unstoppable 詞綴給 flag.cc_immune，
 * 異常模組（暈眩、凍結、冰緩、電擊的避免）與戰鬥模組的減益（減速、定身、眩暈…）都照這個旗標擋；純傷害的（點燃、感電）照常。
 */
class CcImmuneTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MonsterConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new MonsterConfig();
        config.load(DATA);
    }

    @Test
    void unstoppableModGrantsTheFlagAndIsHidden() {
        final MonsterConfig.MonsterMod mod = config.mod("unstoppable");
        assertNotNull(mod, "monsters.yml 沒有 unstoppable");
        assertEquals("不可動搖的", mod.name());
        assertTrue(mod.minLevel() >= MonsterService.HIDDEN_LEVEL, "只給 Boss／事件寫死用，不能自然抽到");
        boolean found = false;
        for (final AffixMod stat : mod.mods()) {
            if (stat.stat().equals(StatKeys.FLAG_CC_IMMUNE)) {
                assertEquals(1, stat.constant(), 1e-9);
                found = true;
            }
        }
        assertTrue(found, "unstoppable 要給 " + StatKeys.FLAG_CC_IMMUNE);
        assertTrue(MonsterService.bossSafe(mod) || mod.minLevel() >= MonsterService.HIDDEN_LEVEL);
    }

    @Test
    void monsterWithTheFlagIsImmuneToStunFreezeChillElectrocuteButNotDamageAilments() {
        final StatSheet unstoppable = MonsterSheets.build(config,
                new MonsterData(88, MonsterRarity.UNIQUE, List.of("swift", "pinnacle_resist", "unstoppable"), "深潮海妖", 1, 1, true), "DROWNED");
        final StatSheet plain = MonsterSheets.build(config,
                new MonsterData(88, MonsterRarity.UNIQUE, List.of("swift", "pinnacle_resist"), "深潮海妖", 1, 1, true), "DROWNED");
        assertTrue(AilmentService.ccImmune(unstoppable));
        assertFalse(AilmentService.ccImmune(plain));
        assertFalse(AilmentService.ccImmune((StatSheet) null));
        // 擲避免：帶旗標的暈眩、凍結、冰緩、電擊一律避開（不受 75% 上限）；沒旗標的照機率（0% = 不避）
        final Random random = new Random(1);
        for (final String stat : List.of(StatKeys.AVOID_STUN, StatKeys.AVOID_FREEZE, StatKeys.AVOID_CHILL, StatKeys.AVOID_ELECTROCUTE)) {
            for (int i = 0; i < 50; i++) {
                assertTrue(AilmentService.avoided(unstoppable, stat, random), stat + "：不可動搖一定避開");
            }
            assertFalse(AilmentService.avoided(plain, stat, random), stat + "：沒旗標、沒避免機率就不避");
        }
        // 傷害型異常（點燃、感電、流血、中毒）不擋
        for (final String stat : List.of(StatKeys.AVOID_IGNITE, StatKeys.AVOID_SHOCK, StatKeys.AVOID_BLEED, StatKeys.AVOID_POISON)) {
            assertFalse(AilmentService.avoided(unstoppable, stat, random), stat + "：純傷害的照常");
        }
    }

    @Test
    void debuffsDropTheirCrowdControlPartOnImmuneTargets() {
        final UUID source = UUID.randomUUID();
        // 整條都是控場的（釘地、眩暈、致盲、阻礙、癱瘓）：不放
        for (final String id : List.of("pin", "daze", "blind", "hinder", "maim", "monster_maim", "monster_blind")) {
            assertNull(Debuffs.withoutCc(new Debuffs.Debuff(id, source, 100, 0, false, null, List.of(), 1.0)), id);
        }
        // 詛咒：受到的傷害增加（留）＋ 減速（拿掉）
        final Debuffs.Debuff slowCurse = new Debuffs.Debuff("temporal_curse", source, 100, 0.2, false, null,
                List.of(Modifier.increased(StatKeys.MOVEMENT_SPEED, -0.3, "遲滯"), Modifier.increased(StatKeys.STUN_THRESHOLD, -0.5, "遲滯")), 0.3);
        final Debuffs.Debuff stripped = Debuffs.withoutCc(slowCurse);
        assertNotNull(stripped);
        assertEquals(0, stripped.slow(), 1e-9, "減速拿掉");
        assertEquals(0.2, stripped.taken(), 1e-9, "受到的傷害增加留著");
        assertTrue(stripped.mods().isEmpty(), "緩速、降暈眩門檻都拿掉");
        // 只有控場修飾器、沒有傷害的：整條不放
        assertNull(Debuffs.withoutCc(new Debuffs.Debuff("chill_curse", source, 100, 0, false, null,
                List.of(Modifier.increased(StatKeys.MOVEMENT_SPEED, -0.3, "冰緩")), 0)));
        // 純傷害的詛咒原封不動（同一個物件）
        final Debuffs.Debuff damageCurse = new Debuffs.Debuff("wound_curse", source, 100, 0.25, false, null,
                List.of(Modifier.flat(StatKeys.RESIST_FIRE, -20, "曝露")), 0);
        assertSame(damageCurse, Debuffs.withoutCc(damageCurse));
        // 控場修飾器的判斷：負的速度、門檻；正的「受到的暈眩累積」；其他不算
        assertTrue(Debuffs.ccModifier(Modifier.increased(StatKeys.MOVEMENT_SPEED, -0.3, "x")));
        assertFalse(Debuffs.ccModifier(Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.3, "x")), "加速不是控場");
        assertTrue(Debuffs.ccModifier(Modifier.more(AilmentKeys.STUN_BUILDUP_TAKEN, 0.5, "x")));
        assertFalse(Debuffs.ccModifier(Modifier.increased(StatKeys.DAMAGE_TAKEN, 0.3, "x")));
        // 經過 apply：免疫的目標只留傷害部分，不免疫的照放
        final Debuffs debuffs = new Debuffs();
        final UUID immune = UUID.randomUUID();
        final UUID normal = UUID.randomUUID();
        debuffs.setCcImmunity(immune::equals);
        debuffs.apply(immune, new Debuffs.Debuff("pin", source, 100, 0, false, null, List.of(), 1.0));
        debuffs.apply(immune, slowCurse);
        debuffs.apply(normal, new Debuffs.Debuff("pin", source, 100, 0, false, null, List.of(), 1.0));
        debuffs.apply(normal, slowCurse);
        assertEquals(0, debuffs.slow(immune, 10), 1e-9, "免疫的不會慢");
        assertEquals(1.2, debuffs.takenMultiplier(immune, source, 10), 1e-9, "詛咒的傷害部分還在");
        assertFalse(debuffs.has(immune, "pin", 10));
        assertEquals(1.0, debuffs.slow(normal, 10), 1e-9, "不免疫的照放");
        assertTrue(debuffs.has(normal, "pin", 10));
    }
}
