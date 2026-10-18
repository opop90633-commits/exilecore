package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.skill.BossAlerts;
import tw.exilecore.skill.BossSkill;
import tw.exilecore.skill.BossSkillConfig;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18：地圖事件的巔峰 Boss（七隻）的內容：每隻 3 件專屬傳奇、2 顆專屬技能寶石、3 顆專屬輔助，
 * boss_skills.yml 的招式組（代號前綴）符合巔峰 Boss 的規則（躲得掉、提示放得下、前搖短的招一下打不死）。
 */
class EventPinnacleContentTest {

    static final Path DATA = Path.of("src/main/resources/data");
    /** Boss 代號 → 招式代號的前綴。 */
    static final Map<String, String> BOSSES = Map.of(
            "shrine_warden", "shrine_", "strongbox_mimic", "box_", "breach_lord", "breach_", "exile_king", "exile_",
            "ritual_priest", "ritual_", "delirium_mirror", "mist_", "abyss_lich", "abyssal_");
    /** 跑步每秒大約幾格（跟 BossBurstTest 一樣）。 */
    static final double RUN_SPEED = 5.6;
    static ItemRepository repository;
    static SkillConfig skills;
    static MapConfig maps;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
    }

    @Test
    void everyEventBossHasThreeUniquesTwoSkillsAndThreeSupports() {
        for (final String boss : BOSSES.keySet()) {
            final List<UniqueItem> uniques = repository.uniquesFor(boss, 92);
            assertEquals(3, uniques.size(), boss + " 的專屬傳奇");
            assertTrue(repository.uniquesFor(boss, 84).isEmpty(), boss + "：專屬傳奇物品等級 85 起");
            int jewels = 0;
            for (final UniqueItem unique : uniques) {
                assertEquals(Set.of(boss), Set.copyOf(unique.sources()), unique.id());
                assertTrue(unique.weight() >= 100, unique.id());
                assertTrue(unique.affixIds().size() >= 3, unique.id());
                if (unique.base().endsWith("_jewel")) {
                    jewels++;
                }
            }
            assertEquals(1, jewels, boss + "：三件裡一件是傳奇珠寶");
            final List<SkillDefinition> gems = skills.skillsFrom(boss);
            assertEquals(2, gems.size(), boss + " 的專屬技能寶石");
            for (final SkillDefinition gem : gems) {
                assertFalse(gem.engravable(), gem.id() + "：未切割技能寶石雕不出來");
                assertEquals(boss, gem.source(), gem.id());
            }
            final List<SupportDefinition> supports = skills.supportsFrom(boss);
            assertEquals(3, supports.size(), boss + " 的專屬輔助");
            for (final SupportDefinition support : supports) {
                assertFalse(support.cuttable(), support.id() + "：未切割輔助寶石切不出來");
                assertEquals(3, support.tier(), support.id());
                assertFalse(support.notes().isEmpty(), support.id() + "：寫出是誰掉的");
                assertFalse(support.modifiers().isEmpty(), support.id());
            }
        }
        // 深淵裂縫事件（abyss）的專屬：五件、物品等級 60 起
        assertEquals(5, repository.uniquesFor("abyss", 84).size(), "深淵裂縫事件的專屬傳奇");
        assertTrue(repository.uniquesFor("abyss", 59).isEmpty(), "深淵裂縫：物品等級 60 起");
    }

    /** 每隻的招式組：8–10 招＋1 個場地危害，特殊招式躲得掉，前搖短的一下打不死（照 BossBurstTest 的算法，傷害 2.9、第三階段 ×1.3×1.4）。 */
    @Test
    void eventBossKitsAreEscapableAndCannotOneShot() {
        final BossSkillConfig bosses = skills.bossSkills();
        // 等級 87（T16＋3）普通怪一下 9.19% 生命；Boss 一下 = 傳奇倍率 2 × damage 2.9 × 這個
        final double hit = 2.0 * 2.9 * 0.091875;
        final double more = 1.3 * 1.4;
        for (final Map.Entry<String, String> entry : BOSSES.entrySet()) {
            final String prefix = entry.getValue();
            final List<BossSkill> kit = new ArrayList<>();
            double curse = 0;
            for (final BossSkill skill : bosses.skills().values()) {
                if (skill.id().startsWith(prefix) || skill.id().startsWith("hazard_" + prefix)) {
                    kit.add(skill);
                    if (skill.kind() == BossSkill.Kind.CURSE) {
                        curse = Math.max(curse, skill.taken() / 100);
                    }
                }
            }
            assertTrue(kit.size() >= 9 && kit.size() <= 11, entry.getKey() + "：8–10 招＋1 個場地危害，實際 " + kit.size());
            int hazards = 0;
            int special = 0;
            for (final BossSkill skill : kit) {
                if (skill.id().startsWith("hazard_")) {
                    hazards++;
                    assertTrue(skill.kind().hazardCapable(), skill.id());
                }
                if (BossAlerts.special(skill)) {
                    special++;
                }
                final String hint = BossAlerts.hint(skill);
                assertFalse(hint.isBlank(), skill.id());
                assertTrue(hint.codePointCount(0, hint.length()) <= BossSkill.MAX_HINT, skill.id() + " 的提示太長：" + hint);
                switch (skill.kind()) {
                    case PULL -> assertTrue(skill.explode() < skill.radius(), skill.id());
                    case SANCTUARY -> assertTrue(skill.telegraph() >= 2.5 && skill.scatter() / skill.telegraph() <= 5.5, skill.id());
                    case RING -> assertTrue(skill.gaps() > 0 || skill.width() <= 0.6 && skill.speed() <= 0.3 && skill.hint() != null, skill.id());
                    case MARK -> assertTrue(skill.explode() <= 4, skill.id());
                    case ECHOES -> assertTrue(skill.delay() >= 1.0, skill.id());
                    case ARENA -> assertTrue(skill.radius() >= 6 && skill.damage() <= 1.2, skill.id());
                    case BEAM -> assertTrue(skill.interval() >= 0.25, skill.id());
                    case NOVA -> assertTrue(skill.radius() < 12 || skill.telegraph() >= (skill.radius() - 2) / RUN_SPEED + 0.5, skill.id() + "：大新星要跑得出去");
                    default -> {
                    }
                }
                assertTrue(skill.telegraph() >= 1.0 || skill.kind() == BossSkill.Kind.SLAM && skill.telegraph() + skill.delay() >= 1.5
                        || skill.kind() == BossSkill.Kind.ZONE && skill.telegraph() >= 0.8, skill.id() + "：前搖至少 1 秒");
                // 一下打不死：前搖短的招（照 BossBurstTest）
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
                double taken = 0;
                for (final Map.Entry<tw.exilecore.stats.DamageType, Double> share : skill.typeShares().entrySet()) {
                    taken += share.getValue() * switch (share.getKey()) {
                        case PHYSICAL -> 0.75;
                        case CHAOS -> 1.0;
                        default -> 0.25;
                    };
                }
                final boolean waves = skill.kind() == BossSkill.Kind.NOVA || skill.kind() == BossSkill.Kind.CONE
                        || skill.kind() == BossSkill.Kind.RING;
                final int inOneSecond = !waves ? 1
                        : skill.stagger() <= 0 ? skill.count() : Math.min(skill.count(), (int) Math.floor(1.0 / skill.stagger() + 1e-9) + 1);
                final double fraction = skill.damage() * hit * more * (1 + curse) * taken * inOneSecond;
                assertTrue(fraction < 1.0, entry.getKey() + " 的 " + skill.id() + "（前搖 " + warning + " 秒）一下就是參考角色 "
                        + Math.round(fraction * 100) + "% 的生命");
            }
            assertEquals(1, hazards, entry.getKey() + "：一個場地危害");
            assertTrue(special >= 5, entry.getKey() + "：特殊招式夠多（每個階段至少一招），實際 " + special);
        }
    }

    /** maps.yml 接上 finals 之後：每隻是巔峰、三個階段、每個階段都有會跳大字的招、場地危害、入場券的掉落都寫齊。 */
    @Test
    void eventBossFinalsWhenSpliced() {
        final BossSkillConfig bosses = skills.bossSkills();
        int found = 0;
        for (final String id : BOSSES.keySet()) {
            final MapConfig.MapDef map = maps.find(id);
            if (map == null) {
                continue; // 另一位實作者把 res_parts/event_finals.yml 接進 maps.yml 之前先略過
            }
            found++;
            final MapConfig.Boss boss = map.boss();
            assertTrue(boss.named(), id);
            assertEquals(3, boss.phases().size(), id);
            assertTrue(boss.life() >= 50 && boss.life() <= 55, id + "：巔峰級生命");
            assertTrue(boss.damage() >= 2.8 && boss.damage() <= 3.0, id + "：巔峰級傷害");
            assertNotNull(boss.enrage(), id);
            assertEquals(1, boss.hazards().size(), id);
            final List<String> problems = new ArrayList<>();
            MapConfig.bossProblems(problems, id, boss, bosses);
            assertEquals(List.of(), problems, id);
            for (int phase = 0; phase <= boss.phases().size(); phase++) {
                boolean announced = false;
                for (final BossSkillConfig.Entry entry : bosses.kitFor(boss.type(), boss.skills())) {
                    if (entry.fromPhase() == phase && BossAlerts.special(entry.skill())) {
                        announced = true;
                    }
                }
                assertTrue(announced, id + " 第 " + phase + " 階段沒有任何會跳大字說明的招式");
            }
            final MapConfig.Pinnacle pinnacle = maps.pinnacle(id);
            assertNotNull(pinnacle, id + "：是巔峰");
            assertEquals(2, pinnacle.attempts(), id);
            assertEquals(150000, pinnacle.dpsCap(), 1e-9, id);
            assertEquals(2, pinnacle.drops().maps(), id);
            assertEquals(0.02, pinnacle.drops().uniqueChance(), 1e-9, id);
            int own = 0;
            for (final MapConfig.ExtraDrop extra : pinnacle.drops().extra()) {
                if (extra.kind().equals("support")) {
                    assertNotNull(skills.support(extra.id()), id + "：" + extra.id());
                    assertEquals(id, skills.support(extra.id()).source(), extra.id());
                    own++;
                } else if (extra.kind().equals("skill")) {
                    assertNotNull(skills.skill(extra.id()), id + "：" + extra.id());
                    assertEquals(id, skills.skill(extra.id()).source(), extra.id());
                    own++;
                }
            }
            assertEquals(5, own, id + "：自己的 3 顆輔助＋2 顆技能寶石都在 extra 裡");
        }
        assertTrue(found == 0 || found == 7, "七隻一起接進 maps.yml，實際 " + found);
    }
}
