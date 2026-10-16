package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.UniqueArt;
import tw.exilecore.item.UniqueItem;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 網頁規劃器的裝備資料（/tree/items.json，2026-10）：可以穿的基底（含標籤、固定詞綴）、會出的詞綴（標籤、各階範圍）、傳奇（詞綴與範圍）。
 * 規劃器在網頁上自己配裝備（不是真的物品），跟天賦一起分享。純資料。
 */
public final class PlannerItems {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z0-9_]+)(?:/(\\d+))?}");

    private PlannerItems() {
    }

    public static byte[] json(final ItemRepository repository) {
        final JsonObject root = new JsonObject();
        final JsonArray bases = new JsonArray();
        for (final ItemBase base : repository.bases()) {
            if (!wearable(base.slot())) {
                continue;
            }
            final JsonObject b = new JsonObject();
            b.addProperty("id", base.id());
            b.addProperty("name", base.name());
            b.addProperty("slot", base.slot().name().toLowerCase(Locale.ROOT));
            b.addProperty("level", base.dropLevel());
            final JsonArray tags = new JsonArray();
            base.tags().stream().sorted().forEach(tags::add);
            b.add("tags", tags);
            if (base.isRadiusJewel()) {
                b.addProperty("radius", true); // 遺時星晶：詞綴照範圍內的小點算
            }
            if (base.implicit() != null && base.implicit().text() != null) {
                b.addProperty("implicit", plain(base.implicit().text()));
                b.add("implicitVars", ranges(base.implicit().vars()));
                b.add("implicitDiv", divisors(base.implicit().text()));
            }
            bases.add(b);
        }
        root.add("bases", bases);
        final JsonArray affixes = new JsonArray();
        for (final Affix affix : repository.affixes()) {
            if (affix.spawnTags().contains(UniqueItem.SPAWN_TAG)) {
                continue;
            }
            final JsonObject a = new JsonObject();
            a.addProperty("id", affix.id());
            a.addProperty("type", affix.type().name().toLowerCase(Locale.ROOT));
            a.addProperty("group", affix.group());
            a.addProperty("text", plain(affix.text()));
            a.add("div", divisors(affix.text()));
            final JsonArray spawn = new JsonArray();
            affix.spawnTags().stream().sorted().forEach(spawn::add);
            a.add("spawn", spawn);
            final JsonArray exclude = new JsonArray();
            affix.excludeTags().stream().sorted().forEach(exclude::add);
            a.add("exclude", exclude);
            final JsonArray tiers = new JsonArray();
            for (final AffixTier tier : affix.tiers()) {
                final JsonObject t = new JsonObject();
                t.addProperty("tier", tier.tier());
                t.addProperty("rank", affix.rank(tier.tier()));
                t.addProperty("ilvl", tier.ilvl());
                t.add("vars", ranges(tier.vars()));
                tiers.add(t);
            }
            a.add("tiers", tiers);
            affixes.add(a);
        }
        root.add("affixes", affixes);
        final JsonArray uniques = new JsonArray();
        for (final UniqueItem unique : repository.uniques()) {
            final ItemBase base = repository.base(unique.base());
            if (base == null || !wearable(base.slot())) {
                continue;
            }
            final JsonObject u = new JsonObject();
            u.addProperty("id", unique.id());
            u.addProperty("name", unique.name());
            u.addProperty("base", base.id());
            u.addProperty("slot", base.slot().name().toLowerCase(Locale.ROOT));
            u.addProperty("level", unique.dropLevel());
            u.addProperty("icon", UniqueArt.has(unique.id()));
            final JsonArray mods = new JsonArray();
            for (final String id : unique.affixIds()) {
                final Affix affix = repository.affix(id);
                if (affix == null) {
                    continue;
                }
                final JsonObject m = new JsonObject();
                m.addProperty("id", id);
                m.addProperty("text", plain(affix.text()));
                m.add("div", divisors(affix.text()));
                m.add("vars", ranges(affix.tiers().getFirst().vars()));
                mods.add(m);
            }
            u.add("mods", mods);
            final JsonArray flavour = new JsonArray();
            unique.flavour().forEach(flavour::add);
            u.add("flavour", flavour);
            uniques.add(u);
        }
        root.add("uniques", uniques);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /** 規劃器配得到的：裝備欄位的東西，還有珠寶（2026-10-16：放進天賦樹的珠寶孔）。 */
    private static boolean wearable(final ItemSlot slot) {
        return slot != null && slot != ItemSlot.MAP && slot != ItemSlot.FLASK && slot != ItemSlot.CHARM;
    }

    private static JsonObject ranges(final Map<String, int[]> vars) {
        final JsonObject out = new JsonObject();
        if (vars == null) {
            return out;
        }
        for (final Map.Entry<String, int[]> entry : vars.entrySet()) {
            final JsonArray range = new JsonArray();
            final int[] r = entry.getValue();
            range.add(Math.min(r[0], r[r.length - 1]));
            range.add(Math.max(r[0], r[r.length - 1]));
            out.add(entry.getKey(), range);
        }
        return out;
    }

    private static JsonObject divisors(final String text) {
        final JsonObject out = new JsonObject();
        final Matcher matcher = VARIABLE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            int divisor = 1;
            if (matcher.group(2) != null) {
                try {
                    divisor = Math.max(1, Integer.parseInt(matcher.group(2)));
                } catch (final NumberFormatException ignored) {
                    divisor = 1;
                }
            }
            if (!out.has(matcher.group(1))) {
                out.addProperty(matcher.group(1), divisor);
            }
        }
        return out;
    }

    /** 拿掉 MiniMessage 標籤。 */
    private static String plain(final String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "").replace("\\<", "<");
    }
}
