package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-18：地圖裝置的「事件巔峰」子選單：列出用地圖事件入場券開的巔峰 Boss（maps.yml 的 finals 底下 pinnacle.ticket 的那些），
 * 圖示是那張入場券；寫 Boss 是誰、哪一種事件掉券、你有幾張、Boss 的階段與機制。點一下（有券、要先打贏的都打贏了）→ 確認後用掉
 * 1 張券打開傳送門（{@link MapService#activatePinnacle}，跟萬界之巔同一套：只有 Boss 房、每一門每個人只能進一次）。
 */
final class EventPinnacleMenu extends Menu {

    private static final int ROWS = 4;
    /** 七隻 Boss 放第二排（10–16）；多的接著往下排。 */
    private static final int FIRST_SLOT = 10;
    private static final int SLOT_INFO = 27;
    private static final int SLOT_BACK = 35;

    private final MapService service;
    /** 每一格對應的巔峰。 */
    private final java.util.Map<Integer, MapConfig.MapDef> shown = new java.util.HashMap<>();

    EventPinnacleMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, ROWS, "<#c77dff>事件巔峰");
        this.service = service;
    }

    @Override
    protected void draw() {
        shown.clear();
        for (int i = 0; i < ROWS * 9; i++) {
            set(i, filler());
        }
        final MapConfig config = service.config();
        final Set<String> completed = service.completed(player);
        final List<MapConfig.MapDef> pinnacles = config.eventPinnacles();
        int slot = FIRST_SLOT;
        int openable = 0;
        for (final MapConfig.MapDef map : pinnacles) {
            if (slot >= SLOT_INFO) {
                break;
            }
            final boolean ready = service.pinnacleProblem(player, map) == null;
            openable += ready ? 1 : 0;
            shown.put(slot, map);
            set(slot, bossIcon(config, map, completed, ready));
            slot++;
            if ((slot + 1) % 9 == 0) { // 跳過每排的最右邊與下一排的最左邊
                slot += 2;
            }
        }
        set(SLOT_INFO, icon(Material.BOOK, "<white>事件巔峰怎麼打",
                "<gray>完成地圖事件（神龕、寶箱、裂隙、流寇、祭壇、幻霧、深淵裂縫）時，",
                "<gray>有份的人各擲一次那種事件的入場券（固定機率，不吃加成）",
                "<gray>一張券開一次：固定開在 T" + config.tiers().max() + " 再高幾級，只有 Boss 房，",
                "<gray>一進去下一間就是 Boss；兩道門＝兩次挑戰",
                "<red>每一門每個人只能走進去一次<dark_gray>（死了或離開要等這一門結束、走下一門）",
                "<gray>掉落：最高階地圖、Boss 自己的傳奇、專屬技能與輔助寶石",
                "<dark_gray>能開的有 " + openable + " 隻（亮著的）"));
        set(SLOT_BACK, backButton("地圖裝置"));
    }

    /** 一隻 Boss 的格子：入場券的圖示，說明寫 Boss、券的來源、你有幾張、機制。 */
    private ItemStack bossIcon(final MapConfig config, final MapConfig.MapDef map, final Set<String> completed, final boolean ready) {
        final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
        final CurrencyType ticket = plugin.currencies() == null ? null : plugin.currencies().repository().get(pinnacle.ticket());
        final int have = ticket == null ? 0 : service.countCurrency(player, ticket);
        final boolean done = completed.contains(map.id());
        final MapConfig.Boss boss = map.boss();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + ItemRenderer.escape(map.lore()));
        lore.add("<gray>Boss：<#c77dff>" + ItemRenderer.escape(boss.name()) + (done ? " <green>✔ 已打贏" : ""));
        lore.add("<gray>怪物等級 <white>" + config.pinnacleStats(map.id(), pinnacle).monsterLevel() + "<gray>・" + pinnacle.attempts() + " 道門（" + pinnacle.attempts() + " 次挑戰）");
        lore.add(MapService.recommendedText(service.pinnacleRecommendedDps(map))); // 2026-10-21
        lore.add("");
        final String ticketName = ticket == null ? pinnacle.ticket() : ticket.name();
        lore.add((have >= 1 ? "<green>" : "<red>") + "入場券：" + ItemRenderer.escape(ticketName) + " ×" + have + "<gray>（開一次用 1 張）");
        lore.add("<dark_gray>" + ItemRenderer.escape(service.ticketSourceText(pinnacle.ticket())));
        final String missing = Atlas.missingRequirements(config, map, completed);
        if (!missing.isEmpty()) {
            lore.add("<red>先打贏 " + ItemRenderer.escape(missing));
        }
        lore.add("");
        lore.addAll(mechanics(boss));
        lore.add("");
        if (ready) {
            lore.add("<green>點一下打開傳送門（會用掉 1 張" + ItemRenderer.escape(ticketName) + "）");
        } else {
            final String problem = service.pinnacleProblem(player, map);
            lore.add("<dark_gray>" + ItemRenderer.escape(problem == null ? "現在開不了" : problem));
        }
        final String title = (ready ? "<#c77dff>" : "<gray>") + ItemRenderer.escape(boss.name()) + " <dark_gray>（" + ItemRenderer.escape(map.name()) + "）";
        final ItemStack stack;
        if (ticket != null) {
            // 圖示＝那張入場券的材質與貼圖（跟真的券長得一樣，但不是真的通貨；同地圖裝置的品質按鈕）
            final Material material = ticket.material() == null ? null : Material.matchMaterial(ticket.material());
            stack = tw.exilecore.currency.CurrencyService.decorate(
                    icon(material == null || !material.isItem() ? Material.TRIAL_KEY : material, title, lore), ticket);
        } else {
            stack = icon(Material.TRIAL_KEY, title, lore);
        }
        if (have > 0) {
            stack.setAmount(Math.max(1, Math.min(64, have)));
        }
        return ready ? glowing(stack) : stack;
    }

    /** Boss 的階段與機制摘要（{@link BossMechanicText} 的寫法）：每個階段一行、狂暴、場地危害。 */
    private List<String> mechanics(final MapConfig.Boss boss) {
        final List<String> lines = new ArrayList<>();
        final List<MapConfig.Phase> phases = boss.phases();
        if (phases.isEmpty() && boss.enrage() == null && boss.hazards().isEmpty()) {
            return lines;
        }
        lines.add("<#f5b942>【機制】");
        for (int i = 0; i < phases.size(); i++) {
            final MapConfig.Phase phase = phases.get(i);
            // 這個階段才開始用的招式
            final List<String> newSkills = new ArrayList<>();
            for (final tw.exilecore.skill.BossSkillUse use : boss.skills()) {
                if (use.fromPhase() == i + 1) {
                    final String name = skillName(use.id());
                    if (name != null && !newSkills.contains(name)) {
                        newSkills.add(name);
                    }
                }
            }
            final List<String> notes = BossMechanicText.phaseNotes(phase, newSkills);
            lines.add("<gray>血量 " + Math.round(phase.at() * 100) + "% 第 " + (i + 1) + " 階段「<white>" + ItemRenderer.escape(phase.name()) + "<gray>」："
                    + ItemRenderer.escape(String.join("；", notes)));
        }
        final MapConfig.Enrage enrage = boss.enrage();
        if (enrage != null) {
            final long seconds = Math.round(enrage.after());
            lines.add("<gray>開打 " + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60) + " 後狂暴"
                    + (enrage.damage() > 0 ? "（傷害更多 " + Math.round(enrage.damage()) + "%）" : ""));
        }
        final List<String> hazards = new ArrayList<>();
        for (final tw.exilecore.skill.BossHazard hazard : boss.hazards()) {
            final String name = skillName(hazard.skill());
            if (name != null && !hazards.contains(name)) {
                hazards.add(name);
            }
        }
        if (!hazards.isEmpty()) {
            lines.add("<gray>場地危害：" + ItemRenderer.escape(String.join("、", hazards)));
        }
        return lines;
    }

    /** 招式代號 → 名稱（boss_skills.yml 沒有就 null）。 */
    private String skillName(final String id) {
        if (plugin.skills() == null) {
            return null;
        }
        final tw.exilecore.skill.BossSkill skill = plugin.skills().config().bossSkills().skill(id);
        return skill == null ? null : skill.name();
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            goBack();
            return;
        }
        final MapConfig.MapDef map = shown.get(slot);
        if (map == null || !acceptAction(click)) {
            return;
        }
        final String problem = service.pinnacleProblem(player, map);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            return;
        }
        final MapConfig config = service.config();
        final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
        final CurrencyType ticket = plugin.currencies().repository().get(pinnacle.ticket());
        player.closeInventory();
        plugin.dialogs().confirm(player, "<#c77dff>" + ItemRenderer.escape(map.boss().name()),
                List.of("<gray>要用掉 1 張" + ItemRenderer.escape(ticket == null ? pinnacle.ticket() : ticket.name()) + "，開在 T" + config.tiers().max()
                                + " 再高幾級（怪物等級 " + config.pinnacleStats(map.id(), pinnacle).monsterLevel() + "）",
                        "<gray>只有 Boss 房：一進去下一間就是 Boss；" + pinnacle.attempts() + " 道門＝" + pinnacle.attempts() + " 次挑戰",
                        "<red>每一門每個人只能走進去一次：死了或離開（包括回營地）要等這一門結束、走下一門",
                        "<dark_gray>準備好藥劑與抗性再進去；隊友也是各進一次"),
                "打開傳送門",
                () -> {
                    final String failed = service.activatePinnacle(player, map);
                    if (failed != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(failed)));
                    }
                }, null);
    }

    @Override
    protected void goBack() {
        new MapDeviceMenu(plugin, player, service).open();
    }
}
