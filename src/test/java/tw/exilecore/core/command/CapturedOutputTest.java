package tw.exilecore.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理網頁借用遊戲裡的管理員指令：指令寫出來的訊息要收得到（2026-10）。 */
class CapturedOutputTest {

    @Test
    void messagesAreKeptAsPlainText() {
        final CapturedOutput output = new CapturedOutput(null, "網頁:測試");
        final CommandSender sender = output.sender();
        sender.sendMessage(Component.text("第一行", NamedTextColor.RED).append(Component.text("（紅色）")));
        sender.sendMessage("第二行");
        sender.sendMessage("第三行", "第四行");
        assertEquals(List.of("第一行（紅色）", "第二行", "第三行", "第四行"), output.lines());
    }

    @Test
    void behavesLikeAnAdminNamedAfterTheWebUser() {
        final CapturedOutput output = new CapturedOutput(null, "網頁:測試");
        final CommandSender sender = output.sender();
        assertEquals("網頁:測試", sender.getName());
        assertTrue(sender.hasPermission("exilecore.admin"));
        assertTrue(sender.isOp());
        assertTrue(sender.equals(sender));
        assertFalse(sender.equals(new CapturedOutput(null, "別人").sender()));
        assertTrue(output.lines().isEmpty());
    }

    @Test
    void primitivesGetSafeDefaults() {
        assertEquals(false, CapturedOutput.fallback(boolean.class));
        assertEquals(0, CapturedOutput.fallback(int.class));
        assertEquals(0L, CapturedOutput.fallback(long.class));
        assertEquals(null, CapturedOutput.fallback(String.class));
    }
}
