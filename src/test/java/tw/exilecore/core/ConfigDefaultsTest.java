package tw.exilecore.core;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隨插件附的 config.yml 跟程式裡的預設值一致：舊的 config.yml 沒有某一項時程式用預設值，
 * 新裝的伺服器拿到的 config.yml 也要是同一個值（不然兩邊的行為會不一樣）。
 */
class ConfigDefaultsTest {

    @Test
    void shippedConfigMatchesCodeDefaults() throws Exception {
        final YamlNode root = YamlNode.load(Path.of("src/main/resources/config.yml"));
        assertTrue(root.node("items").has("allow-drop"));
        assertFalse(root.node("items").bool("allow-drop", true), "預設不能把東西丟在地上（CharacterListener 的預設值）");
        assertTrue(root.node("combat").has("monster-knockback"));
        assertFalse(root.node("combat").bool("monster-knockback", true), "預設怪物打玩家不擊退（CombatModule 的預設值）");
        assertTrue(root.node("combat").node("environment").has("FALL"), "環境傷害的設定還在 combat 底下");
        assertTrue(root.node("combat").has("hold-left-click"));
        assertTrue(root.node("combat").bool("hold-left-click", false), "預設按住左鍵連續施放（HoldToCast.enabled 的預設值）");
        assertFalse(root.has("movement"), "常駐奔跑拿掉了（2026-10，改回原版：要按衝刺），config.yml 不再有 movement");
        assertTrue(root.node("web").has("economy-page"));
        assertFalse(root.node("web").bool("economy-page", true), "預設不公開市場報告網頁（2026-10，WebModule 的預設值）");
    }
}
