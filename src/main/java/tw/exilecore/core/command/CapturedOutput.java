package tw.exilecore.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把指令的輸出收起來（2026-10，管理網頁用）：做一個假的指令來源，寫給它的訊息都轉成純文字留在 {@link #lines()}，
 * 這樣網頁可以直接沿用遊戲裡管理員指令的做法（公會管理、物品追查），不用每個功能再寫一遍。
 * 這個來源什麼權限都有（只給已經確認過是管理員的請求用）。
 */
public final class CapturedOutput {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private volatile long lastAt = System.currentTimeMillis();
    private final CommandSender sender;

    public CapturedOutput(final Server server, final String name) {
        this.sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "sendMessage", "sendRichMessage", "sendPlainMessage" -> {
                            if (args != null) {
                                for (final Object arg : args) {
                                    collect(arg);
                                }
                            }
                            return null;
                        }
                        case "getName" -> {
                            return name;
                        }
                        case "name" -> {
                            return Component.text(name);
                        }
                        case "getServer" -> {
                            return server;
                        }
                        case "hasPermission", "isPermissionSet", "isOp" -> {
                            return true;
                        }
                        case "equals" -> {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "CapturedOutput(" + name + ")";
                        }
                        default -> {
                            return fallback(method.getReturnType());
                        }
                    }
                });
    }

    private void collect(final Object arg) {
        if (arg instanceof ComponentLike like) {
            add(PlainTextComponentSerializer.plainText().serialize(like.asComponent()));
        } else if (arg instanceof String text) {
            add(text);
        } else if (arg instanceof String[] texts) {
            for (final String text : texts) {
                add(text);
            }
        }
    }

    private void add(final String line) {
        lines.add(line);
        lastAt = System.currentTimeMillis();
    }

    /** 沒有特別處理的方法：照回傳型別給一個不會出事的值。 */
    static Object fallback(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    /** 拿去當指令來源。 */
    public CommandSender sender() {
        return sender;
    }

    /** 到現在為止收到的訊息（純文字，一行一則）。 */
    public List<String> lines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    /** 最後一次收到訊息的時間（毫秒）。 */
    public long lastAt() {
        return lastAt;
    }
}
