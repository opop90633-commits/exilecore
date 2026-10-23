package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 2026-10-23：魚圖鑑（星骸之海）：每一種魚釣過幾條、最長幾公分、在哪裡（時間、海象、海域）釣得到。 */
final class LifeDexMenu extends Menu {

    private final LifeService service;

    LifeDexMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 6, "<dark_gray>魚圖鑑");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        final LifeConfig config = service.config();
        final LifeService.Profile profile = service.profile(player);
        final int level = service.level(player);
        final int species = config.fishing().fish().size();
        final int caught = profile == null ? 0 : service.extras().dexCount(profile);
        set(4, icon(Material.BOOK, "<#7fd4ff>魚圖鑑 <white>" + caught + "<gray>／" + species,
                "<gray>拿著星索鉤對著水面按右鍵釣魚；魚籠抓到的也算", "<gray>第一次釣到一種：生活經驗 +" + config.dexNewXp(),
                "<gray>每一種都釣過：稱號「" + ItemRenderer.escape(config.titles().containsKey("angler") ? config.titles().get("angler").name() : "全魚圖鑑") + "」",
                "<gray>釣魚大賽比的是「長度佔這種魚最大長度的比例」"));
        int slot = 9;
        for (final LifeConfig.Fish fish : config.fishing().fish()) {
            if (slot >= 45) {
                break;
            }
            final long[] record = profile == null ? null : profile.dex.get(fish.material());
            final boolean unlocked = level >= fish.level();
            final LifeConfig.Material material = config.materials().get(fish.material());
            final List<String> lore = new ArrayList<>();
            if (record != null) {
                lore.add("<gray>釣過 <white>" + record[0] + "</white> 條");
                lore.add("<gray>最長 <white>" + (record[1] > 0 ? String.format(Locale.ROOT, "%.1f", record[1] / 10.0) + " 公分" : "—（還沒量過）"));
            } else {
                lore.add("<dark_gray>還沒釣到過");
            }
            lore.add("<gray>長度 " + fish.size()[0] + "–" + fish.size()[1] + " 公分");
            lore.add("");
            lore.add("<gray>哪裡釣：" + where(fish));
            lore.add(unlocked ? "<dark_gray>生活等級 " + fish.level() : "<red>要生活等級 " + fish.level());
            final Material icon = record != null || unlocked ? LifeService.material(material == null ? "COD" : material.icon(), Material.COD) : Material.GRAY_DYE;
            final String name = record != null || unlocked ? "<white>" + ItemRenderer.escape(material == null ? fish.material() : material.name()) : "<dark_gray>？？？";
            final ItemStack stack = icon(icon, name, lore);
            set(slot++, record != null ? glowing(stack) : stack);
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private String where(final LifeConfig.Fish fish) {
        final LifeConfig config = service.config();
        final List<String> parts = new ArrayList<>();
        if (fish.weight() > 0 && fish.nightWeight() <= 0) {
            parts.add("白天");
        } else if (fish.weight() <= 0 && fish.nightWeight() > 0) {
            parts.add("晚上（筏燈有加成）");
        } else if (fish.nightWeight() > fish.weight()) {
            parts.add("晚上比較多（筏燈有加成）");
        }
        if (fish.weather() != null) {
            String name = fish.weather();
            for (final LifeConfig.Weather weather : config.weathers()) {
                if (weather.id().equals(fish.weather())) {
                    name = weather.name();
                }
            }
            parts.add("海象「" + name + "」的時候");
        }
        if (!fish.zones().isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final String zone : fish.zones()) {
                names.add(config.zone(zone) == null ? zone : config.zone(zone).name());
            }
            parts.add(String.join("或", names));
        }
        return parts.isEmpty() ? "哪裡都釣得到" : ItemRenderer.escape(String.join("、", parts));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
        }
    }
}
