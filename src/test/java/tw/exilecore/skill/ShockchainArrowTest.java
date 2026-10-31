package tw.exilecore.skill;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Shockchain Arrow（電鍊箭矢，chain_arrow）。 */
class ShockchainArrowTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static double modifier(final SkillDefinition skill, final String stat) {
        double total = 0;
        boolean found = false;
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(stat)) {
                total += modifier.value();
                found = true;
            }
        }
        return found ? total : Double.NaN;
    }

    @Test
    void skillDataMatchesRef() {
        final SkillDefinition arrow = config.skill("linked_lightning_arrow");
        assertNotNull(arrow);
        assertEquals("連電箭", arrow.name());
        assertEquals(11, arrow.tier());
        assertEquals(0.7, arrow.effectiveness().at(1), 1e-9);
        assertEquals(2.18, arrow.effectiveness().at(20), 1e-9);
        assertEquals(10, arrow.mana().at(1), 1e-9);
        assertEquals(68, arrow.mana().at(20), 1e-9);
        // 本身不連鎖：連鎖只來自打中閃電異常的敵人（這次之前固定 5 次）
        assertTrue(Double.isNaN(modifier(arrow, StatKeys.SKILL_CHAINS)));
        assertEquals(3, modifier(arrow, ShockchainArrows.CHAINS_ON_AILMENT), 1e-9);
        assertEquals(1, modifier(arrow, ShockchainArrows.FLAG_HOMING), 1e-9);
        assertEquals(1, modifier(arrow, ShockchainArrows.FLAG_CONSUME_SHOCK), 1e-9);
        assertEquals(-1.0, modifier(arrow, StatKeys.SHOCK_CHANCE), 1e-9); // 更多 -100%（MORE 存成比例）
        assertTrue(Double.isNaN(modifier(arrow, "proc.hit")), "震波不再是每一下都觸發的隱藏技能");
        assertNull(config.skill("proc_shockchain_wave"));
        assertTrue(arrow.description().contains("雷霆之杖"));
        assertTrue(arrow.description().contains("不會消耗電擊"));
        assertTrue(arrow.description().contains("重複這個過程") && arrow.description().contains("連鎖 8 次後消失"), "2026-10-29 照原作：連鎖的箭會重複，總共 8 次");
    }

    @Test
    void shockwaveStageAndRod() {
        final SkillStages.Stage wave = SkillStages.get("linked_lightning_arrow", ShockchainArrows.SHOCKWAVE);
        assertNotNull(wave);
        assertEquals(1.4, wave.effectiveness().at(1), 1e-9);
        assertEquals(5.07, wave.effectiveness().at(20), 1e-9);
        assertEquals(18.42, wave.effectiveness().at(40), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.5), wave.radius(1), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(1.0, wave.conversion().get(DamageType.LIGHTNING), 1e-9);
        final SkillConfig.RodSpec rod = config.rod("linked_lightning_arrow");
        assertNotNull(rod);
        assertEquals(20, rod.duration(), 1e-9);
        assertEquals(10, rod.max());
        assertEquals(8, rod.chains());
        assertEquals(1.0, rod.charge(), 1e-9); // PoE2 0.2.0：被連到後充能 1 秒
        assertEquals(tw.exilecore.core.Units.metres(8), ShockchainArrows.CHAIN_RANGE, 1e-9); // PoE2 0.2.0：連鎖最遠 8 公尺
    }

    @Test
    void orbBoltChainsThroughRods() {
        // 2026-10-28 照 PoE2：雷雲的連鎖可以連到雷霆之杖，再從杖往下連（SkillZones.bolt 把杖跟敵人放在同一串候選）
        final LightningRods.Rod rod = new LightningRods.Rod(UUID.randomUUID(), UUID.randomUUID(), new Vector(3, 0, 0), 0, 400, 8, 20, null, 2.5,
                "thunderclap_arrow", "linked_lightning_arrow");
        final Object first = new Vector(0, 0, 0);
        final Object far = new Vector(6, 0, 0);
        final List<Object> chain = ZonePulses.boltChain(List.of(first), List.of(first, rod, far), 5, 3.5,
                (a, b) -> position(a).distance(position(b)), new java.util.Random(1));
        assertEquals(List.of(first, rod, far), chain, "敵人 → 杖 → 杖那邊的敵人");
    }

    private static Vector position(final Object node) {
        return node instanceof LightningRods.Rod rod ? rod.at : (Vector) node;
    }

    @Test
    void chargesAndRepeatsOnLightningAilmentHits() {
        // 第一下打到沒有閃電異常的：普通的一箭（不連鎖、不放震波、不充能、不插杖）
        final ShockchainArrows.Outcome plain = ShockchainArrows.onHit(3, false, false);
        assertEquals(0, plain.addChains());
        assertFalse(plain.shockwave());
        assertFalse(plain.charges());
        assertFalse(ShockchainArrows.lodgesRod(3, false));
        // 2026-10-28 照 PoE2「on first hitting」：第一次打到感電／電擊中的：充能、震波、多 3 次連鎖
        final ShockchainArrows.Outcome charged = ShockchainArrows.onHit(3, true, false);
        assertEquals(3, charged.addChains());
        assertTrue(charged.shockwave());
        assertTrue(charged.charges());
        // 充能之後（連鎖的次級投射物）每一下都放震波；2026-10-29 照原作：再打到閃電異常的重複（再多 3 次），總共最多 8 次
        final ShockchainArrows.Outcome chainedPlain = ShockchainArrows.onHit(3, false, true);
        assertEquals(0, chainedPlain.addChains());
        assertTrue(chainedPlain.shockwave());
        final ShockchainArrows.Outcome chainedAfflicted = ShockchainArrows.onHit(3, true, true);
        assertEquals(3, chainedAfflicted.addChains());
        assertEquals(5, ShockchainArrows.capChains(2 + 3, 3), "連鎖過 3 次：最多再 5 次");
        assertEquals(2, ShockchainArrows.capChains(2 + 3, 6), "連鎖過 6 次：只剩 2 次（總共 8 次）");
        assertEquals(0, ShockchainArrows.capChains(3, 8), "連鎖 8 次後消失");
        assertTrue(chainedAfflicted.shockwave());
        assertFalse(chainedAfflicted.charges());
        assertTrue(ShockchainArrows.lodgesRod(3, true));
        // 不是電鍊箭矢的投射物不受影響
        assertEquals(0, ShockchainArrows.onHit(0, true, true).addChains());
        assertFalse(ShockchainArrows.onHit(0, true, false).shockwave());
        assertFalse(ShockchainArrows.lodgesRod(0, true));
    }

    @Test
    void onlyInitialArrowConsumesShock() {
        // PoE2 0.4.0：初始的箭用掉感電；次級投射物（連鎖的箭）不用掉
        assertTrue(ShockchainArrows.consumesShock(true, false));
        assertFalse(ShockchainArrows.consumesShock(true, true));
        assertFalse(ShockchainArrows.consumesShock(false, false));
    }

    @Test
    void piercesNonShockedOnlyWhileTargetingShocked() {
        // RePoE always_pierce_non_shocked_enemies_if_targeting_shocked_enemy
        assertTrue(ShockchainArrows.piercesOnTheWay(true, true, false, false), "追感電的，路上沒感電的穿過");
        assertFalse(ShockchainArrows.piercesOnTheWay(false, true, false, false), "追的只有電擊（沒感電）：不穿");
        assertFalse(ShockchainArrows.piercesOnTheWay(true, true, true, false), "路上感電的照打");
        assertFalse(ShockchainArrows.piercesOnTheWay(true, false, false, false), "就是追的那一隻");
        assertFalse(ShockchainArrows.piercesOnTheWay(true, true, false, true), "電擊中、會讓箭充能的照打（第一次打中）");
    }

    @Test
    void homingTurnsAtMostTheLimit() {
        final Vector forward = new Vector(1, 0, 0);
        final Vector side = new Vector(0, 0, 5);
        final Vector turned = ShockchainArrows.steer(forward, side, 12);
        assertEquals(1, turned.length(), 1e-9);
        assertEquals(12, Math.toDegrees(forward.angle(turned)), 1e-6);
        assertTrue(turned.getZ() > 0, "往目標那邊轉");
        // 角度小於上限：直接對準
        final Vector slight = new Vector(10, 0, 1);
        final Vector aimed = ShockchainArrows.steer(forward, slight, 12);
        assertEquals(0, Math.toDegrees(aimed.angle(slight)), 1e-6);
        assertTrue(ShockchainArrows.ahead(forward, new Vector(5, 0, 4), ShockchainArrows.HOMING_CONE));
        assertFalse(ShockchainArrows.ahead(forward, new Vector(-5, 0, 1), ShockchainArrows.HOMING_CONE));
    }

    @Test
    void rodCapIsPerSkill() {
        final LightningRods rods = new LightningRods();
        final UUID owner = UUID.randomUUID();
        final UUID world = UUID.randomUUID();
        final List<LightningRods.Rod> removed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rods.place(new LightningRods.Rod(owner, world, new Vector(i, 64, 0), 0, 400, 8, 20, null, 2.0, "thunderclap_arrow", "thunder_stake_shaft"), 10, removed::add);
        }
        for (int i = 0; i < 10; i++) {
            rods.place(new LightningRods.Rod(owner, world, new Vector(i, 64, 5), 0, 400, 8, 20, null, 2.5, "thunderclap_arrow", "linked_lightning_arrow"), 10, removed::add);
        }
        assertTrue(removed.isEmpty(), "雷樁矢、連電箭各自最多 10 支");
        assertEquals(20, rods.count(owner));
        rods.place(new LightningRods.Rod(owner, world, new Vector(0, 64, 9), 0, 400, 8, 20, null, 2.5, "thunderclap_arrow", "linked_lightning_arrow"), 10, removed::add);
        assertEquals(1, removed.size());
        assertEquals("linked_lightning_arrow", removed.get(0).skill);
        assertEquals(10, rods.count(owner, "thunder_stake_shaft"));
    }

    @Test
    void qualityIsIncreasedLightningAndMoreAttackSpeed() {
        // 2026-10-29 照 poe2db 寶石頁「增加 (0—20)% 閃電傷害」（2026-10-28 寫成更多）
        final List<Modifier> normal = GemQuality.modifiers("linked_lightning_arrow", 20, "q");
        assertNotNull(normal);
        // 2026-10-30 照 RePoE quality_stats 與 poe2db 英文頁：更多（more）
        assertTrue(normal.stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && m.type() == ModType.MORE && m.tags().contains("lightning")));
        final List<Modifier> advanced = GemQuality.advancedModifiers("linked_lightning_arrow", 20, "q");
        assertNotNull(advanced);
        assertTrue(advanced.stream().anyMatch(m -> m.stat().equals(StatKeys.ATTACK_SPEED) && m.type() == ModType.MORE));
    }

    @Test
    void modifierText() {
        assertTrue(ModifierText.describe(Modifier.flat(ShockchainArrows.CHAINS_ON_AILMENT, 3, "x")).contains("多連鎖 3 次"));
        assertTrue(ModifierText.describe(Modifier.flat(ShockchainArrows.FLAG_CONSUME_SHOCK, 1, "x")).contains("不消耗電擊"));
        assertTrue(ModifierText.describe(Modifier.flat(ShockchainArrows.FLAG_HOMING, 1, "x")).contains("追蹤"));
    }

    @Test
    void rodLabelShowsTimeAndChainsAndBlinks() {
        // 2026-10-29 照站長「更明顯」：杖頭上顯示剩幾秒、還能被連幾次；最後 3 秒紅白交替；充能中改成青色
        final String calm = LightningRods.labelText(15 * 20, 6, false, true);
        assertTrue(calm.contains("15 秒") && calm.contains("剩 6 次") && calm.startsWith("<yellow>"), calm);
        assertTrue(LightningRods.labelText(41, 2, false, true).startsWith("<red>"), "最後 3 秒：紅");
        assertTrue(LightningRods.labelText(41, 2, false, false).startsWith("<white>"), "最後 3 秒：白（閃）");
        assertTrue(LightningRods.labelText(41, 2, false, true).contains("3 秒"), "41 tick 進位成 3 秒");
        assertTrue(LightningRods.labelText(200, 5, true, true).contains("充能中"));
        assertTrue(LightningRods.blinkOn(0) != LightningRods.blinkOn(5), "每 0.25 秒換一次");
    }
}
