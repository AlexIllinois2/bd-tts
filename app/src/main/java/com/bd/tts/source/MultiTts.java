package com.bd.tts.source;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.util.LinkedHashSet;

/**
 * MultiTTS 安装检测与其 apk 路径。
 *
 * Android 11+ 需要在 Manifest 的 <queries> 里声明 org.nobody.multitts，
 * 否则 getApplicationInfo 直接抛 NameNotFoundException，看起来像「没安装」。
 */
public final class MultiTts {
    private MultiTts() {}

    public static final String PKG = "org.nobody.multitts";

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * MultiTTS 的 base.apk 与 split apk（arm64 so 通常在 split_config.arm64_v8a.apk 里）
     * 的绝对路径。未安装返回空数组。注意：路径拿得到不代表本 app 读得动
     * （Android 11+ 禁止直接 open 其它 app 的 apk），读不读得动由调用方 try/catch。
     */
    public static String[] apkPaths(Context c) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(PKG, 0);
            LinkedHashSet<String> out = new LinkedHashSet<>();
            if (ai.sourceDir != null) out.add(ai.sourceDir);
            if (ai.splitSourceDirs != null)
                for (String s : ai.splitSourceDirs) if (s != null) out.add(s);
            return out.toArray(new String[0]);
        } catch (Exception e) {
            return new String[0];
        }
    }
}
