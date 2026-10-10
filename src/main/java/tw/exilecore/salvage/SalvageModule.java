package tw.exilecore.salvage;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** salvage 模組：拆解台（拆解師 NPC 打開 {@link SalvageMenu}）與鎖定裝備（/lock，{@link LockMenu}），設定在 data/salvage.yml。 */
public final class SalvageModule implements Module {

    private final ExileCore plugin;
    private final SalvageConfig config = new SalvageConfig();
    private boolean enabled;

    public SalvageModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "salvage";
    }

    @Override
    public void enable() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        // 2026-10：裝備拆出來的金幣跟被掉落過濾換的一樣（掉落模組沒啟用就照 salvage.yml）
        config.equipmentGold((rarity, level) -> plugin.loot() == null || plugin.monsterModule() == null ? 0
                : plugin.loot().equipmentGold(rarity, level));
        enabled = true;
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
    }

    @Override
    public void disable() {
        enabled = false;
    }

    /** 模組沒啟用（設定檔寫錯）時為 null。 */
    public SalvageConfig config() {
        return enabled ? config : null;
    }

    /** /lock：鎖定或解除鎖定裝備（拆解台、市集、交易、垃圾桶都不收鎖定的）。 */
    public static LiteralCommandNode<CommandSourceStack> lockCommand(final ExileCore plugin) {
        return Commands.literal("lock")
                .executes(context -> {
                    if (context.getSource().getExecutor() instanceof Player player) {
                        new LockMenu(plugin, player).open();
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
