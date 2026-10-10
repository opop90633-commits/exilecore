package tw.exilecore.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 跳出來的輸入框（Minecraft 的 Dialog）：取代「關掉視窗、在聊天打字」。
 * 表單有文字欄位與下拉選單，按「確定」才送出；回呼一律在主執行緒上執行。
 * 文字都是 MiniMessage。
 */
public final class Dialogs {

    /** 輸入框裡的一個欄位。 */
    public sealed interface Field permits Text, Choice, Toggle {
        String key();
    }

    /** 文字欄位。 */
    public record Text(String key, String label, String initial, int maxLength) implements Field {
    }

    /** 勾選（開／關）；回答是 "true" 或 "false"。 */
    public record Toggle(String key, String label, boolean initial) implements Field {
    }

    /** 下拉選單（照順序列出），initial 是預設選中的代號。 */
    public record Choice(String key, String label, List<Option> options, String initial) implements Field {
    }

    /** 下拉選單的一個選項。 */
    public record Option(String id, String display) {
    }

    /** 說明框的按鈕：url 不是 null 時是「開網頁」，否則按下去執行 action（可為 null＝只關掉）。 */
    public record Button(String label, String tooltip, Runnable action, String url) {

        public static Button run(final String label, final Runnable action) {
            return new Button(label, null, action, null);
        }

        public static Button link(final String label, final String tooltip, final String url) {
            return new Button(label, tooltip, null, url);
        }
    }

    /** 送出的內容：欄位代號 → 文字或選項代號（沒有就是空字串）。 */
    public static final class Answers {
        private final Map<String, String> values;

        Answers(final Map<String, String> values) {
            this.values = values;
        }

        public String get(final String key) {
            return values.getOrDefault(key, "");
        }

        /** 勾選欄位的答案。 */
        public boolean on(final String key) {
            return "true".equals(values.get(key));
        }
    }

    /**
     * 欄位寬度。對話框的寬度要放得進 GUI 縮放 4 的 1080p 螢幕（480 寬）與縮放 3 的 720p（426 寬）：
     * 太寬的話客戶端會把整排按鈕推出畫面右邊、內容也跟著歪掉。
     */
    private static final int WIDTH = 260;
    /** 內文的寬度（字太長會自己換行）。 */
    private static final int BODY_WIDTH = 300;
    /** 一排按鈕最多多寬（含按鈕之間的空隙）。 */
    static final int ROW_WIDTH = 400;
    /** 按鈕的回呼只能用一次（避免連點送兩次），一小時後失效。 */
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofHours(1)).build();

    private final ExileCore plugin;

    public Dialogs(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 表單：說明＋欄位＋「確定」「取消」。
     *
     * @param onCancel 按「取消」（或 Esc）時做的事；null＝只關掉
     */
    public void form(final Player player, final String title, final List<String> body, final List<Field> fields,
                     final String confirmLabel, final Consumer<Answers> onConfirm, final Runnable onCancel) {
        final List<DialogInput> inputs = new ArrayList<>();
        for (final Field field : fields) {
            if (field instanceof Text text) {
                inputs.add(DialogInput.text(text.key(), parse(text.label()))
                        .width(WIDTH)
                        .initial(text.initial() == null ? "" : text.initial())
                        .maxLength(Math.max(1, text.maxLength()))
                        .build());
            } else if (field instanceof Choice choice) {
                final List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (final Option option : choice.options()) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(option.id(), parse(option.display()),
                            option.id().equals(choice.initial())));
                }
                inputs.add(DialogInput.singleOption(choice.key(), parse(choice.label()), entries).width(WIDTH).build());
            } else if (field instanceof Toggle toggle) {
                inputs.add(DialogInput.bool(toggle.key(), parse(toggle.label())).initial(toggle.initial())
                        .onTrue("true").onFalse("false").build());
            }
        }
        final ActionButton yes = ActionButton.builder(parse("<green>" + confirmLabel))
                .width(buttonWidth(2))
                .action(DialogAction.customClick((view, audience) -> {
                    final Answers answers = read(view, fields);
                    sync(player, audience, () -> onConfirm.accept(answers));
                }, ONCE))
                .build();
        final ActionButton no = ActionButton.builder(parse("<gray>取消"))
                .width(buttonWidth(2))
                .action(onCancel == null ? null : DialogAction.customClick((view, audience) -> sync(player, audience, onCancel), ONCE))
                .build();
        show(player, base(title, body, inputs), DialogType.confirmation(yes, no));
    }

    /** 一個文字欄位的輸入框。 */
    public void text(final Player player, final String title, final List<String> body, final String label, final String initial,
                     final int maxLength, final Consumer<String> onConfirm, final Runnable onCancel) {
        form(player, title, body, List.of(new Text("text", label, initial, maxLength)), "確定",
                answers -> onConfirm.accept(answers.get("text").trim()), onCancel);
    }

    /** 確認框：說明＋「是」「否」。 */
    public void confirm(final Player player, final String title, final List<String> body, final String yesLabel,
                        final Runnable onYes, final Runnable onNo) {
        form(player, title, body, List.of(), yesLabel, answers -> onYes.run(), onNo);
    }

    /** 說明框：內容＋幾個按鈕（開網頁、執行、或只關掉），每列 columns 個。 */
    public void notice(final Player player, final String title, final List<String> body, final List<Button> buttons, final int columns) {
        final List<ActionButton> actions = new ArrayList<>();
        for (final Button button : buttons) {
            final DialogAction action;
            if (button.url() != null) {
                action = DialogAction.staticAction(ClickEvent.openUrl(button.url()));
            } else if (button.action() != null) {
                final Runnable run = button.action();
                action = DialogAction.customClick((view, audience) -> sync(player, audience, run), ONCE);
            } else {
                action = null;
            }
            actions.add(ActionButton.builder(parse(button.label()))
                    .tooltip(button.tooltip() == null ? null : parse(button.tooltip()))
                    .width(buttonWidth(columns))
                    .action(action)
                    .build());
        }
        final ActionButton close = ActionButton.builder(parse("<gray>關閉")).width(100).build();
        show(player, base(title, body, List.of()), actions.isEmpty()
                ? DialogType.notice(close)
                : DialogType.multiAction(actions, close, Math.max(1, Math.min(MAX_COLUMNS, columns))));
    }

    /** 一排最多幾個按鈕（再多就太窄、字放不下）。 */
    static final int MAX_COLUMNS = 3;

    /** 一排 columns 個按鈕時每個多寬：一個 200、兩個 150、三個 128（原版的按鈕是 150）。 */
    static int buttonWidth(final int columns) {
        final int count = Math.max(1, Math.min(MAX_COLUMNS, columns));
        if (count == 1) {
            return 200;
        }
        return Math.min(150, (ROW_WIDTH - (count - 1) * 8) / count);
    }

    // ---- 共用 ----

    private DialogBase base(final String title, final List<String> body, final List<DialogInput> inputs) {
        final List<DialogBody> bodies = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            final List<Component> lines = new ArrayList<>();
            for (final String line : body) {
                lines.add(parse(line));
            }
            bodies.add(DialogBody.plainMessage(Component.join(JoinConfiguration.newlines(), lines), BODY_WIDTH));
        }
        return DialogBase.builder(parse(title))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(bodies)
                .inputs(inputs)
                .build();
    }

    private void show(final Player player, final DialogBase base, final DialogType type) {
        final Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(type));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isConnected()) {
                return;
            }
            // 開著的箱子視窗先關掉（倉庫這種會在關掉時把內容同步回去），伺服器這邊才不會以為視窗還開著
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
            player.showDialog(dialog);
        });
    }

    /** 讀回答：文字截到欄位的長度上限、選項只收列出來的（改過的客戶端可以送任何東西）。 */
    private static Answers read(final DialogResponseView view, final List<Field> fields) {
        final Map<String, String> values = new HashMap<>();
        for (final Field field : fields) {
            if (field instanceof Toggle toggle) {
                final Boolean on = view.getBoolean(toggle.key());
                values.put(toggle.key(), Boolean.toString(on == null ? toggle.initial() : on));
                continue;
            }
            String value = view.getText(field.key());
            value = value == null ? "" : value;
            if (field instanceof Text text) {
                final int max = Math.max(1, text.maxLength());
                if (value.length() > max) {
                    value = value.substring(0, max);
                }
            } else if (field instanceof Choice choice) {
                final String picked = value;
                if (choice.options().stream().noneMatch(option -> option.id().equals(picked))) {
                    value = choice.initial() == null ? "" : choice.initial();
                }
            }
            values.put(field.key(), value);
        }
        return new Answers(values);
    }

    /**
     * 回到主執行緒、按的人就是這個玩家、而且還是同一次連線才做
     * （回呼可以活一小時：重新登入後舊的玩家物件已經失效，不能拿它開選單或存檔）。
     */
    private void sync(final Player player, final net.kyori.adventure.audience.Audience clicker, final Runnable task) {
        if (clicker instanceof Player other && !other.getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        final Runnable guarded = () -> {
            if (player.isConnected()) {
                task.run();
            }
        };
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            guarded.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, guarded);
        }
    }

    private Component parse(final String miniMessage) {
        return plugin.text().parse(miniMessage == null ? "" : miniMessage);
    }
}
