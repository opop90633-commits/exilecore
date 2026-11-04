package tw.exilecore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長「先做絕境」：絕境的設定讀得起來、每回合的數字照預期往上、懲罰的代號跟 yml 對得上。 */
class UltimatumConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedSettings() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        final MapEventConfig.UltimatumSettings settings = config.ultimatum();
        assertEquals(0.2, settings.chance(), 1e-9);
        assertEquals(5, settings.rounds());
        assertEquals(12, settings.killCount(1, 1));
        assertEquals(12 + 6 * 4, settings.killCount(5, 1));
        assertEquals(18, settings.killCount(1, 2), "兩個人 ×1.5");
        assertEquals(15, settings.surviveSeconds(1), 1e-9); // 2026-11-04 照站長減半
        assertEquals(35, settings.surviveSeconds(5), 1e-9);
        assertEquals(0.5, settings.killSpawnSeconds(), 1e-9);
        assertEquals(1, settings.prepareSeconds(), 1e-9);
        assertEquals(7.5, settings.circleSeconds(1), 1e-9);
        assertEquals(1.0, settings.spawnScale(1), 1e-9);
        assertEquals(2.0, settings.spawnScale(5), 1e-9);
        assertEquals(1, settings.rewards(1));
        assertEquals(2, settings.rewards(5));
        assertEquals(1.0, settings.finalMultiplier(), 1e-9); // 2026-11-04 照站長：收手 ×1、贏了審判者 ×2
        assertEquals(100, settings.typeWeights().values().stream().mapToInt(Integer::intValue).sum());
        for (final Ultimatum.Penalty modifier : Ultimatum.Penalty.values()) {
            assertTrue(settings.modifierWeights().containsKey(modifier.id), "map_events.yml 的 ultimatum.modifiers 少了 " + modifier.id);
        }
        assertEquals(Ultimatum.Penalty.values().length, settings.modifierWeights().size(), "yml 寫了程式沒有的懲罰");
        assertEquals(2.0, settings.bossMultiplier(), 1e-9);
        // 2026-11-04 照站長：絕境給魂核符文、賭注通貨；軍團給魔偶符文
        assertEquals(12, settings.rewardWeights().get(MapEventConfig.Reward.CORE));
        assertEquals(6, settings.rewardWeights().get(MapEventConfig.Reward.WAGER));
        assertEquals(12, config.legion().rewardWeights().get(MapEventConfig.Reward.IDOL));
        final tw.exilecore.currency.CurrencyRepository currencies = new tw.exilecore.currency.CurrencyRepository();
        currencies.load(DATA);
        for (final MapRewards.Wager wager : MapRewards.WAGERS) {
            assertTrue(currencies.get(wager.currency()) != null, "賭注通貨 " + wager.currency() + " 不在 currencies.yml");
        }
        assertTrue(MapRewards.WAGERS.contains(new MapRewards.Wager("oracle_orb", 5, 1)), "神聖石 x5（2026-11-04 照站長加回來）");
    }

    /** 2026-11-04 照站長「審判者 Boss＋迷霧也要有 Boss」：maps.yml 的 event_bosses 讀得起來、招式都存在。 */
    @Test
    void eventBossesLoad() throws IOException {
        final MapConfig maps = new MapConfig();
        maps.load(DATA);
        final tw.exilecore.skill.BossSkillConfig skills = new tw.exilecore.skill.BossSkillConfig();
        skills.load(DATA);
        final tw.exilecore.monster.MonsterConfig monsters = new tw.exilecore.monster.MonsterConfig();
        monsters.load(DATA);
        for (final String id : new String[] {FogTrial.KIND, Ultimatum.BOSS_KIND, Legion.DOMAIN_KIND}) {
            final MapConfig.EventBoss boss = maps.eventBoss(id);
            assertTrue(boss != null && boss.boss().named(), "maps.yml 的 event_bosses 少了 " + id);
            assertTrue(boss.boss().skills().size() >= 10, id + "：招式太少");
            for (final tw.exilecore.skill.BossSkillUse use : boss.boss().skills()) {
                assertTrue(use.id().startsWith(id.equals(FogTrial.KIND) ? "fog_" : id.equals(Ultimatum.BOSS_KIND) ? "judge_" : "legion_"), id + " 的招式 " + use.id() + " 不是自己的（照站長：新增的 Boss 要有獨特的招式）");
            }
            for (final tw.exilecore.skill.BossSkillUse use : boss.boss().skills()) {
                assertTrue(skills.skill(use.id()) != null, id + " 的招式 " + use.id() + " 不在 boss_skills.yml");
            }
            assertEquals(3, boss.boss().phases().size(), id + "：三個階段");
            // 2026-11-04 照站長「全新的難度：DPS 1000 萬、3 分半」：災厄級——等級固定 94、生命對準建議每秒 1000 萬
            assertEquals(94, boss.level(), id + "：災厄級 94 級");
            assertEquals(4_000_000, boss.recommendedDps(), 1e-9, id + "：建議每秒 400 萬（2026-11-04 照站長；這次之前 1000 萬）");
            final double base = monsters.lifeAt(boss.level()) * monsters.rarity(tw.exilecore.monster.MonsterRarity.UNIQUE).lifeMultiplier() * boss.boss().life();
            final double multiplier = boss.recommendedDps() * MapService.RECOMMENDED_SECONDS * MapService.resistFactor(monsters, boss.boss().mods()) / base;
            assertEquals(boss.recommendedDps(), MapService.recommendedDps(monsters, boss.boss(), boss.level(), multiplier), 1, id + "：倍率算回來就是建議每秒");
            assertTrue(multiplier > 40, id + "：比巔峰（每秒 100 萬）硬得多（倍率 " + Math.round(multiplier) + "；2026-11-04 改成 400 萬之後約 72，這次之前門檻 100）");
        }
    }
}
