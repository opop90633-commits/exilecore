package tw.exilecore.balance;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理指令：
 * <pre>
 * /ec balance          每 5 級一行的平衡摘要＋區域 Boss，完整報表（每一級＋建議的錨點與 Boss 倍率）寫進 plugins/ExileCore/balance-report.txt
 * /ec balance &lt;等級&gt;  那個等級的明細（參考角色的裝備、輔助、每下傷害、攻速、命中、暴擊）
 * </pre>
 * 每次都重新讀 data/balance.yml，改完不用 /ec reload；怪物、技能、裝備的資料照目前載入的（改了要先 /ec reload）。
 */
public final class BalanceCommand {

    public static final String REPORT_FILE = "balance-report.txt";

    private BalanceCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("balance")
                .executes(context -> summary(plugin, context.getSource().getSender()))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 200))
                        .executes(context -> detail(plugin, context.getSource().getSender(),
                                IntegerArgumentType.getInteger(context, "level"))));
    }

    private static int summary(final ExileCore plugin, final CommandSender sender) {
        final BalanceModel model = model(plugin, sender);
        if (model == null) {
            return Command.SINGLE_SUCCESS;
        }
        final int maxLevel = plugin.characters() == null ? 60 : plugin.characters().config().levelCap();
        final List<BalanceModel.Row> rows = model.rows(maxLevel);
        final List<BalanceModel.BossRow> bosses = new ArrayList<>();
        for (final BalanceModel.BossSpec spec : bossSpecs(plugin)) {
            bosses.add(model.boss(spec));
        }
        for (final String line : BalanceReport.summary(model, rows, bosses)) {
            sender.sendMessage(plugin.text().parse(line));
        }
        final Path file = plugin.dataFolder().resolve(REPORT_FILE);
        try {
            Files.write(file, BalanceReport.full(model, rows, bosses), StandardCharsets.UTF_8);
            sender.sendMessage(plugin.text().prefixed("<gray>每一級的數字與建議的錨點寫在 <white>plugins/ExileCore/" + REPORT_FILE));
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("寫入平衡報表失敗", e);
            sender.sendMessage(plugin.text().prefixed("<red>完整報表寫入失敗（看主控台）"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int detail(final ExileCore plugin, final CommandSender sender, final int level) {
        final BalanceModel model = model(plugin, sender);
        if (model == null) {
            return Command.SINGLE_SUCCESS;
        }
        for (final String line : BalanceReport.detail(model.row(level))) {
            sender.sendMessage(plugin.text().parse(line));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** zones.yml 的區域 Boss（Boss 的等級是區域的最高等級）＋ maps.yml 的地圖 Boss（模組沒啟用就沒有）。 */
    public static List<BalanceModel.BossSpec> bossSpecs(final ExileCore plugin) {
        final List<BalanceModel.BossSpec> specs = new ArrayList<>();
        if (plugin.zones() != null) {
            specs.addAll(BalanceModel.bossSpecs(plugin.zones().config()));
        }
        if (plugin.maps() != null) {
            specs.addAll(BalanceModel.mapBossSpecs(plugin.maps().config()));
        }
        specs.addAll(extraBossSpecs(plugin.maps() == null ? null : plugin.maps().config(),
                plugin.ascendancy() == null ? null : plugin.ascendancy().config(),
                plugin.heists() == null ? null : plugin.heists().config(),
                plugin.bounties() == null ? null : plugin.bounties().config(),
                plugin.zones() == null ? null : plugin.zones().config(),
                plugin.worldEvents() == null ? null : plugin.worldEvents().config()));
        return specs;
    }

    /** 2026-10：後來加的 Boss（試煉、劫盜據點與劫盜終局、回響、極境、世界活動）；沒載入的模組傳 null。 */
    public static List<BalanceModel.BossSpec> extraBossSpecs(final tw.exilecore.map.MapConfig maps,
                                                            final tw.exilecore.ascendancy.AscendancyConfig ascendancy,
                                                            final tw.exilecore.heist.HeistConfig heist,
                                                            final tw.exilecore.bounty.BountyConfig bounties,
                                                            final tw.exilecore.zone.ZoneConfig zones,
                                                            final tw.exilecore.worldevent.WorldEventConfig events) {
        final List<BalanceModel.BossSpec> specs = new ArrayList<>();
                final int topLevel = maps == null ? 84 : maps.tiers().level(maps.tiers().max());
        if (ascendancy != null) {
            for (final tw.exilecore.ascendancy.AscendancyConfig.Trial trial : ascendancy.trials()) {
                if (trial.boss() != null) {
                    specs.add(new BalanceModel.BossSpec("trial:" + trial.id(), trial.boss().name(), trial.level(), null,
                            trial.boss().life(), trial.boss().mods()));
                }
            }
        }
        if (heist != null) {
            for (final tw.exilecore.heist.HeistConfig.Site site : heist.sites()) {
                if (site.boss() != null) {
                    specs.add(new BalanceModel.BossSpec("heist:" + site.id(), site.boss().name(), topLevel, null, site.boss().life(),
                            site.boss().mods()));
                }
            }
            final tw.exilecore.heist.HeistConfig.Site finale = heist.finale();
            if (finale != null && finale.boss() != null) {
                specs.add(new BalanceModel.BossSpec("heist:finale", finale.boss().name(), topLevel + heist.finaleLevelBonus(), null,
                        finale.boss().life() * (maps == null ? 1.0 : maps.finalLifeMultiplier(maps.tiers().max())), finale.boss().mods()));
            }
        }
        if (bounties != null && zones != null) {
            final tw.exilecore.bounty.BountyConfig.Weekly weekly = bounties.weekly();
            if (weekly != null && weekly.enabled()) {
                for (final tw.exilecore.zone.ZoneConfig.ZoneDefinition zone : zones.combatZones()) {
                    final tw.exilecore.zone.ZoneConfig.BossDefinition boss = zone.boss();
                    if (boss == null) {
                        continue;
                    }
                    final List<String> mods = new ArrayList<>(boss.mods());
                    for (final String mod : weekly.mods()) {
                        if (!mods.contains(mod)) {
                            mods.add(mod);
                        }
                    }
                    specs.add(new BalanceModel.BossSpec("echo:" + zone.id(), weekly.prefix() + boss.name(),
                            tw.exilecore.bounty.Echoes.level(zone.maxLevel(), 100, topLevel), boss.vanillaType(),
                            boss.lifeMultiplier() * weekly.life(), mods, 1.3, weekly.life()));
                }
            }
        }
        if (maps != null) {
            for (final tw.exilecore.map.MapConfig.MapDef map : maps.all()) {
                if (!map.isFinal()) {
                    continue;
                }
                final tw.exilecore.map.MapConfig.Uber uber = maps.uber(map.id());
                if (uber == null) {
                    continue;
                }
                final List<String> mods = new ArrayList<>(map.boss().mods());
                for (final String mod : uber.mods()) {
                    if (!mods.contains(mod)) {
                        mods.add(mod);
                    }
                }
                specs.add(new BalanceModel.BossSpec("uber:" + map.id(), uber.name() != null ? uber.name() : map.boss().name() + "・極境",
                        maps.uberStats(map.id(), uber).monsterLevel(), null,
                        map.boss().life() * uber.life() * maps.finalLifeMultiplier(maps.tiers().max()), mods, 2.0, uber.life()));
            }
        }
        if (events != null) {
            final tw.exilecore.worldevent.WorldEventConfig.Boss boss = events.boss();
            if (boss != null) {
                specs.add(new BalanceModel.BossSpec("world:boss", boss.name() + "（一人）", boss.level(), boss.type(), boss.life(), boss.mods(), 3.0));
            }
            final tw.exilecore.worldevent.WorldEventConfig.Invasion invasion = events.invasion();
            if (invasion != null && invasion.commander() != null) {
                specs.add(new BalanceModel.BossSpec("world:commander", invasion.commander().name() + "（一人）", invasion.levelMax(),
                        invasion.commander().type(), invasion.commander().life(), List.of(), 1.5));
            }
        }
        return specs;
    }

    /** 用目前載入的資料與最新的 balance.yml 做模型；有問題就回報並回傳 null。 */
    private static BalanceModel model(final ExileCore plugin, final CommandSender sender) {
        if (plugin.items() == null || plugin.skills() == null || plugin.monsters() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>物品、技能或怪物模組沒有啟用，算不出平衡報表"));
            return null;
        }
        final BalanceConfig config = new BalanceConfig();
        try {
            config.load(plugin.dataFolder().resolve("data"));
        } catch (final IOException | RuntimeException e) {
            sender.sendMessage(plugin.text().prefixed("<red>balance.yml 讀取失敗：" + ItemRenderer.escape(String.valueOf(e.getMessage()))));
            return null;
        }
        final BalanceModel model = new BalanceModel(plugin.items().repository(), plugin.skills().config(),
                plugin.monsters().config(), config);
        final List<String> problems = model.problems();
        if (!problems.isEmpty()) {
            for (final String problem : problems) {
                sender.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            }
            return null;
        }
        return model;
    }
}
