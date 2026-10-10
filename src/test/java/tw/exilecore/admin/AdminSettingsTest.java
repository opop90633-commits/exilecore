package tw.exilecore.admin;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理網頁上能改的設定：每一項都對得到出貨的資料檔、範圍合理、檢查擋得住亂填的值。 */
class AdminSettingsTest {

    static final Path RESOURCES = Path.of("src/main/resources");
    /** 有 reload 的模組代號（ExileCore 註冊的）。 */
    static final Set<String> MODULES = Set.of("character", "monster", "event", "economy", "map", "pvp");

    @Test
    void everySettingPointsAtARealKeyWithASaneValue() throws IOException {
        final Set<String> ids = new HashSet<>();
        for (final AdminSettings.Setting setting : AdminSettings.ALL) {
            assertTrue(ids.add(setting.id()), "代號重複：" + setting.id());
            final Path file = setting.config() ? RESOURCES.resolve("config.yml") : RESOURCES.resolve("data").resolve(setting.file());
            assertTrue(Files.exists(file), setting.id() + "：沒有 " + file);
            final Object shipped = YamlNode.loadRaw(file).lookup(setting.key());
            assertNotNull(shipped, setting.id() + "：出貨的 " + file.getFileName() + " 沒有 " + setting.key());
            final Object display = setting.display(shipped);
            switch (setting.kind()) {
                case BOOLEAN -> assertTrue(display instanceof Boolean, setting.id());
                case CHOICE -> assertTrue(display instanceof String, setting.id());
                default -> {
                    final double value = ((Number) display).doubleValue();
                    assertTrue(value >= setting.min() && value <= setting.max(), setting.id() + "：出貨的值 " + value + " 不在範圍裡");
                    assertEquals(shipped instanceof Number n ? n.doubleValue() : Double.NaN,
                            ((Number) setting.parse(display, List.of())).doubleValue(), 1e-9, setting.id() + "：顯示 → 存回去要一樣");
                }
            }
            // 程式的預設值（檔案沒寫這個鍵時）也要是合法的值（經驗倍數出貨是 2，程式預設 1）
            final Object fallback = setting.display(setting.fallback());
            if (fallback instanceof Number number) {
                assertTrue(number.doubleValue() >= setting.min() && number.doubleValue() <= setting.max(), setting.id() + "：預設值不在範圍裡");
            } else {
                assertEquals(display.getClass(), fallback.getClass(), setting.id());
            }
            for (final String module : setting.reload()) {
                assertTrue(MODULES.contains(module), setting.id() + "：不認得的模組 " + module);
            }
            assertTrue(setting.config() == setting.reload().isEmpty(), setting.id() + "：資料檔的設定要重新載入模組、config.yml 的不用");
            assertTrue(!setting.label().isBlank() && !setting.help().isBlank());
        }
        // 使用者要的四組都有
        for (final AdminSettings.Group group : AdminSettings.Group.values()) {
            assertTrue(AdminSettings.ALL.stream().anyMatch(setting -> setting.group() == group), "沒有 " + group);
        }
    }

    @Test
    void parseChecksRangesAndTypes() {
        final AdminSettings.Setting xp = AdminSettings.find("xp_bonus");
        assertEquals(2.5, (Double) xp.parse(2.5, List.of()), 1e-9);
        assertEquals(3.0, (Double) xp.parse("3", List.of()), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> xp.parse(0.0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> xp.parse(11.0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> xp.parse(Double.NaN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> xp.parse("很多", List.of()));
        assertThrows(IllegalArgumentException.class, () -> xp.parse(null, List.of()));

        final AdminSettings.Setting quantity = AdminSettings.find("drop_quantity");
        assertEquals(50, quantity.parse(50.0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> quantity.parse(12.5, List.of()), "要整數");
        assertThrows(IllegalArgumentException.class, () -> quantity.parse(-5.0, List.of()));

        final AdminSettings.Setting boss = AdminSettings.find("boss_unique");
        assertEquals(0.05, (Double) boss.parse(5.0, List.of()), 0, "網頁上填 5（%），檔案存 0.05");
        assertEquals(0.025, (Double) boss.parse(2.5, List.of()), 0);
        assertEquals(5.0, (Double) boss.display(0.05), 0);
        assertEquals(0.034, (Double) boss.display(0.00034), 1e-12);

        final AdminSettings.Setting pvp = AdminSettings.find("pvp");
        assertEquals(false, pvp.parse(false, List.of()));
        assertEquals(true, pvp.parse("true", List.of()));
        assertThrows(IllegalArgumentException.class, () -> pvp.parse("也許", List.of()));

        final AdminSettings.Setting theme = AdminSettings.find("event_theme");
        assertEquals("ember_month", theme.parse("ember_month", List.of("", "ember_month")));
        assertEquals("", theme.parse("", List.of("", "ember_month")), "空的 = 照月份輪替");
        assertThrows(IllegalArgumentException.class, () -> theme.parse("no_such_theme", List.of("", "ember_month")));

        assertEquals(null, AdminSettings.find("no_such_setting"));
    }

    @Test
    void storedDecimalsStayClean() {
        final AdminSettings.Setting fee = AdminSettings.find("trade_fee");
        assertEquals("0.1", String.valueOf(AdminSettings.find("boss_unique").parse(10.0, List.of())));
        assertEquals("3.5", String.valueOf(fee.parse(3.5, List.of())));
        assertEquals("0.07", String.valueOf(AdminSettings.find("boss_unique").parse(7.0, List.of())), "不會變成 0.07000000000000001");
    }
}
