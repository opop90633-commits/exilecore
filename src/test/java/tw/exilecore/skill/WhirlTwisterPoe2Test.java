package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5（poe2db Whirling Slash、Twister）：旋矛擊的旋風、旋風矛的旋風、元素地面。 */
class WhirlTwisterPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void whirlwindRadiusStagesAndCollapse() {
        assertEquals(1.8, Signatures.whirlRadius(1, 1), 1e-9, "Whirlwind radius is 1.8 metres");
        assertEquals(2.1, Signatures.whirlRadius(2, 1), 1e-9, "+0.3m to Radius per additional stage");
        assertEquals(2.4, Signatures.whirlRadius(3, 1), 1e-9);
        assertEquals(3.6, Signatures.whirlRadius(1, 2), 1e-9, "吃範圍");
        assertEquals(1, Signatures.nextWhirlStage(0), "沒有旋風：捲起第 1 階");
        assertEquals(2, Signatures.nextWhirlStage(1));
        assertEquals(3, Signatures.nextWhirlStage(3), "Maximum 3 stages");
        assertEquals(1.0, Signatures.slashScale(1), 1e-9, "揮的那一圈跟旋風一樣大");
        assertEquals(2.4 / 1.8, Signatures.slashScale(3), 1e-9);
        assertEquals(1.0, Signatures.collapseMore(1, 0), 1e-9);
        assertEquals(2.5, Signatures.collapseMore(2, 0), 1e-9, "150% more damage per additional stage");
        assertEquals(4.0, Signatures.collapseMore(3, 0), 1e-9);
        assertEquals(1 + 1.9 * 2, Signatures.collapseMore(3, 40), 1e-9, "20% 品質：每階再 +40%");
    }

    @Test
    void whirlingSlashData() {
        final SkillDefinition slash = config.skill("whirling_slash");
        assertTrue(slash.tags().contains("wind"), "Wind");
        assertEquals(1.8, slash.params().radius().at(1), 1e-9);
        assertEquals(1.8, slash.params().radius().at(40), 1e-9, "不再隨等級變大");
        assertTrue(slash.params().knockback() > 0, "Knocks Back Enemies");
        final SkillDefinition collapse = config.skill("whirling_slash_collapse");
        assertEquals(1.0, collapse.effectiveness().at(7), 1e-9, "7 級 100%（RePoE 沒寫＝預設值）");
        assertEquals(0.6, collapse.effectiveness().at(1), 1e-9);
        assertEquals(2.17, collapse.effectiveness().at(20), 1e-9);
        assertEquals(7.89, collapse.effectiveness().at(40), 1e-9);
    }

    @Test
    void twisterData() {
        final SkillDefinition twister = config.skill("gale_spear");
        assertEquals(Twisters.SIGNATURE, config.signature("gale_spear"));
        assertTrue(twister.tags().contains("wind"), "Wind");
        assertEquals(7.5, twister.params().speed() * 20, 1e-9, "Projectile Speed: 7.5 metres per Second");
        assertEquals(0.5, twister.params().size(), 1e-9, "Twister radius is 0.5 metres");
        assertEquals(3.0, Twisters.DURATION, 1e-9, "Twister duration is 3 seconds");
        assertEquals(13, Twisters.HIT_INTERVAL, "0.66 秒");
    }

    @Test
    void twisterCountSpreadAndMore() {
        final Random random = new Random(1);
        assertEquals(1, Twisters.count(1, 0, random));
        assertEquals(3, Twisters.count(3, 0, random), "投射物數量幾個就幾道");
        assertEquals(2, Twisters.count(1, 100, random), "每 100% 一定多一道");
        int extra = 0;
        for (int i = 0; i < 2000; i++) {
            extra += Twisters.count(1, 20, random) - 1;
        }
        assertTrue(extra > 300 && extra < 500, "20% 機率多一道：" + extra);
        assertEquals(0, Twisters.offset(0, 1), 1e-9);
        assertEquals(-15, Twisters.offset(0, 3), 1e-9, "互相隔 15°");
        assertEquals(15, Twisters.offset(2, 3), 1e-9);
        assertEquals(1.0, Twisters.more(0), 1e-9);
        assertEquals(3.4, Twisters.more(3), 1e-9, "每一階旋風更多 80%");
    }

    @Test
    void twisterHitInterval() {
        final Twisters.Volley volley = new Twisters.Volley();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        assertTrue(volley.tryHit(a, 100));
        assertFalse(volley.tryHit(a, 105), "同一次放出去的旋風 0.66 秒內不再打同一個");
        assertTrue(volley.tryHit(b, 105), "別的敵人照打");
        assertFalse(volley.tryHit(a, 112));
        assertTrue(volley.tryHit(a, 113), "重複打中");
    }

    @Test
    void elementalGround() {
        final UUID world = UUID.randomUUID();
        final List<ElementalGround.Surface> list = new ArrayList<>();
        ElementalGround.add(list, new ElementalGround.Surface(world, 0, 64, 0, 2, DamageType.FIRE, 100));
        ElementalGround.add(list, new ElementalGround.Surface(world, 3, 64, 0, 1, DamageType.COLD, 200));
        assertEquals(DamageType.FIRE, ElementalGround.find(list, world, 1, 64, 0, 0, 50));
        assertEquals(DamageType.COLD, ElementalGround.find(list, world, 2.5, 64, 0, 0, 50), "同時踩到兩種照最新放的");
        assertEquals(DamageType.FIRE, ElementalGround.find(list, world, 1.9, 64, 0, -1, 50), "reach 0 以下照 0 算");
        assertEquals(DamageType.FIRE, ElementalGround.find(list, world, 0, 64, 2.4, 0.5, 50), "旋風本身有半徑");
        assertNull(ElementalGround.find(list, world, 0, 64, 2.4, 0, 50));
        assertNull(ElementalGround.find(list, world, 0, 67, 0, 0, 50), "高度差太多");
        assertNull(ElementalGround.find(list, UUID.randomUUID(), 0, 64, 0, 0, 50), "別的世界");
        assertNull(ElementalGround.find(list, world, 0, 64, 0, 0, 150), "點燃地面過期了");
        assertEquals(1, list.size(), "過期的清掉");
        for (int i = 0; i < ElementalGround.MAX + 5; i++) {
            ElementalGround.add(list, new ElementalGround.Surface(world, i, 64, 0, 1, DamageType.LIGHTNING, 1000));
        }
        assertEquals(ElementalGround.MAX, list.size());
        assertEquals(DamageType.FIRE, ElementalGround.of(AilmentType.IGNITE));
        assertEquals(DamageType.COLD, ElementalGround.of(AilmentType.CHILL));
        assertEquals(DamageType.LIGHTNING, ElementalGround.of(AilmentType.SHOCK));
        assertNull(ElementalGround.of(AilmentType.POISON), "毒地不是元素地面");
    }

    @Test
    void quality() {
        final List<Modifier> slash = GemQuality.modifiers("whirling_slash", 20, "q");
        assertEquals(40, slash.stream().filter(m -> m.stat().equals(Signatures.WHIRL_QUALITY_MORE)).mapToDouble(Modifier::value).sum(), 1e-9,
                "Collapse deals +(0—40)% more damage per additional stage");
        final List<Modifier> twister = GemQuality.modifiers("gale_spear", 20, "q");
        assertEquals(20, twister.stream().filter(m -> m.stat().equals(Twisters.EXTRA_CHANCE)).mapToDouble(Modifier::value).sum(), 1e-9,
                "(0—20)% chance for an additional twister");
        assertTrue(GemQuality.hasAdvanced("whirling_slash") && GemQuality.hasAdvanced("gale_spear"));
        assertEquals("品質 +20%：旋風崩散每多一階再更多 40% 傷害（加在 150% 上）", SkillService.qualityText(config.skill("whirling_slash"), 20));
        assertEquals("品質 +20%：20% 機率多一道旋風", SkillService.qualityText(config.skill("gale_spear"), 20));
    }
}
