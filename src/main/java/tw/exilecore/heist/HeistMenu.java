package tw.exilecore.heist;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 盜賊首領的選單：用合約潛入、用藍圖大劫盜、揭露隱匿詞綴、說明。
 */
public final class HeistMenu extends Menu {

    private static final int SLOT_CONTRACT = 10;
    private static final int SLOT_BLUEPRINT = 12;
    private static final int SLOT_UNVEIL = 14;
    private static final int SLOT_HELP = 16;
    private static final int SLOT_CLOSE = 22;
    private static final int SLOT_FINALE = 4;

    public HeistMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 3, "<dark_gray>盜賊首領");
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final HeistService heists = plugin.heists();
        if (heists == null) {
            set(13, icon(Material.BARRIER, "<red>劫盜模組沒有啟用"));
            set(SLOT_CLOSE, closeButton());
            return;
        }
        final int level = heists.levelFor(player);
        set(SLOT_CONTRACT, keyIcon(heists, HeistService.CONTRACT, Material.PAPER, "潛入據點（劫盜合約）",
                List.of("<gray>隨機一處據點：開金庫箱、打倒守衛、打開大金庫",
                        "<gray>怪物等級 <white>" + level + "</white><dark_gray>（打過地圖照最高階，不然照角色等級）",
                        "<gray>大金庫有機率掉<white>劫盜藍圖</white>，金庫箱也有小機率")));
        set(SLOT_BLUEPRINT, keyIcon(heists, HeistService.BLUEPRINT, Material.MAP, "大劫盜（劫盜藍圖）",
                List.of("<gray>三處據點連著打：打完一處直接送進下一處",
                        "<gray>警戒值一路帶著走，只有最後一處有大金庫",
                        "<gray>大金庫的獎勵 <white>×" + format(heists.config().settings().vault().grandMultiplier()) + "</white>")));
        set(SLOT_FINALE, keyIcon(heists, HeistFinale.KEY, Material.TRIAL_KEY, "終局：金庫之主",
                List.of("<gray>劫盜的終局 Boss（怪物等級 +" + heists.config().finaleLevelBonus() + "）",
                        "<gray>打倒之後他的收藏擺在你面前：<white>" + heists.config().finaleChoices() + " 個寶箱</white>，",
                        "<gray>每個寫著裡面是哪一件劫盜傳奇（有機率多一顆 Lv.21 未切割技能寶石）",
                        "<red>只能挑一個<gray>，挑了其他的就封鎖",
                        "<gray>鑰印：大劫盜的大金庫有機率掉")));
        set(SLOT_UNVEIL, icon(Material.SPYGLASS, "<#d4a017>揭露隱匿詞綴",
                "<gray>劫盜掉的裝備有機率帶「隱匿前綴／後綴」",
                "<gray>選一件（要先鑑定），從三個揭露詞綴裡挑一個換上去",
                "<gray>揭露詞綴只能這樣拿到；一件裝備只能有一條",
                "<gray>免費",
                "",
                "<yellow>左鍵：打開"));
        final HeistConfig.Settings settings = heists.config().settings();
        set(SLOT_HELP, icon(Material.BOOK, "<#d4a017>劫盜怎麼玩",
                "<gray>・殺怪、開金庫箱、時間都會讓<white>警戒</white>上升（畫面上方的條）",
                "<gray>・警戒滿了就<red>封鎖</red><gray>：增援每 " + settings.lockdown().reinforceSeconds() + " 秒來一波，",
                "<gray>  大金庫的獎勵 ×" + format(settings.lockdown().rewardMultiplier()),
                "<gray>・金庫箱：右鍵打開，附近的人各一份（警戒 +" + format(settings.alarm().chest()) + "）",
                "<gray>・劫盜裡掉的魔法／稀有裝備 " + Math.round(settings.veiledChance() * 100) + "% 帶隱匿詞綴",
                "<gray>・揭露石只在劫盜掉：在有空位的裝備上加一條隱匿詞綴",
                "<gray>・劫盜合約只在星圖裡掉（劫盜裡不掉）",
                "<gray>・大金庫有機率掉劫盜專屬的<#af6025>隱匿傳奇</#af6025>（" + plugin.items().repository().uniquesFor("heist", 100).size() + " 種）：",
                "<gray>  帶一條隱匿詞綴，拿來這裡三選一揭露",
                "<gray>・大金庫有 " + Math.round(settings.vault().gemChance() * 100) + "% 機率掉<#ff7a6a>Lv.21 未切割技能寶石</#ff7a6a>（技能升到 Lv.21 只能靠它）",
                "<gray>・<#7fd4ff>遺時星晶</#7fd4ff>（範圍珠寶）只在劫盜掉：金庫箱 " + percent(settings.timeLostChance()) + "、",
                "<gray>  合約的大金庫 " + percent(settings.vault().timeLostChance()) + "、藍圖的大金庫 "
                        + percent(Math.min(1, settings.vault().timeLostChance() * settings.vault().grandMultiplier())) + "（怪物等級 60 以上）",
                "<gray>・角色 " + settings.minLevel() + " 級起可以接劫盜"));
        set(SLOT_CLOSE, closeButton());
    }

    /** 機率：0.02 → 2%、0.005 → 0.5%。 */
    private static String percent(final double chance) {
        final double value = chance * 100;
        return (value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value)) + "%";
    }

    private org.bukkit.inventory.ItemStack keyIcon(final HeistService heists, final String id, final Material fallback, final String title,
                                                   final List<String> about) {
        final CurrencyType type = heists.currency(id);
        final int have = heists.count(player, id);
        final List<String> lore = new ArrayList<>(about);
        lore.add("");
        if (type == null) {
            lore.add("<red>currencies.yml 沒有 " + id);
            return icon(Material.BARRIER, "<gray>" + title, lore);
        }
        lore.add("<gray>需要 1 張<white>" + ItemRenderer.escape(type.name()) + "</white>（你有 " + have + " 張）");
        lore.add(have > 0 ? "<yellow>左鍵：出發（傳送門在地圖裝置後面）" : "<red>沒有" + ItemRenderer.escape(type.name()));
        Material material = Material.matchMaterial(type.material());
        if (material == null || !material.isItem()) {
            material = fallback;
        }
        final org.bukkit.inventory.ItemStack stack = icon(material, (have > 0 ? "<#d4a017>" : "<gray>") + title, lore);
        return have > 0 ? glowing(stack) : stack;
    }

    private static String format(final double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final HeistService heists = plugin.heists();
        switch (slot) {
            case SLOT_CONTRACT, SLOT_BLUEPRINT -> {
                if (heists == null) {
                    return;
                }
                final String problem = heists.start(player, slot == SLOT_BLUEPRINT);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                    redraw();
                } else {
                    player.closeInventory();
                }
            }
            case SLOT_FINALE -> {
                if (heists == null) {
                    return;
                }
                final String problem = heists.startFinale(player);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                    redraw();
                } else {
                    player.closeInventory();
                }
            }
            case SLOT_UNVEIL -> {
                if (heists != null) {
                    new UnveilMenu(plugin, player).open();
                }
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }
}
