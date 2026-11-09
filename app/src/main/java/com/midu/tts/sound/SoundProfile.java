package com.midu.tts.sound;

import com.midu.tts.engine.EngineOption;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一套声音参数（预设）。values 存每个 Knob 的值，enabled 存「这一项是否下发」。
 * 只有 enabled 且值非空的项才会变成 EngineOption；基础三项（speed/pitch/volume）
 * 走 NativeEngine 的专用参数，不在扩展选项里重复下发。
 */
public final class SoundProfile {
    public String id;
    public String name;
    public boolean builtin;
    public final Map<String, String> values = new LinkedHashMap<>();
    public final Set<String> enabled = new LinkedHashSet<>();

    public SoundProfile(String id, String name, boolean builtin) {
        this.id = id;
        this.name = name;
        this.builtin = builtin;
        for (Knob k : Knobs.all()) {
            values.put(k.key, k.def);
            if (k.defaultEnabled) enabled.add(k.key);
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

    public SoundProfile setEnabled(String key, boolean on) {
        if (on) enabled.add(key); else enabled.remove(key);
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

    public boolean isEnabled(String key) { return enabled.contains(key); }

    /** 把系统 TTS 的百分数速率叠乘到当前档位上。 */
    public int speedFor(int ratePercent) {
        return clamp(Math.round(intOf(Knobs.K_SPEED) * (ratePercent > 0 ? ratePercent : 100) / 100f), 1, 20);
    }
    public int pitchFor(int pitchPercent) {
        return clamp(Math.round(intOf(Knobs.K_PITCH) * (pitchPercent > 0 ? pitchPercent : 100) / 100f), 1, 20);
    }
    public int volume() { return clamp(intOf(Knobs.K_VOLUME), 0, 100); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 生成要下发的扩展选项（不含基础三项）。 */
    public List<EngineOption> toOptions() {
        List<EngineOption> out = new ArrayList<>();
        for (Knob k : Knobs.all()) {
            if (k.key.equals(Knobs.K_SPEED) || k.key.equals(Knobs.K_PITCH)
                    || k.key.equals(Knobs.K_VOLUME)) continue;
            if (!enabled.contains(k.key)) continue;
            String v = str(k.key);
            if (v.isEmpty()) continue;
            out.add(new EngineOption(k.key, v, k.type));
        }
        return out;
    }

    public int enabledCount() {
        int n = 0;
        for (String k : enabled) if (Knobs.byKey(k) != null) n++;
        return n;
    }

    public String describe() {
        return "语速 " + intOf(Knobs.K_SPEED) + " · 音高 " + intOf(Knobs.K_PITCH)
                + " · 音量 " + volume() + " · 下发 " + enabledCount() + " 项";
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
