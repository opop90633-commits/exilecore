package tw.exilecore.skill;

import java.util.List;
import java.util.Map;

/**
 * 2026-11-03 照站長：技能說明文字（skills.yml 的 description）裡技能本身的範圍數字照實際施放乘範圍效果。
 * <p>
 * 照 PoE2（PoB2 CalcOffence calcRadius）：半徑 × √(1 + 增加的範圍效果) × √(更多)，就是 {@link SkillBuild#areaScale()}；
 * 這裡列的每一段（技能代號 → 說明裡的字樣）都是施放時真的乘 areaScale 的半徑、長度（SkillParams 的 radius、length、explode、
 * 地面效果的 radius，或程式裡乘了 build.areaScale() 的常數），字樣裡的數字換成乘過的、標白字，說明最後註明倍率。
 * 投射物距離、最遠距離、擊退距離、近戰距離、找目標的範圍這些不吃範圍效果的數字不列（照寫）。
 * 這次之前說明一律寫原始數字，掛了範圍輔助、範圍效果的天賦也不會變。
 * <p>
 * 字樣要跟 skills.yml 的說明一字不差（AreaTextTest 會檢查每一段都找得到），改說明時一起改這裡。
 */
final class AreaText {

    private AreaText() {
    }

    /** 技能代號 → 說明裡吃範圍效果的字樣（數字後面接「 格」）。 */
    static final Map<String, List<String>> SNIPPETS = Map.ofEntries(
            Map.entry("venom_etch_arrow", List.of("落點 1.8 格")),
            Map.entry("myriad_arrow_fall", List.of("落點 0.7 格")),
            Map.entry("sky_rend_arrow", List.of("衝擊 1 格")),
            Map.entry("thunder_surge", List.of("往前 8 格")),
            Map.entry("spinning_spear_strike", List.of("旋風（1.8 格")),
            Map.entry("collapse_quake_totem", List.of("周圍 3 格")),
            Map.entry("fortress_crumble_shell", List.of("周圍 4 格")),
            Map.entry("flowing_flame_canopy", List.of("落在 3 格")),
            Map.entry("thunder_cloud", List.of("旁邊（3.6 格")),
            Map.entry("erosion_curse_rift", List.of("混沌（2 格")),
            Map.entry("shattered_bone_darts", List.of("炸開 0.5 格")),
            Map.entry("earth_spike_eruption", List.of("一圈 1 格", "爆炸（2 格")),
            Map.entry("lava_splash", List.of("炸開（1.6 格")),
            Map.entry("rushing_thunder_stomp", List.of("猛擊 3.2 格")),
            Map.entry("electrocute_shaft", List.of("炸開 2 格")),
            Map.entry("snake_squall_thrust", List.of("前方 10 格")),
            Map.entry("facet_light_rift", List.of("元素（1.6 格")),
            Map.entry("blazing_sun_spear", List.of("面從 2 格")),
            Map.entry("thunder_escape", List.of("周圍 3 格")),
            Map.entry("flame_core_detonation", List.of("每層 0.6 格")),
            Map.entry("scorching_sun_pearl", List.of("周圍 2.5 格")),
            Map.entry("cinder_sprout", List.of("周圍 2.5 格")),
            Map.entry("plague_taint", List.of("附近 1.7 格")),
            Map.entry("gathered_thundercloud", List.of("雷雨（2.3 格")),
            Map.entry("wild_whirlwind", List.of("旋風（3 格")),
            Map.entry("lashing_thorns", List.of("長區（4 格")),
            Map.entry("volcano", List.of("周圍 3 格")),
            Map.entry("root_bind", List.of("一道 8 格", "纏住 1.5 格")),
            Map.entry("corpse_flame_burst", List.of("波及 2.2 格")),
            Map.entry("thunder_current_zone", List.of("秒朝 1.9 格")),
            Map.entry("fading_thunder_ring", List.of(" 到 2.6 格")),
            Map.entry("bleeding_concoction", List.of("爆炸（1.8 格")),
            Map.entry("fracture_tonic", List.of("爆炸（1.8 格")),
            Map.entry("explosive_concoction", List.of("爆炸（1.8 格")),
            Map.entry("thunder_blast_tonic", List.of("爆炸（1.8 格")),
            Map.entry("acidic_concoction", List.of("爆炸（1.8 格")),
            Map.entry("gap_opportunity", List.of("放出 4 格")),
            Map.entry("proc_flame_pillar", List.of("火柱（1.5 格")),
            Map.entry("proc_shock_rift", List.of("半徑 2 格")),
            Map.entry("proc_bramble", List.of("一下（1 格")),
            Map.entry("proc_caltrops", List.of("炸開（0.5 格")),
            Map.entry("proc_mana_flare", List.of("炸開（2 格")),
            Map.entry("proc_quill_burst", List.of("周圍 1.5 格")),
            Map.entry("proc_fan_flames", List.of("炸開 3.5 格")));

    /** 倍率跟 1 差這麼少就當作沒變（照寫原始數字）。 */
    private static final double EPSILON = 1e-6;

    /**
     * 說明文字（已經 escape 過、放在灰字裡）：列出的字樣裡的數字乘 areaScale、標白字；有換到的話最後加一句倍率。
     * areaScale 是 1（沒有範圍效果、或技能不是範圍技能）就照原樣。
     */
    static String description(final String skillId, final String text, final double areaScale) {
        final List<String> snippets = SNIPPETS.get(skillId);
        if (snippets == null || text == null || Math.abs(areaScale - 1) < EPSILON) {
            return text;
        }
        String out = text;
        boolean changed = false;
        for (final String snippet : snippets) {
            final int at = out.indexOf(snippet);
            if (at < 0) {
                continue;
            }
            out = out.substring(0, at) + scaled(snippet, areaScale) + out.substring(at + snippet.length());
            changed = true;
        }
        if (!changed) {
            return text;
        }
        return out + String.format(java.util.Locale.ROOT, " <dark_gray>（白字的範圍已含範圍效果：半徑 ×%.2f）", areaScale);
    }

    /** 「周圍 2.5 格」→「周圍 <white>3.5</white><gray> 格」（字樣裡最後一個數字）。 */
    static String scaled(final String snippet, final double areaScale) {
        final java.util.regex.Matcher matcher = NUMBER.matcher(snippet);
        int start = -1;
        int end = -1;
        while (matcher.find()) {
            start = matcher.start();
            end = matcher.end();
        }
        if (start < 0) {
            return snippet;
        }
        final double value = Double.parseDouble(snippet.substring(start, end)) * areaScale;
        return snippet.substring(0, start) + "<white>" + ModifierText.number(value) + "</white><gray>" + snippet.substring(end);
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
}
