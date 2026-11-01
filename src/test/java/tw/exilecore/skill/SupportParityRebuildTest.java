package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.SupportParityKeys;
import tw.exilecore.stats.TreeConditions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 把 13 顆機制近似的輔助改成跟原作一樣（瘟潰、磁暴、靜電、震風、脆晶、震裝、咒爆、遠釘、蓄嘯、穩擊、凍地、魂收、骸令）。 */
class SupportParityRebuildTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static boolean has(final String support, final String stat) {
        return config.support(support).modifiers().stream().anyMatch(m -> m.stat().equals(stat));
    }

    @Test
    void lockdownScalesWithDistance() {
        assertEquals(2.2, SupportParityKeys.lockdownMultiplier(120, 0), 1e-9);
        assertEquals(2.2, SupportParityKeys.lockdownMultiplier(120, 2), 1e-9, "2 公尺內更多 120%");
        assertEquals(1.6, SupportParityKeys.lockdownMultiplier(120, 7), 1e-9, "中間線性");
        assertEquals(1.0, SupportParityKeys.lockdownMultiplier(120, 12), 1e-9, "12 公尺不變");
        assertEquals(1.0, SupportParityKeys.lockdownMultiplier(120, 30), 1e-9);
        assertEquals(1.0, SupportParityKeys.lockdownMultiplier(0, 1), 1e-9);
        assertTrue(has("lockdown", SupportParityKeys.PIN_LOCKDOWN));
        assertFalse(has("lockdown", StatKeys.PIN_CHANCE), "原作 Lockdown 本身不給定身");
        assertTrue(config.support("lockdown").requires().contains("spell"), "任何擊中技能（不再只限投射物攻擊）");
    }

    @Test
    void icicleHalvesCrystalLife() {
        assertEquals(2, SupportParityKeys.crystalHits(3, 0.5), "三下的冰晶生命少一半：要兩下");
        assertEquals(3, SupportParityKeys.crystalHits(6, 0.5), "厚晶 6 下 → 3 下");
        assertEquals(3, SupportParityKeys.crystalHits(3, 1.0));
        assertEquals(1, SupportParityKeys.crystalHits(1, 0.5));
        assertTrue(config.support("brittle_crystal").modifiers().stream()
                .anyMatch(m -> m.stat().equals(SupportParityKeys.CRYSTAL_LIFE) && m.value() < 0));
        assertFalse(has("brittle_crystal", StatKeys.CRYSTAL_HITS));
    }

    @Test
    void burstingPlagueStoresPoisonUpToCap() {
        assertEquals(50, PlagueBursts.gain(100, 100, 0.5), 1e-9, "每秒中毒傷害 100% 存成瘟疫");
        assertEquals(20, PlagueBursts.gain(40, 100, 0.5), 1e-9, "只算被輔助的毒");
        assertEquals(10, PlagueBursts.gain(100, 20, 0.5), 1e-9, "只算真的在扣血的");
        assertEquals(200, PlagueBursts.cap(1000), 1e-9, "上限最大生命 20%");
        assertEquals(1.0, PlagueBursts.radiusScale(0, 200), 1e-9);
        assertEquals(Math.sqrt(2), PlagueBursts.radiusScale(200, 200), 1e-9, "滿的時候範圍更多 100%");
        assertEquals(Math.sqrt(2), PlagueBursts.radiusScale(500, 200), 1e-9);
        final List<double[]> poisons = new ArrayList<>(List.of(new double[] {30, 100}, new double[] {20, 50}));
        assertEquals(30, PlagueBursts.activeDps(poisons, 60), 1e-9, "結束的毒不算");
        assertEquals(1, poisons.size());

        final PlagueBursts plague = new PlagueBursts();
        final UUID target = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        plague.ticked(target, owner, 100, 0.5, 0); // 沒被輔助的毒：不存
        plague.poisoned(target, owner, "toxic_shot", 40, 100, 0);
        plague.ticked(target, owner, 100, 0.5, 10);
        plague.ticked(target, UUID.randomUUID(), 100, 0.5, 10); // 別人的毒不算進這個玩家的
        assertEquals(20, plague.died(target).get(owner).amount, 1e-9);
        assertTrue(has("pest_rupture", SupportProcs.PREFIX + SupportProcs.PLAGUE_DEATH));
    }

    @Test
    void impendingDoomWaitsForTheSameCurseToEnd() {
        final UUID source = UUID.randomUUID();
        final Debuffs.Debuff placed = new Debuffs.Debuff("weaken_curse", source, 200, 0, false);
        assertTrue(SupportProcs.sameCurseStill(List.of(placed), List.of(placed)));
        assertTrue(SupportProcs.sameCurseStill(List.of(new Debuffs.Debuff("weaken_curse", source, 200, 0.3, false)), List.of(placed)),
                "效果被放大過還是同一個");
        assertFalse(SupportProcs.sameCurseStill(List.of(new Debuffs.Debuff("weaken_curse", source, 400, 0, false)), List.of(placed)),
                "重放過（結束時間變了）就等新的那個");
        assertFalse(SupportProcs.sameCurseStill(List.of(), List.of(placed)));
        assertTrue(has("curse_blast", SupportProcs.PREFIX + SupportProcs.CURSE_EXPIRED));
        assertFalse(has("curse_blast", SupportProcs.PREFIX + "use"));
    }

    @Test
    void triggersMatchRef() {
        assertTrue(has("magnetic_storm", SupportProcs.PREFIX + SupportProcs.SHOCK_CLUSTER));
        assertTrue(config.support("still_charge").modifiers().stream()
                .anyMatch(m -> m.stat().equals(SupportProcs.PREFIX + SupportProcs.SHOCK) && m.value() >= 100), "讓敵人感電時 100%");
        assertTrue(has("quake_gale", SupportProcs.PREFIX + SupportProcs.SELF_STUNNED));
        assertTrue(has("jolt_load", StatKeys.FLAG_RELOAD_ON_STUN));
        assertFalse(has("jolt_load", StatKeys.STUN_BUILDUP), "原作沒有暈眩累積");
        assertEquals(4, config.skill("proc_static_orb").params().zone().duration(), 1e-9);
    }

    @Test
    void dazzleOnlyAgainstDazed() {
        final Modifier always = config.support("steady_strike").modifiers().stream().filter(m -> m.stat().equals(StatKeys.FLAG_ALWAYS_HIT))
                .findFirst().orElseThrow();
        assertTrue(always.tags().contains(TreeConditions.VS_DAZED));
        assertTrue(has("steady_strike", SupportParityKeys.FLAG_CONSUME_DAZE));
        assertTrue(has("steady_strike", SupportParityKeys.FLAG_CANNOT_DAZE));
        assertFalse(has("steady_strike", StatKeys.DAMAGE), "拿掉原作沒有的更少傷害");
    }

    @Test
    void premeditationBiddingEssenceHarvest() {
        assertTrue(has("stored_howl", SupportParityKeys.WARCRY_EMPOWERED_MORE));
        assertFalse(has("stored_howl", StatKeys.GRANT_PREFIX + StatKeys.DAMAGE));
        assertTrue(config.support("bone_command").modifiers().stream()
                .anyMatch(m -> m.stat().equals(StatKeys.COOLDOWN_RECOVERY) && m.value() > 0), "冷卻回復速度增加 20%");
        assertFalse(has("bone_command", StatKeys.SKILL_COOLDOWN));
        assertNotNull(config.support("soul_reap").refusal(config.skill("corpse_summon")), "不是常駐召喚物");
        assertEquals(null, config.support("soul_reap").refusal(config.skill("summon_skeletons")));
    }
}
