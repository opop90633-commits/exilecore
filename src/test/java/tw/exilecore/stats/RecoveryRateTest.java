package tw.exilecore.stats;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.Leech;
import tw.exilecore.character.Recoup;
import tw.exilecore.flask.FlaskService;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 站長：「魔力再生、魔力回復有差嗎」——照 PoE2：
 * 魔力再生速度（mana_regeneration_rate_+%，mana.regen）只加自然再生；
 * 魔力回復速度（mana_recovery_rate_+%，mana.recovery_rate）加所有隨時間的回復：再生、偷取、回復（Recoup）、魔力藥劑。生命同理。
 */
class RecoveryRateTest {

    private static StatSheet mana(final double regenIncreased, final double recoveryIncreased) {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.MANA_REGEN, 10);
        if (regenIncreased != 0) {
            sheet.add(Modifier.increased(StatKeys.MANA_REGEN, regenIncreased, "測試"));
        }
        if (recoveryIncreased != 0) {
            sheet.add(Modifier.increased(TreeEffectKeys.MANA_RECOVERY_RATE, recoveryIncreased, "測試"));
        }
        return sheet;
    }

    @Test
    void regenerationRateOnlySpeedsUpRegeneration() {
        final StatSheet sheet = mana(0.5, 0);
        assertEquals(15, TreeEffectKeys.manaRegen(sheet), 1e-9, "魔力再生速度 +50%：每秒 10 → 15");
        assertEquals(1.0, TreeEffectKeys.recoveryRate(sheet, TreeEffectKeys.MANA_RECOVERY_RATE), 1e-9, "偷取、回復、藥劑不受影響");
        assertEquals(1.0, FlaskService.flaskRate(sheet, false), 1e-9, "魔力藥劑不受魔力再生速度影響");
    }

    @Test
    void recoveryRateSpeedsUpRegenerationLeechRecoupAndFlasks() {
        final StatSheet sheet = mana(0, 0.5);
        final double rate = TreeEffectKeys.recoveryRate(sheet, TreeEffectKeys.MANA_RECOVERY_RATE);
        assertEquals(1.5, rate, 1e-9);
        assertEquals(15, TreeEffectKeys.manaRegen(sheet), 1e-9, "魔力回復速度 +50%：再生也快 50%");
        // 偷取：同一份偷取，0.5 秒內回得比較多（PlayerStateService：recoverManaLeech(秒數 × 魔力回復速度)）
        final Leech normal = new Leech();
        normal.addHit(50, 1000);
        final Leech fast = new Leech();
        fast.addHit(50, 1000);
        final double before = normal.tick(0.5, 1000, 1.0);
        final double after = fast.tick(0.5 * rate, 1000, 1.0);
        assertTrue(after > before + 1e-6, "偷取變快：" + before + " → " + after);
        // 回復（Recoup）：基礎 4 秒回完；快 50% 的話 2 秒回 75%
        final Recoup recoup = new Recoup();
        recoup.add(Recoup.MANA, 80, 1.0 * rate);
        assertEquals(60, recoup.tick(40)[Recoup.MANA], 1.0); // 回完的時間取整數刻
        // 魔力藥劑：同樣的量、時間除以 1.5
        assertEquals(1.5, FlaskService.flaskRate(sheet, false), 1e-9);
        assertEquals(1.0, FlaskService.flaskRate(sheet, true), 1e-9, "魔力回復速度不影響生命藥劑");
    }

    @Test
    void lifeRecoveryRateAppliesToLifeFlasksAndMultipliesFlaskRate() {
        final StatSheet sheet = new StatSheet()
                .add(Modifier.increased(TreeEffectKeys.LIFE_RECOVERY_RATE, 0.2, "測試"))
                .add(Modifier.increased(KeystoneKeys.FLASK_LIFE_RATE, 0.5, "測試"))
                .add(Modifier.increased(TreeEffectKeys.FLASK_MANA_RATE, 0.4, "測試"));
        assertEquals(1.5 * 1.2, FlaskService.flaskRate(sheet, true), 1e-9, "藥劑生命回復速率與生命回復速度分開乘");
        assertEquals(1.4, FlaskService.flaskRate(sheet, false), 1e-9);
    }

    @Test
    void lessRecoveryRateIsAMultiplier() {
        // 心靈堡壘（Mind over Matter）：更少 50% 魔力回復速度
        final StatSheet sheet = mana(0, 0.2).add(Modifier.more(TreeEffectKeys.MANA_RECOVERY_RATE, -0.5, "測試"));
        assertEquals(0.6, TreeEffectKeys.recoveryRate(sheet, TreeEffectKeys.MANA_RECOVERY_RATE), 1e-9);
        assertEquals(6, TreeEffectKeys.manaRegen(sheet), 1e-9);
    }

    @Test
    void dataFollowsTheOriginalStatIds() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        boolean blossom = false;
        boolean mindOverMatter = false;
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : node.modifiers()) {
                // 秘能之花（Arcane Blossom）：15% increased Mana Recovery rate
                blossom |= modifier.stat().equals(TreeEffectKeys.MANA_RECOVERY_RATE) && modifier.type() == ModType.INCREASED
                        && Math.abs(modifier.value() - 0.15) < 1e-9;
                mindOverMatter |= modifier.stat().equals(TreeEffectKeys.MANA_RECOVERY_RATE) && modifier.type() == ModType.MORE
                        && Math.abs(modifier.value() + 0.5) < 1e-9;
            }
        }
        assertTrue(blossom, "天賦樹的 Mana Recovery rate 是 mana.recovery_rate");
        assertTrue(mindOverMatter, "心靈堡壘：更少 50% 魔力回復速度");
        final String timeless = Files.readString(Path.of("src/main/resources/data/passives/timeless.json"), StandardCharsets.UTF_8);
        assertTrue(timeless.contains("\"mana.recovery_rate\""), "恆時珠寶的 2% increased Mana Recovery rate");
        // 物品上的全部是 Mana Regeneration Rate（RePoE：ManaRegeneration*、AmuletImplicitManaRegeneration1…），文字寫「魔力再生速度」
        for (final String file : new String[] {"uniques.yml", "runes.yml", "affixes/suffixes.yml", "bases/jewellery.yml", "bases/armour.yml"}) {
            final String text = Files.readString(Path.of("src/main/resources/data/" + file), StandardCharsets.UTF_8);
            assertFalse(text.contains("% 魔力回復速度\""), file + "：mana.regen 的文字要寫魔力再生速度");
            assertFalse(text.contains("mana.recovery_rate"), file + "：原作物品沒有 Mana Recovery rate 的詞綴");
        }
        // 腰帶前綴 BeltFlaskLifeRecoveryRate／BeltFlaskManaRecoveryRate：回復速率（同樣的量、更快），不是回復量
        final String prefixes = Files.readString(Path.of("src/main/resources/data/affixes/prefixes.yml"), StandardCharsets.UTF_8);
        assertTrue(prefixes.contains("stat: flask.life_rate") && prefixes.contains("stat: flask.mana_rate"));
    }
}
