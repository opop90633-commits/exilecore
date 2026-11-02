package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/**
 * 2026-11-02 照站長（C 組）：招呼多寫的四個旗標（跟插件的 tw.exilecore.clientmod.FeedProtocol.flags 一樣）。純 Java（測試用）。
 * <ul>
 *   <li>{@code alerts}：模組畫 Boss 預警與換階段的機制清單（exilecore:alert），插件就不送那些大標題。</li>
 *   <li>{@code minimap}：模組畫小地圖（exilecore:minimap）。</li>
 *   <li>{@code death}：模組畫死亡回顧面板（exilecore:death），插件就不印在聊天欄。</li>
 *   <li>{@code tooltip}：模組自己整理裝備說明（精簡版＋Shift、Alt；插件不用改什麼）。</li>
 * </ul>
 * 只多欄位、協定不升（跟 1.3 的 orbs 一樣）：關掉的不寫；舊插件不認得就忽略（照舊送大標題、印聊天欄，只是模組那邊收不到新封包）。
 */
public final class Features {

    private Features() {
    }

    /** 在 {@link Protocol#hello} 做好的招呼後面加上旗標（都關掉的話原封不動）。 */
    public static byte[] withFlags(final byte[] hello, final boolean alerts, final boolean minimap, final boolean death, final boolean tooltip) {
        if (!alerts && !minimap && !death && !tooltip) {
            return hello;
        }
        try {
            final JsonObject root = JsonParser.parseString(new String(hello, StandardCharsets.UTF_8)).getAsJsonObject();
            if (alerts) {
                root.addProperty("alerts", true);
            }
            if (minimap) {
                root.addProperty("minimap", true);
            }
            if (death) {
                root.addProperty("death", true);
            }
            if (tooltip) {
                root.addProperty("tooltip", true);
            }
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final RuntimeException e) {
            return hello;
        }
    }

    /**
     * 2026-11-02 照站長（D 組）：原生畫面的旗標：{@code treeui} 天賦樹、昇華天賦；{@code atlasui} 星圖、星圖天賦
     * （插件 tw.exilecore.clientmod.UiFeed 照這兩個決定開原生畫面還是箱子選單）。只多欄位、協定不升；都關掉的話原封不動。
     */
    public static byte[] withUi(final byte[] hello, final boolean treeui, final boolean atlasui) {
        if (!treeui && !atlasui) {
            return hello;
        }
        try {
            final JsonObject root = JsonParser.parseString(new String(hello, StandardCharsets.UTF_8)).getAsJsonObject();
            if (treeui) {
                root.addProperty("treeui", true);
            }
            if (atlasui) {
                root.addProperty("atlasui", true);
            }
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final RuntimeException e) {
            return hello;
        }
    }
}
