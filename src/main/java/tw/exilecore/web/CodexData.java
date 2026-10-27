package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.skill.ModifierText;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 網頁圖鑑的資料（2026-10，/codex）：技能、輔助寶石、通貨的名稱與說明——都是遊戲裡看得到的文字，不含掉落率這種數值。
 * 傳奇裝備網頁直接用規劃器的 tree/items.json（{@link PlannerItems}）。啟動與重載時算一次。
 * <p>
 * 2026-10：技能附上標籤的中文名（{@code tagNames}，網頁顯示成小標籤、可以篩選）與能掛的輔助寶石（{@code supports}：
 * 照遊戲裡同一套規則 {@link SupportDefinition#supports} 算，點技能就列出來）。
 */
public final class CodexData {

    private static final Gson GSON = new Gson();

    private CodexData() {
    }

    /**
     * 一個技能的圖鑑資料（網頁圖鑑、2026-10-27 起網頁規劃器的昇華賦予技能預覽共用）。
     *
     * @param tagNamesIn 標籤代號 → 中文名稱，順便收集；null = 寫在這個技能自己的 tagNames 裡
     */
    public static JsonObject skillJson(final ExileCore plugin, final SkillDefinition skill, final java.util.Collection<SupportDefinition> gems,
                                       final JsonObject tagNamesIn) {
        final JsonObject tagNames = tagNamesIn == null ? new JsonObject() : tagNamesIn;
        final JsonObject s = new JsonObject();
        s.addProperty("id", skill.id());
        s.addProperty("name", skill.name());
        s.addProperty("type", skill.type() == null ? "" : skill.type().name().toLowerCase(Locale.ROOT));
        s.addProperty("tier", skill.tier());
        s.addProperty("text", plain(tw.exilecore.charge.ChargeText.expand(skill.description())));
        final JsonArray weapons = new JsonArray();
        if (skill.weapons() != null) {
            skill.weapons().stream().sorted().forEach(weapons::add);
        }
        s.add("weapons", weapons);
        final JsonArray tags = new JsonArray();
        if (skill.tags() != null) {
            skill.tags().stream().sorted().forEach(tag -> {
                tags.add(tag);
                if (!tagNames.has(tag)) {
                    tagNames.addProperty(tag, ModifierText.tagName(tag));
                }
            });
        }
        s.add("tags", tags);
        final JsonArray fits = new JsonArray();
        for (final SupportDefinition support : gems) {
            if (support.supports(skill)) {
                fits.add(support.id());
            }
        }
        s.add("supports", fits);
        final JsonArray effects = new JsonArray(); // 2026-10：效果文字（光環、戰吼、印記…；搜尋也找得到）
        try {
            for (final String text : tw.exilecore.gui.CodexMenu.skillEffectTexts(skill)) {
                effects.add(plain(text));
            }
        } catch (final RuntimeException ignored) {
            // 寫不出來的略過
        }
        s.add("effects", effects);
        s.addProperty("kind", skill.summon() != null ? "summon" : skill.aura() != null ? "aura" : skill.warcry() != null ? "warcry" : "");
        if (skill.cooldown() > 0) {
            s.addProperty("cooldown", skill.cooldown());
        }
        // 2026-10-22（補齊遊戲裡圖鑑有的）：來源（未切割寶石雕得出來／專屬掉落）、施放時間、各等級的基礎數值
        if (!skill.engravable()) {
            s.addProperty("source", plain(tw.exilecore.gui.CodexMenu.skillSourceLabel(plugin, skill))); // 2026-10-27：昇華專屬寫出是哪個昇華
        }
        if (skill.isAttack()) {
            if (skill.attackSpeed() > 0 && Math.abs(skill.attackSpeed() - 1) > 1e-9) {
                s.addProperty("attackSpeed", Math.round(skill.attackSpeed() * 100));
            }
        } else if (skill.castTime() > 0) {
            s.addProperty("castTime", skill.castTime());
        }
        final JsonArray levels = new JsonArray();
        for (final int level : new int[] {1, 10, 20, 30, 40}) {
            try {
                final JsonObject row = new JsonObject();
                row.addProperty("lv", level);
                row.addProperty("mana", tw.exilecore.skill.SkillProfiles.manaCost(skill, level));
                if (skill.isAttack()) {
                    row.addProperty("eff", Math.round(skill.effectiveness().at(level) * 100));
                } else if (skill.damage() != null) {
                    final double[] range = tw.exilecore.skill.SkillProfiles.spellDamage(plugin.skills().config(), skill, level);
                    row.addProperty("min", Math.round(range[0]));
                    row.addProperty("max", Math.round(range[1]));
                    final double eff = skill.effectiveness().at(level);
                    if (Math.abs(eff - 1) > 1e-9) {
                        row.addProperty("eff", Math.round(eff * 100));
                    }
                }
                levels.add(row);
            } catch (final RuntimeException ignored) {
                // 算不出來的等級略過
            }
        }
        s.add("levels", levels);
        // 2026-10-26 照 PoE2：技能寶石的屬性分配與需求（GemAttributes；武器賦予的沒有）
        if (tw.exilecore.skill.GemAttributes.isGem(skill)) {
            final tw.exilecore.skill.GemAttributes.Weights weights = tw.exilecore.skill.GemAttributes.weights(skill);
            final JsonObject attr = new JsonObject();
            attr.addProperty("str", weights.str());
            attr.addProperty("dex", weights.dex());
            attr.addProperty("int", weights.intelligence());
            s.add("attr", attr);
            s.addProperty("colour", String.valueOf(colourCode(weights.primary())));
            final JsonArray reqs = new JsonArray();
            for (final int level : new int[] {1, 5, 10, 15, 20}) {
                final tw.exilecore.skill.GemAttributes.Requirement req =
                        tw.exilecore.skill.GemAttributes.base(plugin.skills().config(), skill, level);
                final JsonObject row = new JsonObject();
                row.addProperty("lv", level);
                row.addProperty("level", level <= 1 ? 0 : plugin.skills().config().requiredLevel(level));
                row.addProperty("str", req.str());
                row.addProperty("dex", req.dex());
                row.addProperty("int", req.intelligence());
                reqs.add(row);
            }
            s.add("requirements", reqs);
        }
        if (tagNamesIn == null) {
            s.add("tagNames", tagNames);
        }
        return s;
    }

    /** 2026-10-26：寶石顏色的代號（r／g／b；白色 w）。 */
    static char colourCode(final char colour) {
        return colour == 0 ? 'w' : colour;
    }

    public static byte[] json(final ExileCore plugin) {
        final JsonObject root = new JsonObject();
        final JsonArray skills = new JsonArray();
        final JsonArray supports = new JsonArray();
        final JsonObject tagNames = new JsonObject();
        if (plugin.skills() != null) {
            final List<SupportDefinition> gems = new ArrayList<>(plugin.skills().config().supports());
            gems.sort(Comparator.comparingInt(SupportDefinition::tier).thenComparing(SupportDefinition::name));
            gems.removeIf(support -> support.name() == null || support.name().isBlank());
            final List<SkillDefinition> all = new ArrayList<>(plugin.skills().config().all());
            all.sort(Comparator.comparingInt(SkillDefinition::tier).thenComparing(SkillDefinition::name));
            for (final SkillDefinition skill : all) {
                if (skill.hidden() || skill.id().startsWith("proc_") || skill.name() == null || skill.name().isBlank()) {
                    continue; // 輔助觸發的、內部用的不列
                }
                skills.add(skillJson(plugin, skill, gems, tagNames));
            }
            for (final SupportDefinition support : gems) {
                final JsonObject s = new JsonObject();
                s.addProperty("id", support.id());
                s.addProperty("name", support.name());
                s.addProperty("tier", support.tier());
                s.addProperty("text", plain(support.descriptionText()));
                s.addProperty("requires", plain(support.supportsText(plugin.skills().config().all()))); // 含「除外」：召喚物、光環…
                if (!support.cuttable()) { // 2026-10-22：專屬掉落的寫來源
                    s.addProperty("source", plain(tw.exilecore.gui.CodexMenu.sourceLabel(plugin, support.source())));
                }
                final JsonArray mods = new JsonArray(); // 2026-10-22：效果的數值（跟角色頁一樣的寫法）
                try {
                    for (final String line : ModifierText.describeAll(support.allModifiers())) {
                        mods.add(plain(line));
                    }
                } catch (final RuntimeException ignored) {
                    // 寫不出來的略過
                }
                s.add("mods", mods);
                // 2026-10-26 照 PoE2：輔助寶石的顏色（r 紅＝力量、g 綠＝敏捷、b 藍＝智慧、w 白）與加給技能寶石的需求
                final char colour = tw.exilecore.skill.SupportColours.colour(support.id());
                s.addProperty("colour", String.valueOf(colourCode(colour)));
                s.addProperty("attrName", tw.exilecore.skill.GemAttributes.label(colour));
                s.addProperty("requirement", colour == 0 ? 0 : tw.exilecore.skill.GemAttributes.SUPPORT_REQUIREMENT);
                supports.add(s);
            }
        }
        root.add("skills", skills);
        root.add("supports", supports);
        root.add("tagNames", tagNames);
        final JsonArray currencies = new JsonArray();
        if (plugin.currencies() != null) {
            for (final CurrencyType type : plugin.currencies().repository().all()) {
                if (type.name() == null || type.name().isBlank()) {
                    continue;
                }
                final JsonObject c = new JsonObject();
                c.addProperty("id", type.id());
                c.addProperty("name", type.name());
                c.addProperty("text", plain(type.description()));
                c.addProperty("flavor", plain(type.flavor()));
                c.addProperty("level", type.dropLevel());
                currencies.add(c);
            }
        }
        root.add("currencies", currencies);
        if (plugin.items() != null) {
            affixes(root, plugin.items().repository());
            uniqueInfo(plugin, root, plugin.items().repository());
        }
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 2026-10：詞綴（跟遊戲裡圖鑑的「詞綴一覽」同一套規則：一般掉落、通貨擲得到的，加上揭露才有的），每一種寫各階的範圍與擲得到的部位；
     * 以及規劃器沒有的傳奇（珠寶、藥劑、護符：規劃器只放穿得上身的）。
     */
    static void affixes(final JsonObject root, final tw.exilecore.item.ItemRepository repository) {
        final List<tw.exilecore.item.ItemBase> bases = tw.exilecore.gui.CodexMenu.codexBases(repository);
        final JsonArray list = new JsonArray();
        for (final tw.exilecore.item.Affix affix : repository.affixes()) {
            final boolean normal = tw.exilecore.gui.CodexMenu.normalAffix(affix);
            final boolean unveiled = !normal && tw.exilecore.gui.CodexMenu.unveiledAffix(affix);
            final boolean corrupted = !normal && !unveiled && tw.exilecore.gui.CodexMenu.corruptionAffix(affix); // 2026-10-21
            if (!normal && !unveiled && !corrupted) {
                continue;
            }
            final java.util.Set<String> slots = new java.util.LinkedHashSet<>();
            final JsonArray baseNames = new JsonArray();
            for (final tw.exilecore.item.ItemBase base : bases) {
                if (corrupted ? tw.exilecore.item.ItemGenerator.corruptionFits(affix, base) : affix.canSpawnOn(base)) {
                    slots.add(base.slot().name().toLowerCase(Locale.ROOT));
                    baseNames.add(base.name());
                }
            }
            if (slots.isEmpty()) {
                continue;
            }
            final JsonObject a = new JsonObject();
            a.addProperty("id", affix.id());
            final List<tw.exilecore.item.AffixTier> tiers = affix.tiers();
            a.addProperty("name", plain(affix.rangeText(tiers.getFirst().tier())));
            a.addProperty("type", corrupted ? "corrupted" : unveiled ? "unveiled" : affix.type() == tw.exilecore.item.AffixType.PREFIX ? "prefix" : "suffix");
            a.addProperty("affixType", affix.type() == tw.exilecore.item.AffixType.PREFIX ? "prefix" : "suffix");
            final JsonArray slotArray = new JsonArray();
            slots.forEach(slotArray::add);
            a.add("slots", slotArray);
            a.add("bases", baseNames);
            final JsonArray tierArray = new JsonArray();
            for (int i = 0; i < tiers.size(); i++) {
                final tw.exilecore.item.AffixTier tier = tiers.get(i);
                final JsonObject t = new JsonObject();
                t.addProperty("t", i + 1);
                t.addProperty("ilvl", tier.ilvl());
                t.addProperty("name", plain(affix.nameFor(tier.tier())));
                t.addProperty("text", plain(affix.rangeText(tier.tier())));
                tierArray.add(t);
            }
            a.add("tiers", tierArray);
            list.add(a);
        }
        root.add("affixes", list);
        final JsonArray extra = new JsonArray();
        for (final tw.exilecore.item.UniqueItem unique : repository.uniques()) {
            final tw.exilecore.item.ItemBase base = repository.base(unique.base());
            if (base == null || base.isMap() || !(base.slot() == tw.exilecore.item.ItemSlot.JEWEL || base.isFlaskLike())) {
                continue;
            }
            final JsonObject u = new JsonObject();
            u.addProperty("id", unique.id());
            u.addProperty("name", unique.name());
            u.addProperty("baseName", base.name());
            u.addProperty("slot", base.slot().name().toLowerCase(Locale.ROOT));
            u.addProperty("level", unique.dropLevel());
            u.addProperty("icon", tw.exilecore.item.UniqueArt.has(unique.id()));
            final JsonArray lines = new JsonArray();
            for (final String id : unique.affixIds()) {
                final tw.exilecore.item.Affix affix = repository.affix(id);
                if (affix != null && !affix.tiers().isEmpty()) {
                    lines.add(plain(affix.rangeText(affix.tiers().getFirst().tier())));
                }
            }
            u.add("lines", lines);
            final JsonArray flavour = new JsonArray();
            unique.flavour().forEach(flavour::add);
            u.add("flavour", flavour);
            extra.add(u);
        }
        root.add("uniquesExtra", extra);
    }

    /**
     * 2026-10-22：每件傳奇的圖鑑資訊（跟遊戲裡傳奇圖鑑一樣）：追逐級、哪裡會掉、隱匿詞綴（幾條、能揭露成哪些）。
     * 傳奇的樣子用規劃器的 items.json，這裡照代號補上。
     */
    static void uniqueInfo(final ExileCore plugin, final JsonObject root, final tw.exilecore.item.ItemRepository repository) {
        final JsonObject info = new JsonObject();
        for (final tw.exilecore.item.UniqueItem unique : repository.uniques()) {
            final JsonObject u = new JsonObject();
            if (unique.chase()) {
                u.addProperty("chase", true);
            }
            final JsonArray sources = new JsonArray();
            final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (final String source : unique.sources()) {
                final String text = tw.exilecore.gui.CodexMenu.sourceLabel(plugin, source);
                if (text != null && seen.add(text)) {
                    sources.add(plain(text));
                }
            }
            u.add("sources", sources);
            final tw.exilecore.item.ItemBase base = repository.base(unique.base());
            final JsonArray veiled = new JsonArray();
            final List<tw.exilecore.item.AffixType> types = repository.uniqueVeiled(unique.id());
            for (final tw.exilecore.item.AffixType type : new tw.exilecore.item.AffixType[] {tw.exilecore.item.AffixType.PREFIX, tw.exilecore.item.AffixType.SUFFIX}) {
                final long count = types.stream().filter(type::equals).count();
                if (count == 0) {
                    continue;
                }
                final JsonObject v = new JsonObject();
                v.addProperty("kind", type == tw.exilecore.item.AffixType.PREFIX ? "前綴" : "後綴");
                v.addProperty("count", count);
                final JsonArray options = new JsonArray();
                if (base != null) {
                    for (final tw.exilecore.item.Affix option : tw.exilecore.gui.CodexMenu.unveiledOptions(plugin, base, type)) {
                        options.add(plain(option.rangeText(option.tiers().getFirst().tier())));
                    }
                }
                v.add("options", options);
                veiled.add(v);
            }
            if (!veiled.isEmpty()) {
                u.add("veiled", veiled);
            }
            info.add(unique.id(), u);
        }
        root.add("uniqueInfo", info);
    }

    /** 拿掉 MiniMessage 標籤。 */
    static String plain(final String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "").replace("\\<", "<").strip();
    }
}
