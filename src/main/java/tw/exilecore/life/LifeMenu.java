package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 星骸之海（2026-10）的選單：生活等級、精力、前往木筏、材料、製作。 */
public final class LifeMenu extends Menu {

    private static final int SLOT_INFO = 4;
    private static final int SLOT_TRAVEL = 10;
    private static final int SLOT_MATERIALS = 12;
    private static final int SLOT_HOOK = 14;
    private static final int SLOT_GUIDE = 16;
    private static final int FIRST_RECIPE = 27;
    private static final int SLOT_DRINK = 22;
    private static final int SLOT_FOOD = 24;

    private final LifeService service;
    private final Map<Integer, LifeConfig.Recipe> shown = new HashMap<>();

    public LifeMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 6, "<dark_gray>" + ItemRenderer.escape(service.config().name()));
        this.service = service;
    }

    private String color() {
        return service.config().color();
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        shown.clear();
        final LifeConfig config = service.config();
        final int level = service.level(player);
        final long xp = service.xp(player);
        final long into = xp - config.xpAt(level);
        final long need = config.xpToNext(level);
        final int size = config.raftSize(level);
        final LifeConfig.SizeStep next = config.nextSize(level);

        final List<String> info = new ArrayList<>();
        info.add("<gray>生活等級 <white>" + level + "</white>／" + config.maxLevel()
                + (need > 0 ? String.format(Locale.ROOT, " <dark_gray>（%,d／%,d）", into, need) : " <green>（滿級）"));
        info.add("<gray>精力 <white>" + service.stamina(player) + "</white>／" + config.staminaMax(level) + " <dark_gray>（每天半夜回滿）");
        info.add("<gray>木筏最大 <white>" + size + "×" + size + (next == null ? "" : "<dark_gray>（Lv." + next.level() + " → " + next.size() + "×" + next.size() + "）"));
        info.add("<gray>星索鉤：<white>" + ItemRenderer.escape(config.hook(service.hookTier(player)).name())
                + "<gray>（" + LifeService.trim(config.hook(service.hookTier(player)).range()) + " 格）");
        info.add("<gray>瓶中信 <white>" + service.lettersRead(player) + "</white>／" + config.letters().size());
        final String voyage = service.islands().status(player);
        if (voyage != null) {
            info.add("<" + color() + ">" + ItemRenderer.escape(voyage));
        }
        info.add("");
        info.add("<gray>生活等級、材料、筏都是帳號共用的");
        set(SLOT_INFO, icon(Material.OAK_BOAT, "<" + color() + ">" + ItemRenderer.escape(config.name()), info));

        final int leaderPlot = service.leaderPlot(player);
        final List<String> travel = new ArrayList<>(List.of("<gray>免費；回營地：/town", "<gray>筏上不能放技能，快捷列照原版用（島上、打淵鯨可以）", "",
                "<#f5b942>一起玩：", "<gray>/sea invite 玩家　邀請別人來你待的筏", "<gray>/sea visit 玩家　去隊友、共用、邀請你的人的筏",
                "<gray>/sea member add 玩家　共用木筏（一起蓋、收網、掌舵）", "<gray>隊伍：隊長在海上時，隊員會到隊長的筏；筏上的人一起出航、打淵鯨", ""));
        if (leaderPlot >= 0) {
            travel.add("<yellow>左鍵：去隊長的筏（" + ItemRenderer.escape(service.ownerName(leaderPlot)) + " 的）");
            travel.add("<yellow>右鍵：回自己的筏");
        } else {
            travel.add("<yellow>左鍵：傳送");
        }
        set(SLOT_TRAVEL, icon(Material.ENDER_PEARL, "<#7fd4ff>前往我的木筏", travel.toArray(new String[0])));

        final List<String> mats = new ArrayList<>();
        for (final LifeConfig.Material material : config.materials().values()) {
            mats.add("<gray>" + ItemRenderer.escape(material.name()) + " <white>" + service.material(player, material.id()));
        }
        mats.add("");
        mats.add("<dark_gray>材料記在帳號裡，不能交易");
        set(SLOT_MATERIALS, icon(Material.BARREL, "<#f5b942>材料", mats));

        set(SLOT_HOOK, icon(Material.FISHING_ROD, "<" + color() + ">拿一把星索鉤", "<gray>弄丟了可以再拿（身上有就不給）", "", "<yellow>左鍵：拿"));

        set(SLOT_GUIDE, icon(Material.WRITABLE_BOOK, "<#f5b942>怎麼玩",
                "<gray>1. 拿著星索鉤，對準從東邊漂過來的東西按右鍵",
                "<gray>2. 撈到的浮木、帆布、繩索…在下面做成筏的方塊",
                "<gray>3. 地板接在原本的地板旁邊（水面那一層）；牆、屋頂蓋在地板上",
                "<gray>4. 攔截網放在筏邊的水面上，會自動攔東西，右鍵收",
                "<gray>5. 研究台研究新東西：淨水器、種田、烤爐、料理台……",
                "<gray>6. 對著水面拋線可以釣魚；渦齒鯊來咬筏時可以放技能打牠",
                "<gray>7. 研究「航海」做海圖桌和船帆，就能開到小島探索（島上可以放技能）",
                "<gray>8. 環礁的號角碎片在海圖桌做成深淵號角，在筏上吹：深淵巨獸淵鯨",
                "<dark_gray>撈東西、做工具、出航花精力；做筏的方塊不花"));

        set(18, rowLabel(Material.CRAFTING_TABLE, "<#f5b942>製作", "左鍵做 1 次；Shift＋左鍵做 5 次（工具一次一個）"));
        int slot = FIRST_RECIPE;
        final String food = service.foodStatus(player);
        set(SLOT_FOOD, icon(Material.COOKED_COD, "<#ffb46b>料理", food == null ? "<gray>現在沒有料理的效果" : "<green>" + ItemRenderer.escape(food),
                "<gray>在筏上的烤爐、料理台做；右鍵吃", "<gray>同時只有一種（吃新的蓋掉舊的）"));
        set(SLOT_DRINK, icon(Material.POTION, "<#7fd4ff>喝一份淡水", "<gray>有 " + service.material(player, config.freshWater()) + " 份；精力 +"
                + config.drinkStamina(), "<dark_gray>淡水、料理一天最多回 " + config.dailyRestore() + " 點精力", "", "<yellow>左鍵：喝"));
        for (final LifeConfig.Recipe recipe : config.recipes()) {
            if (recipe.station() != null) {
                continue; // 料理在烤爐、料理台做
            }
            if (slot >= 45) {
                break;
            }
            shown.put(slot, recipe);
            set(slot, recipeIcon(recipe, level));
            slot++;
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack recipeIcon(final LifeConfig.Recipe recipe, final int level) {
        final LifeConfig config = service.config();
        final List<String> lore = new ArrayList<>();
        final Material material;
        final String name;
        if (recipe.hookUpgrade()) {
            final LifeConfig.Hook hook = config.hook(recipe.hook());
            material = Material.FISHING_ROD;
            name = "<" + color() + ">升級星索鉤：" + ItemRenderer.escape(hook.name());
            lore.add("<gray>撈得到 " + LifeService.trim(hook.range()) + " 格內的東西、拉得更快"
                    + (hook.doubleChance() > 0 ? String.format(Locale.ROOT, "、%.0f%% 多撈一份", hook.doubleChance() * 100) : ""));
            if (service.hookTier(player) >= recipe.hook()) {
                lore.add("<green>✔ 已經升級了");
            }
        } else {
            final LifeConfig.Item item = config.items().get(recipe.item());
            material = Material.matchMaterial(item.block()) == null ? Material.BAMBOO_PLANKS : Material.matchMaterial(item.block());
            name = "<white>" + ItemRenderer.escape(item.name()) + (recipe.amount() > 1 ? " ×" + recipe.amount() : "");
            if (!item.description().isBlank()) {
                lore.add("<gray>" + ItemRenderer.escape(item.description()));
            }
        }
        lore.add("");
        lore.add("<gray>需要：");
        boolean enough = true;
        for (final Map.Entry<String, Integer> cost : recipe.cost().entrySet()) {
            final int have = service.material(player, cost.getKey());
            enough &= have >= cost.getValue();
            lore.add((have >= cost.getValue() ? "<green>" : "<red>") + "・" + ItemRenderer.escape(service.materialName(cost.getKey())) + " ×"
                    + cost.getValue() + " <dark_gray>（有 " + have + "）");
        }
        if (recipe.stamina() > 0) {
            lore.add("<gray>・精力 " + recipe.stamina());
        }
        if (recipe.xp() > 0) {
            lore.add("<dark_gray>生活經驗 +" + recipe.xp());
        }
        lore.add("");
        final boolean locked = recipe.research() != null && !service.researched(player, recipe.research());
        if (locked) {
            final LifeConfig.Research entry = config.research().get(recipe.research());
            lore.add("<red>要先在研究台研究「" + ItemRenderer.escape(entry == null ? recipe.research() : entry.name()) + "」");
        }
        if (level < recipe.level()) {
            lore.add("<red>要生活等級 " + recipe.level());
        } else if (locked) {
            lore.add("<dark_gray>還沒研究");
        } else {
            lore.add(enough ? "<yellow>左鍵：做" + (recipe.hookUpgrade() ? "" : "　Shift＋左鍵：做 5 次") : "<dark_gray>材料不夠");
        }
        final ItemStack stack = icon(material, name, lore);
        return level < recipe.level() || locked ? stack : enough ? glowing(stack) : stack;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        if (slot == SLOT_TRAVEL) {
            player.closeInventory();
            final String problem = service.travel(player, !click.isRightClick());
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            }
            return;
        }
        if (slot == SLOT_DRINK) {
            final String problem = service.drink(player);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(problem)));
            }
            redraw();
            return;
        }
        if (slot == SLOT_HOOK) {
            final String problem = service.giveHook(player);
            player.sendMessage(plugin.text().prefixed(problem == null ? "<green>拿到星索鉤了" : "<gray>" + ItemRenderer.escape(problem)));
            return;
        }
        final LifeConfig.Recipe recipe = shown.get(slot);
        if (recipe == null || !click.isLeftClick()) {
            return;
        }
        final String problem = service.craft(player, recipe, click.isShiftClick() ? 5 : 1);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.8f, 1.2f);
        }
        redraw();
    }
}
