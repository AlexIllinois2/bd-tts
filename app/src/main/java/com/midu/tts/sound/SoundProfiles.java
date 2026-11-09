package com.midu.tts.sound;

import android.content.Context;
import android.content.SharedPreferences;

import com.midu.tts.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 声音预设仓库。存 SharedPreferences（midu_sound），JSON 格式，可整体导入导出。
 * 每次合成都重新读 active()，所以改参数不需要重建引擎（只有改规则/音色才需要）。
 */
public final class SoundProfiles {
    private static final String FILE = "midu_sound";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE = "active";

    public static final String ID_STD   = "std";
    public static final String ID_CALM  = "calm";
    public static final String ID_BRIGHT= "bright";
    public static final String ID_NOVEL = "novel";

    private SoundProfiles() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 首次运行时生成内置预设，并把 0.1.x 的 speed/pitch/volume 迁进「标准」。 */
    public static synchronized List<SoundProfile> ensureDefaults(Context c) {
        String raw = sp(c).getString(KEY_PROFILES, "");
        if (!raw.isEmpty()) {
            List<SoundProfile> l = parse(raw);
            if (!l.isEmpty()) return l;
        }
        List<SoundProfile> l = new ArrayList<>();
        Prefs old = Prefs.get(c);
        SoundProfile std = new SoundProfile(ID_STD, "标准", true);
        std.put(Knobs.K_SPEED, String.valueOf(old.speed()))
           .put(Knobs.K_PITCH, String.valueOf(old.pitch()))
           .put(Knobs.K_VOLUME, String.valueOf(old.volume()));
        l.add(std);

        SoundProfile calm = new SoundProfile(ID_CALM, "沉稳播报", true);
        calm.put(Knobs.K_SPEED, "8").put(Knobs.K_PITCH, "8")
            .put("tts_silence_duration", "140");
        l.add(calm);

        SoundProfile bright = new SoundProfile(ID_BRIGHT, "轻快", true);
        bright.put(Knobs.K_SPEED, "12").put(Knobs.K_PITCH, "12");
        l.add(bright);

        SoundProfile novel = new SoundProfile(ID_NOVEL, "小说慢读", true);
        novel.put(Knobs.K_SPEED, "9")
             .put("tts_sub_sentence_length", "40")
             .put("tts_silence_duration", "220");
        l.add(novel);

        save(c, l);
        if (sp(c).getString(KEY_ACTIVE, "").isEmpty()) sp(c).edit().putString(KEY_ACTIVE, ID_STD).apply();
        return l;
    }

    public static synchronized List<SoundProfile> all(Context c) { return ensureDefaults(c); }

    public static synchronized void save(Context c, List<SoundProfile> list) {
        JSONArray a = new JSONArray();
        for (SoundProfile p : list) a.put(p.toJson());
        sp(c).edit().putString(KEY_PROFILES, a.toString()).apply();
    }

    public static synchronized SoundProfile active(Context c) {
        List<SoundProfile> l = ensureDefaults(c);
        String id = sp(c).getString(KEY_ACTIVE, ID_STD);
        for (SoundProfile p : l) if (p.id.equals(id)) return p;
        return l.get(0);
    }

    public static synchronized void setActive(Context c, String id) {
        sp(c).edit().putString(KEY_ACTIVE, id).apply();
    }

    public static synchronized SoundProfile byId(Context c, String id) {
        if (id == null || id.isEmpty()) return active(c);
        for (SoundProfile p : all(c)) if (p.id.equals(id)) return p;
        return active(c);
    }

    /** 以当前预设为模板新建一份自定义预设，并切成当前。 */
    public static synchronized SoundProfile addFromActive(Context c, String name) {
        List<SoundProfile> l = new ArrayList<>(all(c));
        SoundProfile p = active(c).copy();
        p.id = "custom_" + System.currentTimeMillis();
        p.name = name == null || name.trim().isEmpty() ? "自定义预设" : name.trim();
        p.builtin = false;
        l.add(p);
        save(c, l);
        setActive(c, p.id);
        return p;
    }

    public static synchronized void update(Context c, SoundProfile changed) {
        List<SoundProfile> l = new ArrayList<>(all(c));
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(changed.id)) { l.set(i, changed); break; }
        }
        save(c, l);
    }

    public static synchronized void remove(Context c, String id) {
        List<SoundProfile> l = new ArrayList<>(all(c));
        for (SoundProfile p : l) if (p.id.equals(id) && p.builtin) return;  // 内置不可删
        List<SoundProfile> out = new ArrayList<>();
        for (SoundProfile p : l) if (!p.id.equals(id)) out.add(p);
        save(c, out);
        if (id.equals(sp(c).getString(KEY_ACTIVE, ""))) setActive(c, ID_STD);
    }

    public static synchronized void rename(Context c, String id, String name) {
        for (SoundProfile p : all(c)) if (p.id.equals(id)) {
            p.name = name;
            update(c, p);
            return;
        }
    }

    public static synchronized String exportJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("format", "midu-sound-profiles/1");
            o.put("active", sp(c).getString(KEY_ACTIVE, ID_STD));
            JSONArray a = new JSONArray();
            for (SoundProfile p : all(c)) a.put(p.toJson());
            o.put("profiles", a);
        } catch (Exception ignored) {}
        return o.toString();
    }

    public static synchronized boolean importJson(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray a = o.optJSONArray("profiles");
            if (a == null || a.length() == 0) return false;
            List<SoundProfile> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null) l.add(SoundProfile.fromJson(j));
            }
            if (l.isEmpty()) return false;
            save(c, l);
            String act = o.optString("active", l.get(0).id);
            setActive(c, act);
            return true;
        } catch (Exception e) { return false; }
    }

    public static synchronized void reset(Context c) {
        sp(c).edit().remove(KEY_PROFILES).remove(KEY_ACTIVE).apply();
        ensureDefaults(c);
    }

    private static List<SoundProfile> parse(String raw) {
        List<SoundProfile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null) out.add(SoundProfile.fromJson(j));
            }
        } catch (Exception ignored) {}
        return out;
    }
}
