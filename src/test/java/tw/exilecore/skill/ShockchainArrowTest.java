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
    void skillDataMatchesPoe2() {
        final SkillDefinition arrow = config.skill("chain_arrow");
        assertNotNull(arrow);
        assertEquals("電鍊箭矢", arrow.name());
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
    }

    @Test
    void shockwaveStageAndRod() {
        final SkillStages.Stage wave = SkillStages.get("chain_arrow", ShockchainArrows.SHOCKWAVE);
        assertNotNull(wave);
        assertEquals(1.4, wave.effectiveness().at(1), 1e-9);
        assertEquals(5.07, wave.effectiveness().at(20), 1e-9);
        assertEquals(18.42, wave.effectiveness().at(40), 1e-9);
        assertEquals(2.5, wave.radius(1), 1e-9);
        assertEquals(1.0, wave.conversion().get(DamageType.LIGHTNING), 1e-9);
        final SkillConfig.RodSpec rod = config.rod("chain_arrow");
        assertNotNull(rod);
        assertEquals(20, rod.duration(), 1e-9);
        assertEquals(10, rod.max());
        assertEquals(8, rod.chains());
    }

    @Test
    void chainsOnlyFromLightningAilmentsAndRepeats() {
        // 第一下打到沒有閃電異常的：普通的一箭（不連鎖、不放震波、不插杖）
        ShockchainArrows.Outcome plain = ShockchainArrows.onHit(3, false, false);
        assertEquals(0, plain.addChains());
        assertFalse(plain.shockwave());
        assertFalse(ShockchainArrows.lodgesRod(3, 0));
        // 打到感電／電擊中的：震波＋多 3 次連鎖
        ShockchainArrows.Outcome charged = ShockchainArrows.onHit(3, true, false);
        assertEquals(3, charged.addChains());
        assertTrue(charged.shockwave());
        // 連鎖過來的每一下都放震波；再打到閃電異常的再多 3 次
        ShockchainArrows.Outcome chainedPlain = ShockchainArrows.onHit(3, false, true);
        assertEquals(0, chainedPlain.addChains());
        assertTrue(chainedPlain.shockwave());
        assertEquals(3, ShockchainArrows.onHit(3, true, true).addChains());
        assertTrue(ShockchainArrows.lodgesRod(3, 1));
        // 不是電鍊箭矢的投射物不受影響
        assertEquals(0, ShockchainArrows.onHit(0, true, true).addChains());
        assertFalse(ShockchainArrows.onHit(0, true, true).shockwave());
        assertFalse(ShockchainArrows.lodgesRod(0, 5));
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
            rods.place(new LightningRods.Rod(owner, world, new Vector(i, 64, 0), 0, 400, 8, 20, null, 2.0, "lightning_arrow", "lightning_rod"), 10, removed::add);
        }
        for (int i = 0; i < 10; i++) {
            rods.place(new LightningRods.Rod(owner, world, new Vector(i, 64, 5), 0, 400, 8, 20, null, 2.5, "lightning_arrow", "chain_arrow"), 10, removed::add);
        }
        assertTrue(removed.isEmpty(), "雷樁矢、電鍊箭矢各自最多 10 支");
        assertEquals(20, rods.count(owner));
        rods.place(new LightningRods.Rod(owner, world, new Vector(0, 64, 9), 0, 400, 8, 20, null, 2.5, "lightning_arrow", "chain_arrow"), 10, removed::add);
        assertEquals(1, removed.size());
        assertEquals("chain_arrow", removed.get(0).skill);
        assertEquals(10, rods.count(owner, "lightning_rod"));
    }

    @Test
    void qualityIsMoreLightningAndMoreAttackSpeed() {
        final List<Modifier> normal = GemQuality.modifiers("chain_arrow", 20, "q");
        assertNotNull(normal);
        assertTrue(normal.stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && m.type() == ModType.MORE && m.tags().contains("lightning")));
        final List<Modifier> advanced = GemQuality.advancedModifiers("chain_arrow", 20, "q");
        assertNotNull(advanced);
        assertTrue(advanced.stream().anyMatch(m -> m.stat().equals(StatKeys.ATTACK_SPEED) && m.type() == ModType.MORE));
    }

    @Test
    void modifierText() {
        assertTrue(ModifierText.describe(Modifier.flat(ShockchainArrows.CHAINS_ON_AILMENT, 3, "x")).contains("多連鎖 3 次"));
        assertTrue(ModifierText.describe(Modifier.flat(ShockchainArrows.FLAG_CONSUME_SHOCK, 1, "x")).contains("不消耗電擊"));
        assertTrue(ModifierText.describe(Modifier.flat(ShockchainArrows.FLAG_HOMING, 1, "x")).contains("追蹤"));
    }
}
