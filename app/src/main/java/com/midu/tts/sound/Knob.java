package com.midu.tts.sound;

import com.midu.tts.engine.EngineOption;

/**
 * 一个可下发的引擎选项的「说明书」。UI 由这张表自动生成控件，
 * 所以要把 libspeechengine.so 的发音能力暴露出来，只需要往 Knobs 里加一条。
 *
 * 证据等级（与 docs/PRONUNCIATION_KNOBS.md 对应）：
 *   VERIFIED = rig 实测 / 本工程已接；
 *   RIG_ENV  = rig 的 env 里验过键存在；
 *   STATIC   = 只在 .so 字符串里出现，未实测，默认不下发。
 */
public final class Knob {
    public static final int VERIFIED = 0;
    public static final int RIG_ENV  = 1;
    public static final int STATIC   = 2;

    public final String key;
    public final String title;
    public final String desc;
    public final String group;
    public final int type;          // EngineOption.STRING / INT / BOOL / DOUBLE
    public final String def;
    public final int min;
    public final int max;
    public final String unit;
    public final String[] choices;  // type==STRING 且 choices 非空时渲染成下拉
    public final int evidence;
    public final boolean defaultEnabled;

    private Knob(Builder b) {
        this.key = b.key;
        this.title = b.title;
        this.desc = b.desc;
        this.group = b.group;
        this.type = b.type;
        this.def = b.def;
        this.min = b.min;
        this.max = b.max;
        this.unit = b.unit;
        this.choices = b.choices;
        this.evidence = b.evidence;
        this.defaultEnabled = b.defaultEnabled;
    }

    public boolean isSlider() { return type == EngineOption.INT || type == EngineOption.DOUBLE; }
    public boolean isSwitch() { return type == EngineOption.BOOL; }
    public boolean isChoice() { return choices != null && choices.length > 0; }

    public String evidenceLabel() {
        switch (evidence) {
            case VERIFIED: return "已实测";
            case RIG_ENV:  return "rig env 已验";
            default:       return "仅静态证据";
        }
    }

    public static Builder builder(String key, String title, String group) {
        return new Builder(key, title, group);
    }

    public static final class Builder {
        final String key, title, group;
        String desc = "";
        int type = EngineOption.STRING;
        String def = "";
        int min = 0, max = 100;
        String unit = "";
        String[] choices = null;
        int evidence = STATIC;
        boolean defaultEnabled = false;

        Builder(String key, String title, String group) {
            this.key = key; this.title = title; this.group = group;
        }
        public Builder desc(String v) { desc = v; return this; }
        public Builder string(String def) { type = EngineOption.STRING; this.def = def; return this; }
        public Builder bool(boolean def) { type = EngineOption.BOOL; this.def = def ? "1" : "0"; return this; }
        public Builder integer(int def, int min, int max) {
            type = EngineOption.INT; this.def = String.valueOf(def);
            this.min = min; this.max = max; return this;
        }
        public Builder dbl(double def, int min, int max) {
            type = EngineOption.DOUBLE; this.def = String.valueOf(def);
            this.min = min; this.max = max; return this;
        }
        public Builder unit(String v) { unit = v; return this; }
        public Builder choices(String... v) { type = EngineOption.STRING; choices = v;
            if (v.length > 0) def = v[0]; return this; }
        public Builder evidence(int v) { evidence = v; defaultEnabled = v != STATIC; return this; }
        public Builder defaultEnabled(boolean v) { defaultEnabled = v; return this; }
        public Knob build() { return new Knob(this); }
    }
}
