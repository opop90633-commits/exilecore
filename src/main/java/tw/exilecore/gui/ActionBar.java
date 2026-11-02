package tw.exilecore.gui;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tw.exilecore.ExileCore;

/**
 * 2026-10-23：所有「動作列的提示」都從這裡送（以前各處直接 {@code sendActionBar}）。
 * <p>
 * 隊伍框（{@link tw.exilecore.party.PartyHud}）是畫在動作列那一行的字（著色器把它搬到左上角），
 * 動作列一換成別的提示（裝上裝備、通貨、選單訊息…），隊伍框就跟著不見，要等下一次血量那一行（半秒一次）才回來——
 * 提示一直來的時候整個框就一直閃、一直消失。這裡把隊伍框接在提示後面一起送（淨寬 0，不影響提示的位置）。
 * 2026-11-02：快捷欄兩側的生命球、魔力球（{@link tw.exilecore.character.OrbHud}）也一樣接在後面。
 * 2026-11-02 照站長（模組 1.4）：星骸輔助模組畫提示列的人（招呼寫了 notice）改送模組的提示（可以疊好幾則、會淡出），
 * 不再整行蓋掉動作列；這次之前每次都蓋掉。
 */
public final class ActionBar {

    /** 2026-11-02（模組 1.4）：提示在模組的提示列停多久（tick；跟原版動作列一樣 3 秒，警告模組自己拉長）。 */
    public static final long MOD_NOTICE_TICKS = 60;

    private ActionBar() {
    }

    public static void send(final Audience audience, final ComponentLike message) {
        if (audience == null) {
            return;
        }
        Component frames = null;
        if (audience instanceof Player player && org.bukkit.Bukkit.isPrimaryThread()) { // 非主執行緒（資料庫回呼）不碰玩家狀態
            try {
                final ExileCore plugin = JavaPlugin.getPlugin(ExileCore.class);
                if (plugin.clientMod() != null && plugin.clientMod().drawsNotices(player)) {
                    final java.util.List<tw.exilecore.clientmod.MiniParts.Part> parts =
                            tw.exilecore.clientmod.ComponentParts.of(message.asComponent());
                    if (!tw.exilecore.clientmod.MiniParts.plainOf(parts).isBlank()) {
                        plugin.clientMod().notice(player, parts, MOD_NOTICE_TICKS);
                        return;
                    }
                }
                if (plugin.players() != null) {
                    // 2026-11-02：生命球、魔力球也接在後面（位置照提示的寬度往回推）；這次之前只接隊伍框，提示一來球就不見
                    frames = plugin.players().partyFrames(player,
                            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message.asComponent()));
                }
            } catch (final RuntimeException ignored) {
                // 插件還沒載入好（或單元測試）：只送提示
            }
        }
        audience.sendActionBar(frames == null ? message : Component.text().append(message).append(frames).build());
    }
}
