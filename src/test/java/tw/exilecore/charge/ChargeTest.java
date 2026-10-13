package tw.exilecore.charge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 球（charges.yml）：讀設定、每顆的效果乘顆數、獲得與過期、上限與持續時間跟著屬性表、詞綴接得上。 */
class ChargeTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static Map<String, ChargeType> types;

    @BeforeAll
    static void load() throws IOException {
        types = ChargeConfig.parse(YamlNode.load(DATA.resolve(ChargeConfig.FILE)));
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("測試", new StringReader(text));
    }

    @Test
    void shippedChargesAreThePowerFrenzyAndEnduranceTrio() {
        assertEquals(Set.of("power", "frenzy", "endurance"), types.keySet());
        assertEquals(ChargeType.Trigger.CRIT, types.get("power").trigger());
        assertEquals(ChargeType.Trigger.KILL, types.get("frenzy").trigger());
        assertEquals(ChargeType.Trigger.HIT, types.get("endurance").trigger());
        for (final ChargeType type : types.values()) {
            assertEquals(3, type.baseMax(), type.id());
            assertEquals(10, type.durationSeconds(), 1e-9, type.id());
            assertTrue(type.describe().isEmpty(), type.id() + " 本身不給屬性（2026-10 照 PoE2）");
            assertFalse(type.symbol().isBlank());
        }
        final ChargeConfig config = new ChargeConfig();
        config.replace(types);
        assertEquals(List.of(types.get("frenzy")), config.triggeredBy(ChargeType.Trigger.KILL));
        assertNotNull(config.type("endurance"));
    }

    @Test
    void shippedChargesGiveNoStatsButCustomOnesScaleWithTheNumberOfCharges() {
        for (final ChargeType type : types.values()) {
            assertTrue(type.modifiers(3).isEmpty(), type.id() + "：戰星本身沒有屬性（2026-10 照 PoE2），只算顆數");
        }
        final ChargeType custom = ChargeConfig.parse(yaml("""
                sharp:
                  name: 試星
                  trigger: crit
                  per_charge:
                    - { stat: crit.chance, type: INCREASED, value: 40 }
                    - { stat: damage, type: MORE, value: 4 }
                """)).get("sharp");
        assertTrue(custom.modifiers(0).isEmpty());
        final List<Modifier> two = custom.modifiers(2);
        assertEquals(2, two.size());
        assertEquals(StatKeys.CRIT_CHANCE, two.getFirst().stat());
        assertEquals(ModType.INCREASED, two.getFirst().type());
        assertEquals(0.80, two.getFirst().value(), 1e-9, "每顆增加 40% → 兩顆 80%");
        final StatSheet sheet = new StatSheet().addAll(custom.modifiers(3));
        assertEquals(1.04 * 1.04 * 1.04, sheet.more(StatKeys.DAMAGE, Set.of()), 0.02, "更多傷害一顆一顆疊");
    }

    @Test
    void bagGainsUpToTheCapRefreshesTheOldestAndExpiresOneByOne() {
        final ChargeBag bag = new ChargeBag();
        assertEquals(-1, bag.nextExpiry());
        assertTrue(bag.gain(0, 3, 200));
        assertTrue(bag.gain(10, 3, 200));
        assertTrue(bag.gain(20, 3, 200));
        assertEquals(3, bag.count());
        assertFalse(bag.gain(30, 3, 200), "滿了：只刷新最舊那顆，不會變多");
        assertEquals(3, bag.count());
        assertEquals(210, bag.nextExpiry(), "最舊的（0 → 200）被換成 30 → 230，現在最早消失的是 10 → 210");
        assertEquals(1, bag.expire(210));
        assertEquals(2, bag.count());
        assertEquals(0, bag.expire(219));
        assertEquals(2, bag.expire(1000), "其他的也到期了");
        assertTrue(bag.isEmpty());
        bag.gain(0, 5, 100);
        bag.gain(1, 5, 100);
        bag.gain(2, 5, 100);
        assertEquals(2, bag.trim(1), "上限變小：多的丟掉");
        assertEquals(102, bag.nextExpiry(), "留下的是最晚消失的那顆");
        assertFalse(bag.gain(5, 0, 100), "上限 0 拿不到");
        bag.clear();
        assertTrue(bag.isEmpty());
    }

    @Test
    void maxDurationAndChanceFollowTheCharacterSheet() {
        final ChargeType frenzy = types.get("frenzy");
        final StatSheet none = new StatSheet();
        assertEquals(3, frenzy.maxFor(none));
        assertEquals(200, frenzy.durationTicksFor(none), "10 秒 = 200 tick");
        assertEquals(0, frenzy.chanceFor(none), 1e-9, "沒有任何來源：一顆也拿不到");
        final StatSheet geared = new StatSheet()
                .add(Modifier.flat(StatKeys.chargeMax("frenzy"), 1, "靴子"))
                .add(Modifier.flat(StatKeys.chargeChance("frenzy"), 25, "戒指"))
                .add(Modifier.increased(StatKeys.CHARGE_DURATION, 0.5, "腰帶"));
        assertEquals(4, frenzy.maxFor(geared));
        assertEquals(300, frenzy.durationTicksFor(geared));
        assertEquals(0.25, frenzy.chanceFor(geared), 1e-9);
        assertEquals(3, types.get("power").maxFor(geared), "別種球不受影響");
        final StatSheet cursed = new StatSheet().add(Modifier.flat(StatKeys.chargeMax("frenzy"), -5, "詛咒"))
                .add(Modifier.increased(StatKeys.CHARGE_DURATION, -2, "詛咒"));
        assertEquals(0, frenzy.maxFor(cursed), "不會變負的");
        assertEquals(20, frenzy.durationTicksFor(cursed), "持續時間最短剩一成");
        assertEquals(1.0, frenzy.chanceFor(new StatSheet().add(Modifier.flat(StatKeys.chargeChance("frenzy"), 250, "測試"))), 1e-9);
    }

    @Test
    void badChargeDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChargeConfig.parse(yaml("""
                odd:
                  name: 怪球
                  trigger: jump
                  per_charge:
                    - { stat: crit.chance, type: INCREASED, value: 10 }
                """)), "觸發時機寫錯");
        assertTrue(ChargeConfig.parse(yaml("""
                odd:
                  name: 怪球
                  trigger: kill
                  per_charge: []
                """)).get("odd").modifiers(3).isEmpty(), "可以沒有效果（只算顆數）");
        assertThrows(IllegalArgumentException.class, () -> ChargeConfig.parse(yaml("""
                odd:
                  name: 怪球
                  trigger: kill
                  per_charge:
                    - { stat: crit.chance, type: INCREASED, value: "{value}" }
                """)), "每顆的數值不能用變數");
        assertThrows(IllegalArgumentException.class, () -> ChargeConfig.parse(yaml("""
                odd:
                  name: 怪球
                  trigger: kill
                  max: 0
                  per_charge:
                    - { stat: crit.chance, type: INCREASED, value: 10 }
                """)), "上限至少 1");
        final Map<String, ChargeType> custom = ChargeConfig.parse(yaml("""
                rage:
                  name: 怒氣
                  trigger: hit
                  max: 5
                  duration: 4
                  per_charge:
                    - { stat: damage, type: INCREASED, value: 2 }
                """));
        assertEquals("怒", custom.get("rage").symbol(), "沒寫代表字用名字第一個字");
        assertEquals(80, custom.get("rage").durationTicksFor(new StatSheet()));
    }

    @Test
    void everyChargeAffixPointsAtAChargeThatExists() throws IOException {
        final ItemRepository repository = new ItemRepository();
        repository.load(DATA);
        int found = 0;
        int onHit = 0;
        for (final Affix affix : repository.affixes()) {
            for (final AffixMod mod : affix.mods()) {
                for (final String prefix : List.of(StatKeys.CHARGE_CHANCE, StatKeys.CHARGE_ON_HIT, StatKeys.CHARGE_MAX)) {
                    if (mod.stat().startsWith(prefix)) {
                        found++;
                        onHit += prefix.equals(StatKeys.CHARGE_ON_HIT) ? 1 : 0;
                        assertTrue(types.containsKey(mod.stat().substring(prefix.length())), affix.id() + "：沒有這種球 " + mod.stat());
                        // 詞綴寫的字跟天賦、輔助寶石、/stats 的說明（ChargeText）一樣；
                        // 好幾條效果的詞綴（揭露的怒星：+1 上限、擊殺時機率）每一條都要寫在文字裡
                        final double value = mod.variable() == null ? mod.constant() : 7;
                        final String line = ChargeText.describe(mod.stat(), value, "");
                        if (affix.mods().size() == 1) {
                            assertEquals(affix.text().replace("{value}", "7"), line, affix.id() + " 的文字");
                        } else {
                            assertTrue(affix.text().replace("{value}", "7").contains(line), affix.id() + " 的文字要有「" + line + "」");
                        }
                    }
                }
            }
        }
        assertTrue(found >= 9, "三種球各有機率、擊中時、上限的詞綴");
        assertEquals(5, onHit, "擊中時獲得（2026-10）：怒星、銳星、磐星三條詞綴，加上兩件傳奇的「擊中時獲得怒星」");
    }

    @Test
    void consumingAndCountingSkipExpiredCharges() {
        final ChargeBag bag = new ChargeBag();
        bag.gain(0, 3, 100);  // 100 消失
        bag.gain(50, 3, 100); // 150 消失
        assertEquals(2, bag.count(99));
        assertEquals(1, bag.count(100), "到期的那一 tick 就不算（還沒被清掉也一樣）");
        assertTrue(bag.consume(60), "瞬影擊用掉一顆");
        assertEquals(1, bag.count(60));
        assertEquals(150, bag.nextExpiry(), "用掉的是最快消失的那顆，留下撐比較久的");
        assertFalse(bag.consume(150), "過期的不能拿來用");
        assertTrue(bag.isEmpty());
        assertFalse(new ChargeBag().consume(0));
    }

    @Test
    void chargeStatsReadTheSameEverywhere() {
        assertEquals("擊殺時 15% 機率獲得一顆怒星", ChargeText.describe(StatKeys.chargeChance("frenzy"), 15, ""));
        assertEquals("暴擊時 10% 機率獲得一顆銳星", ChargeText.describe(StatKeys.chargeChance("power"), 10, ""));
        assertEquals("被敵人擊中時 8% 機率獲得一顆磐星", ChargeText.describe(StatKeys.chargeChance("endurance"), 8, ""));
        assertEquals("擊中時 20% 機率獲得一顆怒星", ChargeText.describe(StatKeys.chargeOnHit("frenzy"), 20, ""));
        assertEquals("近戰擊中時 5% 機率獲得一顆磐星", ChargeText.describe(StatKeys.chargeOnHit("endurance"), 5, "近戰"));
        assertEquals("+1 怒星上限", ChargeText.describe(StatKeys.chargeMax("frenzy"), 1, ""));
        assertEquals(null, ChargeText.describe(StatKeys.CHARGE_DURATION, 20, ""), "持續時間是一般的「增加」");
        assertEquals(null, ChargeText.describe(StatKeys.LIFE_MAX, 20, ""));
        assertEquals("frenzy", ChargeText.chargeOf("charge.on_hit.frenzy"));
        assertEquals(null, ChargeText.chargeOf("charge.on_hit.Frenzy!"), "代號只能是小寫英文");
        assertEquals(List.of("power", "frenzy", "endurance"), ChargeText.ids().subList(0, 3), "照 charges.yml 的順序");
    }

    /** 2026-10：聚怒、聚銳、聚磐的說明照 charges.yml（戰星本身沒有屬性就不寫每顆效果）。 */
    @Test
    void supportDescriptionsSayWhatEachChargeGives() throws IOException {
        ChargeText.register(types.values());
        for (final String id : List.of("power", "frenzy", "endurance")) {
            assertEquals("", ChargeText.effect(id), id + "：戰星本身沒有屬性（2026-10 照 PoE2），說明不接「每顆：…」");
        }
        final tw.exilecore.skill.SkillConfig skills = new tw.exilecore.skill.SkillConfig();
        skills.load(DATA);
        // 2026-10 PoE2 改版：聚銳、聚怒、聚磐換成 charge_power、charge_frenzy、charge_endurance（說明直接寫，不用 {charge:…}）
        assertNull(skills.support("power_on_hit"));
        assertEquals("被輔助的技能擊中敵人時，有機率獲得一顆銳星。",
                skills.support("charge_power").descriptionText(), "戰星本身沒有屬性，說明不寫每顆效果");
        assertTrue(skills.support("charge_frenzy").descriptionText().contains(ChargeText.name("frenzy")));
        assertTrue(skills.support("charge_endurance").descriptionText().contains(ChargeText.name("endurance")));
        assertEquals("先 x 再 怒星", ChargeText.expand("先 {charge:x} 再 {charge:frenzy}"),
                "不認得的代號換成代號本身");
        assertEquals("沒有預留位置", ChargeText.expand("沒有預留位置"));
        assertEquals(List.of("x", "frenzy"), ChargeText.placeholders("先 {charge:x} 再 {charge:frenzy}"));
        assertEquals(List.of(), skills.chargeProblems(types::containsKey));
        // 現在的輔助都沒有用 {charge:…}：自己改一份說明來測打錯字會被抓出來
        final Path dir = java.nio.file.Files.createTempDirectory("exilecore-charge");
        java.nio.file.Files.copy(DATA.resolve("skills.yml"), dir.resolve("skills.yml"));
        final String supports = java.nio.file.Files.readString(DATA.resolve("supports.yml"));
        final String original = "有機率獲得一顆銳星。";
        assertTrue(supports.contains(original));
        java.nio.file.Files.writeString(dir.resolve("supports.yml"), supports.replace(original, "有機率獲得一顆{charge:power}。"));
        final tw.exilecore.skill.SkillConfig placeholders = new tw.exilecore.skill.SkillConfig();
        placeholders.load(dir);
        assertEquals("被輔助的技能擊中敵人時，有機率獲得一顆銳星。",
                placeholders.support("charge_power").descriptionText(), "沒有每顆效果就只換名字");
        assertEquals(List.of(), placeholders.chargeProblems(types::containsKey));
        assertEquals(1, placeholders.chargeProblems(id -> !id.equals("power")).stream().filter(line -> line.contains("說明寫了 {charge:power}")).count(),
                "說明裡打錯字也會被抓出來");
    }

    @Test
    void skillsOnlyReferToChargesThatExist() throws IOException {
        final tw.exilecore.skill.SkillConfig skills = new tw.exilecore.skill.SkillConfig();
        skills.load(DATA);
        int bypass = 0;
        int warcry = 0;
        for (final tw.exilecore.skill.SkillDefinition skill : skills.all()) {
            if (skill.cooldownBypass() != null) {
                bypass++;
                assertTrue(types.containsKey(skill.cooldownBypass()), skill.id() + "：cooldown_bypass 沒有這種球 " + skill.cooldownBypass());
            }
            if (skill.warcry() != null) {
                for (final String charge : skill.warcry().charges().keySet()) {
                    warcry++;
                    assertTrue(types.containsKey(charge), skill.id() + "：warcry.charges 沒有這種球 " + charge);
                }
            }
        }
        assertEquals(List.of(), skills.chargeProblems(types::containsKey), "伺服器紀錄不會有警告");
        assertEquals(1, skills.chargeProblems(id -> !id.equals("power")).stream().filter(line -> line.contains("cooldown_bypass")).count(),
                "打錯字會被抓出來");
        assertEquals("power", skills.skill("flicker_strike").cooldownBypass(), "瞬影擊冷卻中消耗銳星（2026-10）");
        assertEquals(1, bypass);
        assertEquals(1, warcry, "聚星戰吼拿掉了（2026-10），只剩隱藏的磐星給一種");
        for (final tw.exilecore.skill.SupportDefinition support : skills.supports()) {
            for (final Modifier modifier : support.modifiers()) {
                final String charge = ChargeText.chargeOf(modifier.stat());
                if (charge != null) {
                    assertTrue(types.containsKey(charge), support.id() + "：沒有這種球 " + modifier.stat());
                }
            }
        }
    }
}
