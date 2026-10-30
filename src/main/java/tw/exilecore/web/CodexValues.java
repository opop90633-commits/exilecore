package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tw.exilecore.ascendancy.AscendancyConfig;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.event.EventConfig;
import tw.exilecore.flask.CharmService;
import tw.exilecore.flask.FlaskBase;
import tw.exilecore.flask.FlaskConfig;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.Runes;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.item.Veiled;
import tw.exilecore.map.AtlasTree;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapEventConfig;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.skill.BossSkill;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.skill.Mechanic;
import tw.exilecore.skill.ModifierText;
import tw.exilecore.skill.ScaledMod;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SkillProfiles;
import tw.exilecore.skill.SkillService;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.worldevent.WorldEventConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 2026-10-26：網頁圖鑑的完整數值（/codex/values 與 /codex/values.json）——管理員要「補全目前有的，並且顯示數值」。
 * <p>
 * 每一類都直接從遊戲的設定物件讀（技能、輔助、傳奇、基底、詞綴、通貨、藥劑、護符、符文、昇華、天賦、怪物、地圖與 Boss、事件、星圖），
 * 文字用遊戲內同一套產生器（{@link ModifierText}、{@link Affix#rangeText}、{@link SkillService#codexLines}、{@link tw.exilecore.skill.GemService#effectLines}），
 * 前端不寫死任何數值：之後天賦樹、昇華換成原作資料、技能輔助數值改了，重新整理（/ec reload）就跟著變。
 * 純資料（沒有 Bukkit 物件）：測試可以直接把 data/ 讀進來產生。
 */
public final class CodexValues {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 技能逐級表列到幾級：寶石 1～20，再加「+x 技能等級」到 40（跟遊戲內圖鑑的 Lv.30、Lv.40 同一個算法）。 */
    static final int MAX_LEVEL = 40;

    private CodexValues() {
    }

    /**
     * 產生圖鑑要用的設定物件。沒有的（模組沒開）給 null，那一類就是空的。
     *
     * @param sourceLabel 掉落來源代號 → 玩家看的文字（遊戲裡用 {@link tw.exilecore.gui.CodexMenu#sourceLabel}）
     */
    public record Sources(SkillConfig skills, ItemRepository items, CurrencyRepository currencies, FlaskConfig flasks,
                          Collection<CharmService.CharmBase> charms, AscendancyConfig ascendancy, PassiveTree passives,
                          MonsterConfig monsters, MapConfig maps, MapEventConfig mapEvents, EventConfig events,
                          WorldEventConfig worldEvents, AtlasTree atlas, Function<String, String> sourceLabel) {
    }

    /** 伺服器上的設定物件（模組沒開的給 null）。 */
    public static Sources sources(final tw.exilecore.ExileCore plugin) {
        return new Sources(
                plugin.skills() == null ? null : plugin.skills().config(),
                plugin.items() == null ? null : plugin.items().repository(),
                plugin.currencies() == null ? null : plugin.currencies().repository(),
                plugin.flasks() == null ? null : plugin.flasks().config(),
                plugin.flasks() == null || plugin.flasks().charms() == null ? null : plugin.flasks().charms().bases(),
                plugin.ascendancy() == null ? null : plugin.ascendancy().config(),
                plugin.passives() == null ? null : plugin.passives().tree(),
                plugin.monsters() == null ? null : plugin.monsters().config(),
                plugin.maps() == null ? null : plugin.maps().config(),
                plugin.maps() == null ? null : plugin.maps().eventConfig(),
                plugin.events() == null ? null : plugin.events().config(),
                plugin.worldEvents() == null ? null : plugin.worldEvents().config(),
                plugin.maps() == null ? null : plugin.maps().tree(),
                source -> tw.exilecore.gui.CodexMenu.sourceLabel(plugin, source));
    }

    /** 圖鑑頁（web/codex.html）的右下角加一個「完整數值」的入口（不改圖鑑頁本身）；沒有 body 結尾就接在最後。 */
    public static byte[] injectLink(final byte[] page) {
        final String link = "<a href=\"codex/values\" id=\"codex-values-link\" style=\"position:fixed;right:16px;bottom:16px;z-index:50;"
                + "padding:10px 14px;border-radius:8px;background:#2a2116;border:1px solid #af6025;color:#f5d7a1;font:600 14px sans-serif;"
                + "text-decoration:none;box-shadow:0 2px 10px #0008\">完整數值圖鑑 →</a>";
        final String html = new String(page, StandardCharsets.UTF_8);
        final int end = html.lastIndexOf("</body>");
        final String out = end < 0 ? html + link : html.substring(0, end) + link + html.substring(end);
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** 壓成 gzip（/codex/values.json 直接送壓好的）。 */
    public static byte[] gzip(final byte[] raw) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, raw.length / 6));
        try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(out)) {
            zip.write(raw);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static byte[] json(final Sources sources) {
        return GSON.toJson(build(sources)).getBytes(StandardCharsets.UTF_8);
    }

    /** 全部的類別；哪一類做不出來寫在 errors（測試要求是空的）。 */
    public static JsonObject build(final Sources sources) {
        final JsonObject root = new JsonObject();
        final JsonArray errors = new JsonArray();
        final JsonObject tagNames = new JsonObject();
        root.add("tagNames", tagNames);
        section(root, errors, "skills", () -> skills(sources, tagNames));
        section(root, errors, "supports", () -> supports(sources, tagNames));
        section(root, errors, "uniques", () -> uniques(sources));
        section(root, errors, "bases", () -> bases(sources, tagNames));
        section(root, errors, "affixes", () -> affixes(sources));
        section(root, errors, "currencies", () -> currencies(sources));
        section(root, errors, "runes", () -> runes(sources));
        section(root, errors, "ascendancies", () -> ascendancies(sources));
        section(root, errors, "passives", () -> passives(sources));
        section(root, errors, "atlas", () -> atlas(sources));
        section(root, errors, "monsters", () -> monsters(sources));
        section(root, errors, "maps", () -> maps(sources));
        section(root, errors, "events", () -> events(sources));
        root.add("errors", errors);
        return root;
    }

    private interface Part {
        com.google.gson.JsonElement make();
    }

    private static void section(final JsonObject root, final JsonArray errors, final String key, final Part part) {
        try {
            root.add(key, part.make());
        } catch (final RuntimeException e) {
            errors.add(key + "：" + e);
            root.add(key, new JsonArray());
        }
    }

    // ---- 技能 ----

    /** 網頁圖鑑列的技能：玩家看得到的（普攻、輔助觸發的 proc_、沒名字的不列）。 */
    static boolean listedSkill(final SkillDefinition skill) {
        return !skill.hidden() && !skill.id().startsWith("proc_") && skill.name() != null && !skill.name().isBlank();
    }

    static JsonArray skills(final Sources sources, final JsonObject tagNames) {
        final JsonArray list = new JsonArray();
        final SkillConfig config = sources.skills();
        if (config == null) {
            return list;
        }
        final Map<String, List<String>> grantedBy = new LinkedHashMap<>();
        if (sources.items() != null) {
            for (final Map.Entry<String, String> entry : sources.items().grantedSkills().entrySet()) {
                final ItemBase base = sources.items().base(entry.getKey());
                if (base != null && !sources.items().retiredBase(base.id())) {
                    grantedBy.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(base.name());
                }
            }
        }
        final List<SkillDefinition> all = new ArrayList<>(config.all());
        all.sort(Comparator.comparingInt(SkillDefinition::tier).thenComparing(SkillDefinition::name));
        final List<SupportDefinition> gems = supportList(config);
        for (final SkillDefinition skill : all) {
            if (!listedSkill(skill)) {
                continue;
            }
            final JsonObject s = new JsonObject();
            s.addProperty("id", skill.id());
            s.addProperty("name", skill.name());
            s.addProperty("granted", skill.droppedBy("weapon") || grantedBy.containsKey(skill.id()));
            s.addProperty("type", skill.isAttack() ? "attack" : "spell");
            s.addProperty("typeName", skill.typeName());
            s.addProperty("headline", CodexData.plain(SkillService.headline(skill)));
            s.addProperty("tier", skill.tier());
            s.addProperty("mechanic", skill.mechanic() == null ? "" : skill.mechanic().name().toLowerCase(Locale.ROOT));
            s.addProperty("text", CodexData.plain(tw.exilecore.charge.ChargeText.expand(skill.description())));
            if (skill.source() != null) {
                s.addProperty("source", CodexData.plain(label(sources, skill.source())));
            }
            if (grantedBy.containsKey(skill.id())) {
                s.add("grantedBy", strings(grantedBy.get(skill.id())));
            }
            final JsonArray tags = new JsonArray();
            if (skill.displayTags() != null) {
                skill.displayTags().stream().sorted().forEach(tag -> { // 2026-10-30：照原作寶石標籤顯示
                    tags.add(tag);
                    tagNames.addProperty(tag, ModifierText.tagName(tag));
                });
            }
            s.add("tags", tags);
            final JsonArray weapons = new JsonArray();
            if (skill.weapons() != null) {
                skill.weapons().stream().sorted().forEach(weapon -> {
                    weapons.add(weapon);
                    tagNames.addProperty(weapon, ModifierText.tagName(weapon));
                });
            }
            s.add("weapons", weapons);
            if (skill.isAttack()) {
                s.addProperty("attackSpeed", Math.round(skill.attackSpeed() * 100));
            } else if (skill.mechanic() != Mechanic.AURA) {
                s.addProperty("castTime", skill.castTime());
            }
            if (skill.cooldown() > 0) {
                s.addProperty("cooldown", skill.cooldown());
            }
            if (!skill.damageTypes().isEmpty() && !skill.isAttack() && skill.damage() != null) {
                final JsonObject split = new JsonObject();
                skill.damageTypes().forEach((type, share) -> split.addProperty(type.displayName(), Math.round(share * 100)));
                s.add("damageTypes", split);
            }
            if (!skill.conversion().isEmpty()) {
                final JsonArray conversion = new JsonArray();
                skill.conversion().forEach((type, fraction) -> conversion.add(String.format(Locale.ROOT, "%d%% 物理傷害轉換為%s傷害",
                        Math.round(fraction * 100), type.displayName())));
                s.add("conversion", conversion);
            }
            s.add("mods", described(skill.modifiers()));
            s.add("info", skillInfo(skill));
            final String quality = SkillService.codexQualityText(skill); // 2026-10-30 照站長：品質效果、進階奇術的額外品質效果
            if (quality != null && !quality.isBlank()) {
                s.addProperty("quality", CodexData.plain(quality));
            }
            final String advanced = SkillService.codexAdvancedQualityText(skill);
            if (advanced != null && !advanced.isBlank()) {
                s.addProperty("advancedQuality", CodexData.plain(advanced));
            }
            final JsonArray fits = new JsonArray();
            for (final SupportDefinition support : gems) {
                if (support.supports(skill)) {
                    fits.add(support.id());
                }
            }
            s.add("supports", fits);
            levels(config, skill, s);
            list.add(s);
        }
        return list;
    }

    /** 不隨等級變的說明：召喚物、光環的範圍、詛咒的半徑…（數值照設定）。 */
    private static JsonArray skillInfo(final SkillDefinition skill) {
        final JsonArray info = new JsonArray();
        if (skill.summon() != null) {
            final var spec = skill.summon();
            info.add(String.format(Locale.ROOT, "一次召出 %d 隻；生命是同等級普通怪的 %s 倍、傷害 %s 倍、每秒攻擊 %s 次%s",
                    spec.count(), num(spec.life()), num(spec.damage()), num(spec.attackSpeed()),
                    spec.duration() > 0 ? "、持續 " + num(spec.duration()) + " 秒" : "（永久）"));
            if (spec.ranged() > 0) {
                info.add("遠程：站在 " + num(spec.ranged()) + " 格內射擊");
            }
            if (spec.corpse()) {
                info.add("要用一具屍體當材料（主人 " + num(spec.search()) + " 格內）");
            }
            for (final String line : ModifierText.describeAll(spec.buff())) {
                info.add("有召喚物活著時你：" + CodexData.plain(line));
            }
        }
        if (skill.aura() != null && !skill.reservesSpiritOnly()) { // 2026-10-28：只保留精魂的主動技能（戰旗、掠閃、喚駒）沒有光環範圍
            final var aura = skill.aura();
            if (aura.radius() > 0) {
                info.add("光環半徑 " + num(aura.radius()) + " 格" + (aura.allies() ? "，隊友也有效" : ""));
            }
        }
        if (skill.cooldownBypass() != null) {
            info.add("冷卻中可以消耗一顆" + tw.exilecore.charge.ChargeText.name(skill.cooldownBypass()) + "直接施放");
        }
        return info;
    }

    /**
     * 逐級表（1～40）：寶石 1～20 照寶石等級，21～40 當成 20 級寶石再加「+x 技能等級」（跟遊戲的 {@code describeExtra} 一樣）。
     * 每一級的說明行（範圍、投射物、光環效果…）去重複後放 {@code lines}，每一級只放索引。
     */
    private static void levels(final SkillConfig config, final SkillDefinition skill, final JsonObject s) {
        final List<String> table = new ArrayList<>();
        final Map<String, Integer> index = new LinkedHashMap<>();
        final JsonArray rows = new JsonArray();
        for (int level = 1; level <= MAX_LEVEL; level++) {
            final int gem = Math.min(level, SkillLoadout.MAX_LEVEL);
            final int extra = level - gem;
            final StatSheet sheet = extra > 0
                    ? new StatSheet().overlay(List.of(Modifier.flat(StatKeys.SKILL_LEVEL, extra, "圖鑑")))
                    : new StatSheet();
            final SkillBuild build;
            try {
                build = SkillBuild.of(config, skill, gem, 100, false, List.of(), Set.of(), sheet);
            } catch (final RuntimeException e) {
                continue;
            }
            final int effective = build.level();
            final JsonObject row = new JsonObject();
            row.addProperty("lv", level);
            if (level <= SkillLoadout.MAX_LEVEL) {
                row.addProperty("req", config.requiredLevel(level));
            }
            if (build.manaCost() > 0) {
                row.addProperty("mana", build.manaCost());
            }
            final boolean deals = skill.dealsDamage();
            if (deals && !skill.isAttack() && skill.damage() != null) {
                final double[] range = SkillProfiles.spellDamage(config, skill, effective);
                row.addProperty("min", Math.round(range[0]));
                row.addProperty("max", Math.round(range[1]));
            }
            if (deals && (skill.isAttack() || skill.damage() != null)) {
                row.addProperty("eff", Math.round(build.profile().effectiveness() * 100));
            }
            if (!skill.isAttack() && skill.mechanic() != Mechanic.AURA) {
                row.addProperty("time", round2(build.intervalTicks(sheet) / 20.0));
            }
            if (build.cooldownTicks() > 0 && skill.mechanic() != Mechanic.AURA) {
                row.addProperty("cd", round2(build.cooldownSeconds()));
            }
            if (build.reserve() > 0) {
                row.addProperty("spirit", Math.round(build.reserve()));
            }
            if (build.minionReserve() > 0) {
                row.addProperty("spiritEach", round2(build.minionReserve()));
            }
            if (build.minions() > 0) {
                row.addProperty("minions", build.minions());
            }
            final List<String> lines = new ArrayList<>();
            try {
                for (final String line : SkillService.codexLines(build, sheet)) {
                    lines.add(CodexData.plain(line));
                }
            } catch (final RuntimeException ignored) {
                // 寫不出來的說明略過（數值欄照樣有）
            }
            lines.addAll(effectLines(skill, effective, build.auraEffect()));
            final JsonArray ids = new JsonArray();
            for (final String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                final Integer known = index.get(line);
                final int id = known != null ? known : table.size();
                if (known == null) {
                    index.put(line, id);
                    table.add(line);
                }
                ids.add(id);
            }
            if (!ids.isEmpty()) {
                row.add("l", ids);
            }
            rows.add(row);
        }
        s.add("lines", strings(table));
        s.add("levels", rows);
    }

    /** 光環、狂怒、詛咒／印記、戰吼給召喚物的效果，照這個等級（{@link ScaledMod#describe}，跟遊戲同一套）。 */
    private static List<String> effectLines(final SkillDefinition skill, final int level, final double effect) {
        final List<String> lines = new ArrayList<>();
        if (skill.aura() != null) {
            for (final ScaledMod mod : skill.aura().effects()) {
                lines.add("光環效果：" + safeDescribe(mod, level, effect));
            }
            if (skill.aura().fury() != null) {
                for (final ScaledMod mod : skill.aura().fury().effects()) {
                    lines.add("狂暴時：" + safeDescribe(mod, level, 1.0));
                }
            }
            if (skill.aura().perMinion() != null) {
                lines.add("每一隻保留精魂 " + num(skill.aura().perMinion().at(Math.min(level, 40)))
                        + (skill.aura().freeMinions() > 0 ? "（前 " + skill.aura().freeMinions() + " 隻不用）" : ""));
            }
        }
        return lines;
    }

    private static String safeDescribe(final ScaledMod mod, final int level, final double scale) {
        try {
            return CodexData.plain(mod.describe(level, scale));
        } catch (final RuntimeException e) {
            return mod.stat();
        }
    }

    // ---- 輔助 ----

    private static List<SupportDefinition> supportList(final SkillConfig config) {
        final List<SupportDefinition> gems = new ArrayList<>(config.supports());
        gems.removeIf(support -> support.name() == null || support.name().isBlank());
        gems.sort(Comparator.comparingInt(SupportDefinition::tier).thenComparing(SupportDefinition::name));
        return gems;
    }

    static JsonArray supports(final Sources sources, final JsonObject tagNames) {
        final JsonArray list = new JsonArray();
        final SkillConfig config = sources.skills();
        if (config == null) {
            return list;
        }
        for (final SupportDefinition support : supportList(config)) {
            final JsonObject s = new JsonObject();
            s.addProperty("id", support.id());
            s.addProperty("name", support.name());
            s.addProperty("tier", support.tier());
            s.addProperty("text", CodexData.plain(support.descriptionText()));
            s.addProperty("requires", CodexData.plain(support.supportsText(config.all())));
            s.addProperty("mana", support.manaMultiplier());
            if (support.spiritSupport()) {
                s.addProperty("spirit", true);
            }
            if (!support.cuttable()) {
                s.addProperty("source", CodexData.plain(label(sources, support.source())));
            }
            final JsonArray requireTags = new JsonArray();
            for (final String tag : support.requires()) {
                requireTags.add(tag);
                tagNames.addProperty(tag, ModifierText.tagName(tag));
            }
            s.add("requireTags", requireTags);
            final JsonArray excludeTags = new JsonArray();
            for (final String tag : support.excludes()) {
                excludeTags.add(tag);
                tagNames.addProperty(tag, ModifierText.tagName(tag));
            }
            s.add("excludeTags", excludeTags);
            final JsonArray lines = new JsonArray();
            try {
                for (final String line : tw.exilecore.skill.GemService.effectLines(support)) {
                    lines.add(CodexData.plain(line));
                }
            } catch (final RuntimeException e) {
                for (final String line : ModifierText.describeAll(support.allModifiers())) {
                    lines.add(CodexData.plain(line));
                }
            }
            s.add("lines", lines);
            int count = 0;
            for (final SkillDefinition skill : config.all()) {
                if (listedSkill(skill) && support.supports(skill)) {
                    count++;
                }
            }
            s.addProperty("skillCount", count);
            list.add(s);
        }
        return list;
    }

    // ---- 傳奇 ----

    static JsonArray uniques(final Sources sources) {
        final JsonArray list = new JsonArray();
        final ItemRepository repository = sources.items();
        if (repository == null) {
            return list;
        }
        for (final UniqueItem unique : repository.uniques()) {
            final ItemBase base = repository.base(unique.base());
            if (base == null) {
                continue;
            }
            final JsonObject u = new JsonObject();
            u.addProperty("id", unique.id());
            u.addProperty("name", unique.name());
            u.addProperty("baseName", base.name());
            u.addProperty("group", group(base));
            u.addProperty("slot", slotName(base.slot()));
            u.addProperty("level", unique.dropLevel());
            if (unique.chase()) {
                u.addProperty("chase", true);
            }
            final ItemData data = new ItemData(base.id(), Rarity.UNIQUE, Math.max(1, unique.dropLevel()));
            data.setUnique(unique.id());
            stats(repository, base, data, u);
            final int sockets = Runes.slots(repository, base, data);
            if (sockets > 0) {
                u.addProperty("runeSockets", sockets);
            }
            final JsonArray mods = new JsonArray();
            for (final String id : unique.affixIds()) {
                final Affix affix = repository.affix(id);
                if (affix != null && !affix.tiers().isEmpty()) {
                    mods.add(CodexData.plain(affix.rangeText(affix.tiers().getFirst().tier())));
                }
            }
            u.add("mods", mods);
            final List<AffixType> veiled = repository.uniqueVeiled(unique.id());
            if (!veiled.isEmpty()) {
                final long prefixes = veiled.stream().filter(AffixType.PREFIX::equals).count();
                u.addProperty("veiled", (prefixes > 0 ? "隱匿前綴 " + prefixes + " 條" : "")
                        + (prefixes > 0 && veiled.size() > prefixes ? "、" : "")
                        + (veiled.size() > prefixes ? "隱匿後綴 " + (veiled.size() - prefixes) + " 條" : ""));
            }
            final Set<String> seen = new LinkedHashSet<>();
            for (final String source : unique.sources()) {
                final String text = label(sources, source);
                if (text != null && !text.isBlank()) {
                    seen.add(CodexData.plain(text));
                }
            }
            u.add("sources", strings(new ArrayList<>(seen)));
            u.add("flavour", strings(unique.flavour()));
            list.add(u);
        }
        return list;
    }

    // ---- 基底 ----

    /** 基底的分類（網頁的子分頁）。 */
    static String group(final ItemBase base) {
        if (base.isFlask()) {
            return "flask";
        }
        if (base.isCharm()) {
            return "charm";
        }
        if (base.isMap()) {
            return "map";
        }
        if (base.isJewel()) {
            return "jewel";
        }
        if (base.isWeapon()) {
            return base.hasTag("caster") ? "caster" : "weapon";
        }
        if (base.isShield()) {
            return "shield";
        }
        if (base.isQuiver()) {
            return "quiver";
        }
        if (base.isJewellery()) {
            return "jewellery";
        }
        return base.slot() == ItemSlot.OFF_HAND ? "offhand" : "armour";
    }

    static String slotName(final ItemSlot slot) {
        return switch (slot) {
            case MAIN_HAND -> "武器";
            case OFF_HAND -> "副手";
            case HEAD -> "頭盔";
            case CHEST -> "胸甲";
            case LEGS -> "護腿";
            case FEET -> "靴子";
            default -> slot.displayName();
        };
    }

    /** 基底數值（傷害、攻速、暴擊、護甲、閃避、能量護盾、格擋、需求、隱含）：用遊戲算物品數值的 {@link ItemStats#snapshot}。 */
    private static void stats(final ItemRepository repository, final ItemBase base, final ItemData data, final JsonObject out) {
        if (base.isFlaskLike() || base.isMap()) {
            return;
        }
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, data);
        final JsonObject damage = new JsonObject();
        double average = 0;
        for (final Map.Entry<DamageType, double[]> entry : snapshot.damage().entrySet()) {
            final JsonArray range = new JsonArray();
            range.add(Math.round(entry.getValue()[0]));
            range.add(Math.round(entry.getValue()[1]));
            damage.add(entry.getKey().displayName(), range);
            average += (entry.getValue()[0] + entry.getValue()[1]) / 2;
        }
        if (!damage.isEmpty()) {
            out.add("damage", damage);
        }
        if (base.isWeapon() && snapshot.aps() > 0) {
            out.addProperty("aps", snapshot.aps());
            out.addProperty("crit", snapshot.crit());
            if (average > 0) {
                out.addProperty("dps", Math.round(average * snapshot.aps() * 10) / 10.0);
            }
        }
        if (snapshot.armour() > 0) {
            out.addProperty("armour", Math.round(snapshot.armour()));
        }
        if (snapshot.evasion() > 0) {
            out.addProperty("evasion", Math.round(snapshot.evasion()));
        }
        if (snapshot.energyShield() > 0) {
            out.addProperty("es", Math.round(snapshot.energyShield()));
        }
        if (snapshot.block() > 0) {
            out.addProperty("block", snapshot.block());
        }
        final ItemBase.Requirements req = snapshot.requirements();
        if (req != null) {
            final JsonObject r = new JsonObject();
            r.addProperty("level", req.level());
            if (req.str() > 0) {
                r.addProperty("str", req.str());
            }
            if (req.dex() > 0) {
                r.addProperty("dex", req.dex());
            }
            if (req.intelligence() > 0) {
                r.addProperty("int", req.intelligence());
            }
            out.add("req", r);
        }
        final tw.exilecore.item.Implicit implicit = ItemStats.implicit(repository, base, data);
        if (implicit != null && implicit.text() != null) {
            out.addProperty("implicit", CodexData.plain(implicit.rangeText()));
        }
    }

    static JsonArray bases(final Sources sources, final JsonObject tagNames) {
        final JsonArray list = new JsonArray();
        final ItemRepository repository = sources.items();
        if (repository == null) {
            return list;
        }
        final Map<String, CharmService.CharmBase> charms = new LinkedHashMap<>();
        if (sources.charms() != null) {
            for (final CharmService.CharmBase charm : sources.charms()) {
                charms.put(charm.id(), charm);
            }
        }
        final List<ItemBase> all = new ArrayList<>(repository.bases());
        all.addAll(repository.flaskLikeBases());
        // 2026-10-27：跟遊戲裡的圖鑑同一套順序（部位 → 武器類型、防具屬性、珠寶顏色 → 等級 → 名稱）；這次之前只照部位、等級，各種武器混在一起
        all.sort(tw.exilecore.gui.CodexOrder.BASES);
        final SkillConfig skills = sources.skills();
        for (final ItemBase base : all) {
            final JsonObject b = new JsonObject();
            b.addProperty("id", base.id());
            b.addProperty("name", base.name());
            b.addProperty("group", group(base));
            b.addProperty("slot", slotName(base.slot()));
            b.addProperty("level", base.dropLevel());
            final JsonArray tags = new JsonArray();
            base.tags().stream().sorted().forEach(tag -> {
                tags.add(tag);
                final String name = ModifierText.tagName(tag);
                if (name != null && !name.equals(tag)) {
                    tagNames.addProperty(tag, name); // 只有遊戲裡有中文名的標籤網頁才顯示
                }
            });
            b.add("tags", tags);
            if (base.isMap()) {
                b.addProperty("mapTier", base.mapTier());
            }
            if (base.isFlaskLike()) {
                flaskInfo(sources, base, charms.get(base.id()), b);
            } else {
                final int ilvl = Math.max(Math.max(1, base.dropLevel()), base.statLevel());
                b.addProperty("ilvl", ilvl);
                stats(repository, base, new ItemData(base.id(), Rarity.NORMAL, ilvl), b);
                final int sockets = Runes.slots(base);
                if (sockets > 0) {
                    b.addProperty("runeSockets", sockets);
                }
            }
            final String granted = repository.grantedSkill(base.id());
            if (granted != null) {
                final SkillDefinition skill = skills == null ? null : skills.skill(granted);
                b.addProperty("grants", skill == null ? granted : skill.name());
                b.addProperty("grantsId", granted);
            }
            if (repository.reloadTime(base.id()) > 0) {
                b.addProperty("reload", repository.reloadTime(base.id()));
            }
            list.add(b);
        }
        return list;
    }

    private static void flaskInfo(final Sources sources, final ItemBase base, final CharmService.CharmBase charm, final JsonObject b) {
        final FlaskBase flask = sources.flasks() == null ? null : sources.flasks().base(base.id());
        final JsonArray lines = new JsonArray();
        if (flask != null) {
            b.addProperty("kind", flask.kind().name().toLowerCase(Locale.ROOT));
            b.addProperty("recovery", flask.recovery());
            b.addProperty("duration", flask.duration());
            b.addProperty("charges", flask.charges());
            b.addProperty("perUse", flask.perUse());
            b.addProperty("reqLevel", flask.level());
        }
        if (charm != null) {
            b.addProperty("trigger", charm.trigger());
            b.addProperty("duration", charm.duration());
            b.addProperty("charges", charm.charges());
            b.addProperty("perUse", charm.perUse());
            b.addProperty("reqLevel", charm.level());
            if (charm.cure()) {
                lines.add("觸發時解除那個異常狀態");
            }
            for (final String line : ModifierText.describeAll(charm.effects())) {
                lines.add(CodexData.plain(line));
            }
            if (charm.description() != null && !charm.description().isBlank()) {
                lines.add(CodexData.plain(charm.description()));
            }
        }
        b.add("lines", lines);
    }

    // ---- 詞綴 ----

    /** 詞綴的分類：前綴、後綴、珠寶、藥劑與護符、地圖（換界石）、腐化、揭露（隱匿）。傳奇專屬、停用、隱匿佔位的不列。 */
    static String affixCategory(final Affix affix, final List<ItemBase> spawnBases) {
        if (tw.exilecore.gui.CodexMenu.corruptionAffix(affix)) {
            return "corrupted";
        }
        if (tw.exilecore.gui.CodexMenu.unveiledAffix(affix)) {
            return "unveiled";
        }
        if (!tw.exilecore.gui.CodexMenu.normalAffix(affix) || spawnBases.isEmpty()) {
            return null;
        }
        if (spawnBases.stream().allMatch(ItemBase::isMap)) {
            return "map";
        }
        if (spawnBases.stream().allMatch(ItemBase::isJewel)) {
            return "jewel";
        }
        if (spawnBases.stream().allMatch(ItemBase::isCharm)) {
            return "charm"; // 2026-10-27：護符跟藥劑分開一類；這次之前合在「藥劑與護符」
        }
        if (spawnBases.stream().allMatch(ItemBase::isFlaskLike)) {
            return "flask";
        }
        return affix.type() == AffixType.PREFIX ? "prefix" : "suffix";
    }

    /** 詞綴能出在哪些基底（腐化照蝕變石的規則）。 */
    static List<ItemBase> spawnBases(final ItemRepository repository, final Affix affix) {
        final List<ItemBase> bases = new ArrayList<>(repository.bases());
        bases.addAll(repository.flaskLikeBases());
        final boolean corrupted = tw.exilecore.gui.CodexMenu.corruptionAffix(affix);
        final List<ItemBase> out = new ArrayList<>();
        for (final ItemBase base : bases) {
            if (corrupted ? tw.exilecore.item.ItemGenerator.corruptionFits(affix, base) : affix.canSpawnOn(base)) {
                out.add(base);
            }
        }
        return out;
    }

    static JsonArray affixes(final Sources sources) {
        final JsonArray list = new JsonArray();
        final ItemRepository repository = sources.items();
        if (repository == null) {
            return list;
        }
        for (final Affix affix : repository.affixes()) {
            if (affix.tiers().isEmpty() || Veiled.isVeiled(affix.id())) {
                continue;
            }
            final List<ItemBase> spawn = spawnBases(repository, affix);
            final String category = affixCategory(affix, spawn);
            if (category == null) {
                continue;
            }
            final JsonObject a = new JsonObject();
            a.addProperty("id", affix.id());
            a.addProperty("cat", category);
            a.addProperty("type", affix.type() == AffixType.PREFIX ? "前綴" : "後綴");
            a.addProperty("group", affix.group());
            a.addProperty("name", CodexData.plain(affix.rangeText(affix.tiers().getFirst().tier())));
            final Set<String> slots = new LinkedHashSet<>();
            final Set<String> groups = new LinkedHashSet<>();
            for (final ItemBase base : spawn) {
                slots.add(slotName(base.slot()));
                groups.add(group(base));
            }
            a.add("slots", strings(new ArrayList<>(slots)));
            a.add("groups", strings(new ArrayList<>(groups)));
            a.addProperty("baseCount", spawn.size());
            final JsonArray tiers = new JsonArray();
            int totalWeight = 0;
            for (final AffixTier tier : affix.tiers()) {
                totalWeight += tier.weight();
            }
            for (final AffixTier tier : affix.tiers()) {
                final JsonObject t = new JsonObject();
                t.addProperty("t", affix.rank(tier.tier()));
                t.addProperty("ilvl", tier.ilvl());
                t.addProperty("weight", tier.weight());
                final String name = affix.nameFor(tier.tier());
                if (name != null && !name.isBlank()) {
                    t.addProperty("name", CodexData.plain(name));
                }
                t.addProperty("text", CodexData.plain(affix.rangeText(tier.tier())));
                tiers.add(t);
            }
            a.addProperty("weight", totalWeight);
            a.add("tiers", tiers);
            list.add(a);
        }
        return list;
    }

    // ---- 通貨、符文 ----

    static JsonArray currencies(final Sources sources) {
        final JsonArray list = new JsonArray();
        final CurrencyRepository repository = sources.currencies();
        if (repository == null) {
            return list;
        }
        for (final CurrencyType type : repository.all()) {
            if (type.name() == null || type.name().isBlank()) {
                continue;
            }
            final JsonObject c = new JsonObject();
            c.addProperty("id", type.id());
            c.addProperty("name", type.name());
            c.addProperty("op", type.operation() == null ? "" : type.operation().name().toLowerCase(Locale.ROOT));
            c.addProperty("text", CodexData.plain(type.description()));
            c.addProperty("flavor", CodexData.plain(type.flavor()));
            c.addProperty("level", type.dropLevel());
            c.addProperty("stack", type.stack());
            c.addProperty("weight", type.dropWeight());
            if (type.rarities() != null && !type.rarities().isEmpty()) {
                final List<String> names = new ArrayList<>();
                type.rarities().stream().sorted().forEach(rarity -> names.add(rarity.displayName()));
                c.add("rarities", strings(names));
            }
            if (type.success() > 0) {
                c.addProperty("success", type.success());
            }
            if (type.sockets() > 0) {
                c.addProperty("sockets", type.sockets());
            }
            if (type.affix() != null && sources.items() != null) {
                final Affix affix = sources.items().affix(type.affix());
                if (affix != null && !affix.tiers().isEmpty()) {
                    c.addProperty("affix", CodexData.plain(affix.rangeText(affix.tiers().getFirst().tier())));
                }
            }
            if (type.omen() != null && type.omen().effect() != null) {
                c.addProperty("omen", type.omen().effect().name().toLowerCase(Locale.ROOT));
            }
            list.add(c);
        }
        return list;
    }

    static JsonArray runes(final Sources sources) {
        final JsonArray list = new JsonArray();
        if (sources.items() == null) {
            return list;
        }
        for (final Runes.Rune rune : sources.items().runes()) {
            final JsonObject r = new JsonObject();
            r.addProperty("id", rune.id());
            r.addProperty("name", rune.name());
            final JsonArray weapon = new JsonArray();
            rune.weapon().forEach(line -> weapon.add(CodexData.plain(line.text())));
            final JsonArray armour = new JsonArray();
            rune.armour().forEach(line -> armour.add(CodexData.plain(line.text())));
            r.add("weapon", weapon);
            r.add("armour", armour);
            list.add(r);
        }
        return list;
    }

    // ---- 昇華、天賦、星圖 ----

    static JsonArray ascendancies(final Sources sources) {
        final JsonArray list = new JsonArray();
        final AscendancyConfig config = sources.ascendancy();
        if (config == null) {
            return list;
        }
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            final JsonObject a = new JsonObject();
            a.addProperty("id", ascendancy.id());
            a.addProperty("name", ascendancy.name());
            a.addProperty("classId", ascendancy.classId());
            String className = ascendancy.classId();
            try {
                className = tw.exilecore.character.CharacterClass.parse(ascendancy.classId(), null) == null ? className
                        : tw.exilecore.character.CharacterClass.parse(ascendancy.classId(), null).displayName();
            } catch (final RuntimeException ignored) {
                // 認不得的職業照代號
            }
            a.addProperty("className", className);
            a.addProperty("color", ascendancy.color());
            a.addProperty("summary", CodexData.plain(ascendancy.summary()));
            final JsonArray branches = new JsonArray();
            int nodes = 0;
            for (final List<AscendancyConfig.Node> branch : ascendancy.branches()) {
                final JsonArray b = new JsonArray();
                for (final AscendancyConfig.Node node : branch) {
                    final JsonObject n = new JsonObject();
                    n.addProperty("id", node.id());
                    n.addProperty("name", node.name());
                    n.addProperty("cost", node.cost());
                    n.add("lines", described(node.stats()));
                    if (node.note() != null && !node.note().isBlank()) {
                        n.addProperty("note", CodexData.plain(node.note()));
                    }
                    b.add(n);
                    nodes++;
                }
                branches.add(b);
            }
            a.add("branches", branches);
            a.addProperty("nodeCount", nodes);
            list.add(a);
        }
        return list;
    }

    static JsonObject passives(final Sources sources) {
        final JsonObject out = new JsonObject();
        final PassiveTree tree = sources.passives();
        final JsonArray notables = new JsonArray();
        final JsonArray keystones = new JsonArray();
        final Map<String, Integer> small = new LinkedHashMap<>();
        int sockets = 0;
        int total = 0;
        if (tree != null) {
            for (final PassiveNode node : tree.ordered()) {
                total++;
                switch (node.type()) {
                    case NOTABLE, KEYSTONE -> {
                        final JsonObject n = new JsonObject();
                        n.addProperty("id", node.id());
                        n.addProperty("name", node.name().isEmpty() ? node.type().displayName() : node.name());
                        n.add("lines", passiveLines(node));
                        (node.type() == PassiveNode.Type.KEYSTONE ? keystones : notables).add(n);
                    }
                    case SMALL -> small.merge(String.join("／", jsonStrings(passiveLines(node))), 1, Integer::sum);
                    case SOCKET -> sockets++;
                    default -> {
                    }
                }
            }
        }
        out.addProperty("total", total);
        out.addProperty("sockets", sockets);
        out.add("keystones", keystones);
        out.add("notables", notables);
        final JsonArray smallList = new JsonArray();
        small.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(entry -> {
            final JsonObject s = new JsonObject();
            s.addProperty("text", entry.getKey());
            s.addProperty("count", entry.getValue());
            smallList.add(s);
        });
        out.add("small", smallList);
        return out;
    }

    private static JsonArray passiveLines(final PassiveNode node) {
        final JsonArray lines = new JsonArray();
        if (!node.description().isEmpty()) {
            node.description().forEach(line -> lines.add(CodexData.plain(line)));
            return lines;
        }
        return described(node.modifiers());
    }

    static JsonObject atlas(final Sources sources) {
        final JsonObject out = new JsonObject();
        final AtlasTree tree = sources.atlas();
        final JsonArray nodes = new JsonArray();
        if (tree != null) {
            final Map<String, String> trunks = new LinkedHashMap<>();
            for (final AtlasTree.Trunk trunk : tree.trunks()) {
                trunks.put(trunk.id(), trunk.name());
            }
            for (final AtlasTree.Node node : tree.nodes()) {
                final JsonObject n = new JsonObject();
                n.addProperty("id", node.id());
                n.addProperty("name", node.name());
                n.addProperty("trunk", trunks.getOrDefault(node.trunk(), node.trunk() == null ? "" : node.trunk()));
                n.addProperty("cost", node.cost());
                n.addProperty("kind", node.keystone() ? "keystone" : node.notable() ? "notable" : "small");
                final JsonArray lines = new JsonArray();
                for (final AtlasTree.Mod mod : node.mods()) {
                    lines.add(CodexData.plain(AtlasTree.describe(mod.stat(), mod.value())));
                }
                for (final AtlasTree.Mod mod : node.drawbacks()) {
                    lines.add("代價：" + CodexData.plain(AtlasTree.describe(mod.stat(), mod.value())));
                }
                n.add("lines", lines);
                nodes.add(n);
            }
        }
        out.add("nodes", nodes);
        return out;
    }

    // ---- 怪物、地圖與 Boss、事件 ----

    static JsonObject monsters(final Sources sources) {
        final JsonObject out = new JsonObject();
        final MonsterConfig config = sources.monsters();
        final JsonArray rarities = new JsonArray();
        final JsonArray types = new JsonArray();
        final JsonArray mods = new JsonArray();
        final JsonArray levels = new JsonArray();
        if (config != null) {
            for (final MonsterRarity rarity : MonsterRarity.values()) {
                final MonsterConfig.RaritySettings settings = config.rarity(rarity);
                if (settings == null) {
                    continue;
                }
                final JsonObject r = new JsonObject();
                r.addProperty("name", rarity.displayName());
                r.addProperty("weight", settings.weight());
                r.addProperty("life", settings.lifeMultiplier());
                r.addProperty("damage", settings.damageMultiplier());
                r.addProperty("mods", settings.minMods() == settings.maxMods() ? String.valueOf(settings.minMods())
                        : settings.minMods() + "～" + settings.maxMods());
                r.addProperty("quantity", Math.round(settings.quantityBonus() * 100));
                r.addProperty("rarity", Math.round(settings.rarityBonus() * 100));
                r.addProperty("experience", settings.experienceMultiplier());
                r.addProperty("resist", config.rarityResist(rarity));
                r.addProperty("curse", config.rarityCurseEffect(rarity));
                rarities.add(r);
            }
            for (final Map.Entry<String, MonsterConfig.TypeSettings> entry : config.types().entrySet()) {
                final MonsterConfig.TypeSettings type = entry.getValue();
                final JsonObject t = new JsonObject();
                t.addProperty("id", entry.getKey());
                t.addProperty("name", type.name() == null ? entry.getKey() : type.name());
                t.addProperty("life", type.lifeMultiplier());
                t.addProperty("damage", type.damageMultiplier());
                t.add("skills", bossSkillNames(sources, type.skills()));
                if (!type.skills().isEmpty()) {
                    t.addProperty("skillChance", Math.round(type.skillChance() * 100));
                }
                types.add(t);
            }
            for (final MonsterConfig.MonsterMod mod : config.mods()) {
                final JsonObject m = new JsonObject();
                m.addProperty("id", mod.id());
                m.addProperty("name", mod.name());
                final JsonArray lines = new JsonArray();
                for (final tw.exilecore.item.AffixMod each : mod.mods()) {
                    lines.add(affixModText(each));
                }
                mod.addedFromPhysical().forEach((type, share) -> lines.add(String.format(Locale.ROOT, "物理傷害的 %d%% 額外附加為%s傷害",
                        Math.round(share * 100), type.displayName())));
                if (mod.vanillaSpeedBonus() > 0) {
                    lines.add(String.format(Locale.ROOT, "移動速度 +%d%%", Math.round(mod.vanillaSpeedBonus() * 100)));
                }
                if (mod.aura() != null) {
                    for (final tw.exilecore.item.AffixMod each : mod.aura().stats()) {
                        lines.add("光環（" + num(mod.aura().radius()) + " 格內的怪物）：" + affixModText(each));
                    }
                }
                if (mod.summon() != null) {
                    lines.add(String.format(Locale.ROOT, "每 %d 秒叫出 %d 隻同種的普通怪（最多 %d 隻）", mod.summon().intervalSeconds(),
                            mod.summon().count(), mod.summon().max()));
                }
                m.add("lines", lines);
                m.add("skills", bossSkillNames(sources, mod.skills()));
                if (mod.minLevel() > 1) {
                    m.addProperty("minLevel", mod.minLevel());
                }
                mods.add(m);
            }
            // 每一級都列（野外到地圖、終局的等級：到生命表的最後一個錨點，最多 100 級）
            final int top = Math.min(100, Math.max(config.maxLevel(), config.lifeAnchors().isEmpty() ? 0 : config.lifeAnchors().lastKey()));
            for (int level = 1; level <= top; level++) {
                final JsonObject l = new JsonObject();
                l.addProperty("lv", level);
                l.addProperty("life", Math.round(config.lifeAt(level)));
                final double[] damage = config.damageAt(level);
                l.addProperty("dmg", Math.round(damage[0]) + "–" + Math.round(damage[1]));
                l.addProperty("exp", Math.round(config.experienceAt(level)));
                l.addProperty("acc", Math.round(config.accuracyAt(level)));
                l.addProperty("eva", Math.round(config.evasionAt(level)));
                l.addProperty("arm", Math.round(config.armourAt(level)));
                levels.add(l);
            }
            out.addProperty("crit", config.critChance());
        }
        out.add("rarities", rarities);
        out.add("types", types);
        out.add("mods", mods);
        out.add("levels", levels);
        return out;
    }

    private static String affixModText(final tw.exilecore.item.AffixMod mod) {
        try {
            return CodexData.plain(ModifierText.describe(mod.toModifier(Map.of(), "圖鑑")));
        } catch (final RuntimeException e) {
            return mod.toString();
        }
    }

    private static JsonArray bossSkillNames(final Sources sources, final List<BossSkillUse> uses) {
        final JsonArray names = new JsonArray();
        for (final BossSkillUse use : uses) {
            final BossSkill skill = sources.skills() == null ? null : sources.skills().bossSkills().skill(use.id());
            names.add(skill == null ? use.id() : skill.name());
        }
        return names;
    }

    static JsonObject maps(final Sources sources) {
        final JsonObject out = new JsonObject();
        final MapConfig config = sources.maps();
        final JsonArray maps = new JsonArray();
        final JsonArray finals = new JsonArray();
        final JsonArray tiers = new JsonArray();
        if (config != null) {
            for (final MapConfig.MapDef map : config.maps()) {
                maps.add(mapJson(sources, map));
            }
            for (final MapConfig.MapDef map : config.finals()) {
                final JsonObject f = mapJson(sources, map);
                f.addProperty("kind", config.isEventPinnacle(map.id()) ? "事件巔峰" : config.pinnacle(map.id()) != null ? "巔峰" : "終局");
                final MapConfig.FinalInfo info = config.finalInfo(map.id());
                if (info != null) {
                    f.addProperty("points", info.points());
                    f.addProperty("levelBonus", info.levelBonus());
                }
                final MapConfig.Uber uber = config.uber(map.id());
                if (uber != null) {
                    final JsonObject u = new JsonObject();
                    u.addProperty("name", uber.name() != null ? uber.name() : map.boss() != null ? map.boss().name() + "・極境" : map.name()); // 跟地圖裝置一樣的寫法
                    u.addProperty("life", uber.life());
                    u.addProperty("damage", uber.damage());
                    u.addProperty("levelBonus", uber.levelBonus());
                    u.add("skills", bossSkillNames(sources, uber.skills()));
                    u.addProperty("phases", uber.phases().size());
                    final JsonArray lines = new JsonArray();
                    uber.monster().forEach(mod -> lines.add("Boss：" + affixModText(mod)));
                    uber.player().forEach(mod -> lines.add("玩家：" + affixModText(mod)));
                    u.add("lines", lines);
                    f.add("uber", u);
                }
                finals.add(f);
            }
            final List<Integer> levels = config.tiers() == null ? List.of() : config.tiers().levels();
            for (int tier = 1; tier <= levels.size(); tier++) {
                final JsonObject t = new JsonObject();
                t.addProperty("tier", tier);
                t.addProperty("level", levels.get(tier - 1));
                t.addProperty("life", round2(config.monsterLifeMultiplier(tier)));
                t.addProperty("damage", round2(config.monsterDamageMultiplier(tier)));
                t.addProperty("bossLife", round2(config.bossLifeMultiplier(tier)));
                tiers.add(t);
            }
        }
        out.add("maps", maps);
        out.add("finals", finals);
        out.add("tiers", tiers);
        return out;
    }

    private static JsonObject mapJson(final Sources sources, final MapConfig.MapDef map) {
        final JsonObject m = new JsonObject();
        m.addProperty("id", map.id());
        final ItemBase base = map.base() == null || sources.items() == null ? null : sources.items().base(map.base());
        // 一般地圖的名字寫在換界石的基底上（maps.yml 的 name 沒寫）
        m.addProperty("name", map.name() != null ? map.name() : base != null ? base.name() : map.id());
        m.addProperty("ring", map.ring());
        if (base != null && base.isMap()) {
            m.addProperty("tier", base.mapTier());
        }
        m.addProperty("theme", map.theme() == null ? "" : map.theme());
        if (map.lore() != null && !map.lore().isBlank()) {
            m.addProperty("lore", CodexData.plain(map.lore()));
        }
        final MapConfig.Boss boss = map.boss();
        if (boss != null) {
            final JsonObject b = new JsonObject();
            b.addProperty("name", boss.name());
            b.addProperty("named", boss.named());
            b.addProperty("life", boss.life());
            b.addProperty("damage", boss.damage());
            final JsonArray mods = new JsonArray();
            for (final String id : boss.mods()) {
                final MonsterConfig.MonsterMod mod = sources.monsters() == null ? null : sources.monsters().mod(id);
                mods.add(mod == null ? id : mod.name());
            }
            b.add("mods", mods);
            b.add("skills", bossSkillNames(sources, boss.skills()));
            b.addProperty("phases", boss.phases().size());
            if (boss.enrage() != null) {
                b.addProperty("enrage", String.format(Locale.ROOT, "%s 秒後狂暴：傷害 ×%s、速度 ×%s", num(boss.enrage().after()),
                        num(boss.enrage().damage()), num(boss.enrage().speed())));
            }
            m.add("boss", b);
        }
        return m;
    }

    static JsonObject events(final Sources sources) {
        final JsonObject out = new JsonObject();
        final JsonArray map = new JsonArray();
        final MapEventConfig mapEvents = sources.mapEvents();
        if (mapEvents != null) {
            final int total = Math.max(1, mapEvents.totalWeight());
            for (final Map.Entry<MapEventConfig.Kind, Integer> entry : mapEvents.weights().entrySet()) {
                final JsonObject e = new JsonObject();
                final String source = entry.getKey().name().toLowerCase(Locale.ROOT);
                e.addProperty("name", MapEventConfig.sourceName(source));
                e.addProperty("weight", entry.getValue());
                e.addProperty("share", Math.round(entry.getValue() * 1000.0 / total) / 10.0);
                e.addProperty("unique", mapEvents.uniqueChance(entry.getKey()));
                map.add(e);
            }
            final JsonArray shrines = new JsonArray();
            for (final MapEventConfig.Shrine shrine : mapEvents.shrines()) {
                final JsonObject s = new JsonObject();
                s.addProperty("name", shrine.name());
                s.add("lines", described(shrine.mods()));
                shrines.add(s);
            }
            out.add("shrines", shrines);
            final JsonArray boxes = new JsonArray();
            for (final MapEventConfig.Box box : mapEvents.boxes()) {
                final JsonObject b = new JsonObject();
                b.addProperty("name", box.name());
                b.addProperty("weight", box.weight());
                b.addProperty("rolls", box.rolls());
                b.addProperty("quantity", Math.round(box.quantityBonus() * 100));
                b.addProperty("rarity", Math.round(box.rarityBonus() * 100));
                b.addProperty("currency", box.minCurrency() + "～" + box.maxCurrency());
                b.addProperty("maps", box.minMaps() + "～" + box.maxMaps());
                b.addProperty("essences", box.minEssences() + "～" + box.maxEssences());
                boxes.add(b);
            }
            out.add("boxes", boxes);
        }
        out.add("map", map);
        final JsonArray monthly = new JsonArray();
        if (sources.events() != null) {
            for (final EventConfig.Theme theme : sources.events().themes()) {
                final JsonObject t = new JsonObject();
                t.addProperty("id", theme.id());
                t.addProperty("name", theme.name());
                t.addProperty("color", theme.color());
                t.addProperty("lore", CodexData.plain(theme.lore()));
                t.addProperty("quantity", theme.quantity());
                t.addProperty("rarity", theme.rarity());
                final JsonArray challenges = new JsonArray();
                for (final EventConfig.Challenge challenge : theme.challenges()) {
                    challenges.add(CodexData.plain(challenge.rendered()) + "（功勳 " + challenge.merits() + "）");
                }
                t.add("challenges", challenges);
                if (theme.title() != null) {
                    t.addProperty("title", theme.title().name());
                    t.add("titleStats", described(theme.title().stats()));
                }
                monthly.add(t);
            }
        }
        out.add("monthly", monthly);
        final JsonArray world = new JsonArray();
        final WorldEventConfig worldEvents = sources.worldEvents();
        if (worldEvents != null) {
            if (worldEvents.boss() != null) {
                final WorldEventConfig.Boss boss = worldEvents.boss();
                final JsonObject b = new JsonObject();
                b.addProperty("kind", "世界 Boss");
                b.addProperty("name", boss.name());
                final JsonArray lines = new JsonArray();
                lines.add("等級 " + boss.level() + "、生命 ×" + num(boss.life()) + "、傷害 ×" + num(boss.damage()));
                if (boss.lifePerPlayer() > 0) {
                    lines.add("每多一位 " + boss.countLevel() + " 級以上的玩家，生命多基礎的 " + Math.round(boss.lifePerPlayer() * 100) + "%"
                            + (boss.maxCounted() > 0 ? "（最多算 " + boss.maxCounted() + " 人）" : ""));
                }
                lines.add("招式：" + String.join("、", jsonStrings(bossSkillNames(sources, boss.skills()))));
                if (boss.uniqueChance() > 0) {
                    lines.add("傳奇掉落機率 " + num(boss.uniqueChance() * 100) + "%");
                }
                b.add("lines", lines);
                world.add(b);
            }
            if (worldEvents.invasion() != null) {
                final WorldEventConfig.Invasion invasion = worldEvents.invasion();
                final JsonObject i = new JsonObject();
                i.addProperty("kind", "入侵怪潮");
                i.addProperty("name", invasion.name());
                final JsonArray lines = new JsonArray();
                lines.add(invasion.waves() + " 波，每波 " + invasion.waveSeconds() + " 秒；每波 " + invasion.baseCount() + " 隻＋每人 "
                        + invasion.perPlayer() + " 隻（最多 " + invasion.maxCount() + "）");
                lines.add("怪物等級 " + invasion.levelMin() + "～" + invasion.levelMax() + "；每波稀有 " + invasion.rarePerWave() + "、魔法 " + invasion.magicPerWave());
                if (invasion.commander() != null) {
                    lines.add("統帥：" + invasion.commander().name() + "（生命 ×" + num(invasion.commander().life())
                            + "、傷害 ×" + num(invasion.commander().damage()) + "）");
                }
                i.add("lines", lines);
                world.add(i);
            }
        }
        out.add("world", world);
        return out;
    }

    // ---- 小工具 ----

    private static String label(final Sources sources, final String source) {
        if (source == null) {
            return "";
        }
        try {
            final String text = sources.sourceLabel() == null ? source : sources.sourceLabel().apply(source);
            return text == null ? source : text;
        } catch (final RuntimeException e) {
            return source;
        }
    }

    private static JsonArray described(final List<Modifier> modifiers) {
        final JsonArray lines = new JsonArray();
        if (modifiers == null) {
            return lines;
        }
        try {
            for (final String line : ModifierText.describeAll(modifiers)) {
                lines.add(CodexData.plain(line));
            }
        } catch (final RuntimeException e) {
            for (final Modifier modifier : modifiers) {
                lines.add(modifier.stat());
            }
        }
        return lines;
    }

    private static JsonArray strings(final List<String> values) {
        final JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> jsonStrings(final JsonArray array) {
        final List<String> list = new ArrayList<>();
        array.forEach(each -> list.add(each.getAsString()));
        return list;
    }

    /** 數字的寫法：整數不帶小數點，其他最多兩位。 */
    static String num(final double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return java.math.BigDecimal.valueOf(Math.round(value * 100) / 100.0).stripTrailingZeros().toPlainString();
    }

    private static double round2(final double value) {
        return Math.round(value * 100) / 100.0;
    }
}
