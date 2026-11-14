package com.bd.tts.sound;

import com.bd.tts.engine.EngineOption;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一套声音参数（预设）。values 存每个 Knob 的值。
 * 下发规则：基础三项（speed/pitch/volume）走 NativeEngine 专用参数；
 * 其余非空项全部作为 EngineOption 下发。调用方（系统 TTS / Legado HTTP）
 * 显式给出的参数优先生效，未给出的参数才用预设里自己设定的值。
 */
public final class SoundProfile {
    public String id;
    public String name;
    public boolean builtin;
    public final Map<String, String> values = new LinkedHashMap<>();
    /** 仅作旧版 JSON 导入导出兼容；下发与否不再由它决定。 */
    public final Set<String> enabled = new LinkedHashSet<>();

    public SoundProfile(String id, String name, boolean builtin) {
        this.id = id;
        this.name = name;
        this.builtin = builtin;
        for (Knob k : Knobs.all()) {
            values.put(k.key, k.def);
        }
    }

    public SoundProfile copy() {
        SoundProfile p = new SoundProfile(id, name, builtin);
        p.values.clear();
        p.values.putAll(values);
        p.enabled.clear();
        p.enabled.addAll(enabled);
        return p;
    }

    public SoundProfile put(String key, String value) {
        if (Knobs.byKey(key) != null) values.put(key, value == null ? "" : value);
        return this;
    }

    public String str(String key) {
        String v = values.get(key);
        if (v != null) return v;
        Knob k = Knobs.byKey(key);
        return k == null ? "" : k.def;
    }

    public int intOf(String key) {
        try { return Integer.parseInt(str(key).trim()); }
        catch (Exception e) {
            Knob k = Knobs.byKey(key);
            try { return k == null ? 0 : Integer.parseInt(k.def); } catch (Exception ex) { return 0; }
        }
    }

    public boolean boolOf(String key) {
        String v = str(key).trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    /** 把系统 TTS 的百分数速率叠乘到当前档位上。 */
    public int speedFor(int ratePercent) {
        return clamp(Math.round(intOf(Knobs.K_SPEED) * (ratePercent > 0 ? ratePercent : 100) / 100f), 1, 20);
    }
    public int pitchFor(int pitchPercent) {
        return clamp(Math.round(intOf(Knobs.K_PITCH) * (pitchPercent > 0 ? pitchPercent : 100) / 100f), 1, 20);
    }
    public int volume() { return clamp(intOf(Knobs.K_VOLUME), 0, 100); }
    /** 本地立体声展宽度（0=关，单声道原样输出）。 */
    public int stereoWidth() { return clamp(intOf(Knobs.K_STEREO_WIDTH), 0, 100); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 生成要下发的扩展选项（不含基础三项；值非空即下发，调用方给的参数优先）。
     *  bd_ 前缀的本地后处理键不下发引擎（引擎不认识，host2 O 快照也会被污染）。 */
    public List<EngineOption> toOptions() {
        List<EngineOption> out = new ArrayList<>();
        for (Knob k : Knobs.all()) {
            if (Knobs.isLocalOnly(k.key)) continue;
            if (k.key.equals(Knobs.K_SPEED) || k.key.equals(Knobs.K_PITCH)
                    || k.key.equals(Knobs.K_VOLUME)) continue;
            String v = str(k.key);
            if (v.isEmpty()) continue;
            out.add(new EngineOption(k.key, v, k.type));
        }
        return out;
    }

    public int enabledCount() {
        return toOptions().size();
    }

    public String describe() {
        return "语速 " + intOf(Knobs.K_SPEED) + " · 音高 " + intOf(Knobs.K_PITCH)
                + " · 音量 " + volume() + " · 扩展 " + enabledCount() + " 项"
                + (stereoWidth() > 0 ? " · 展宽 " + stereoWidth() + "%" : "");
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("name", name).put("builtin", builtin);
            JSONObject v = new JSONObject();
            for (Map.Entry<String, String> e : values.entrySet()) v.put(e.getKey(), e.getValue());
            o.put("values", v);
            o.put("enabled", new JSONArray(enabled));
        } catch (Exception ignored) {}
        return o;
    }

    public static SoundProfile fromJson(JSONObject o) {
        String id = o.optString("id", "custom_" + System.currentTimeMillis());
        String name = o.optString("name", "未命名预设");
        SoundProfile p = new SoundProfile(id, name, o.optBoolean("builtin", false));
        JSONObject v = o.optJSONObject("values");
        if (v != null) {
            java.util.Iterator<String> it = v.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (Knobs.byKey(k) != null) p.values.put(k, v.optString(k, ""));
            }
        }
        JSONArray e = o.optJSONArray("enabled");
        p.enabled.clear();
        if (e != null) {
            for (int i = 0; i < e.length(); i++) {
                String k = e.optString(i, "");
                if (Knobs.byKey(k) != null) p.enabled.add(k);
            }
        } else {
            // 老格式兼容：没有 enabled 就按表里的 defaultEnabled。
            for (Knob k : Knobs.all()) if (k.defaultEnabled) p.enabled.add(k.key);
        }
        return p;
    }
}
