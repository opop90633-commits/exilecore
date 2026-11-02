package tw.exilecore.client.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（C 組）：死亡回顧（exilecore:death，格式見插件的 FeedProtocol）：插件整理好的那幾行，已經拆好顏色。純 Java（測試用）。
 * 這次之前印在聊天欄；現在模組畫在死亡畫面旁邊，重生後按鍵還叫得出來。
 */
public record DeathData(List<List<HudData.Part>> lines) {

    public static DeathData parse(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            final List<List<HudData.Part>> lines = new ArrayList<>();
            if (root.has("lines") && root.get("lines").isJsonArray()) {
                for (final JsonElement line : root.getAsJsonArray("lines")) {
                    final List<HudData.Part> parts = new ArrayList<>();
                    for (final JsonElement element : line.getAsJsonArray()) {
                        final JsonObject o = element.getAsJsonObject();
                        parts.add(new HudData.Part(o.has("t") ? o.get("t").getAsString() : "", o.has("c") ? o.get("c").getAsInt() : 0xFFFFFF));
                    }
                    lines.add(parts);
                }
            }
            return lines.isEmpty() ? null : new DeathData(lines);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 第一行是標題「━━━ ☠ 死亡回顧 ━━━…」：面板自己畫標題，這行的分隔線拿掉，只留中間的字。 */
    public String title() {
        final StringBuilder out = new StringBuilder();
        for (final HudData.Part part : lines.getFirst()) {
            out.append(part.text());
        }
        return out.toString().replace("━", "").trim();
    }

    /** 標題以外的行。 */
    public List<List<HudData.Part>> body() {
        return lines.subList(1, lines.size());
    }
}
