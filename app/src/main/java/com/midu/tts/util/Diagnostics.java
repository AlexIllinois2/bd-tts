package com.midu.tts.util;

import android.content.Context;
import android.os.Build;

import com.midu.tts.rules.RuleManager;
import com.midu.tts.shizuku.ShizukuClient;
import com.midu.tts.source.ResourceRoot;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;

/** 设置页 / MuMu 调试用的环境自检：ABI、库闭包、资源、规则、Shizuku。 */
public final class Diagnostics {
    private Diagnostics() {}

    private static final String[] REQUIRED_SO = {
            "libmidu_bridge.so", "libspeechengine.so", "libsscronet.so",
            "libttboringssl.so", "libttcrypto.so", "libicuuc.so", "libicui18n.so",
            "libc++_shared.so", "libspeexdsp.so", "icu_shim.so",
            "cronet_shim4.so", "log_shim.so"
    };

    private static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String s = r.readLine();
            r.close();
            p.waitFor();
            return s == null ? "" : s.trim();
        } catch (Exception e) { return ""; }
    }

    public static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 运行环境 ==\n");
        sb.append("SUPPORTED_ABIS : ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("Android        : ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abilist        : ").append(getprop("ro.product.cpu.abilist")).append('\n');
        sb.append("abilist64      : ").append(getprop("ro.product.cpu.abilist64")).append('\n');
        sb.append("native.bridge  : ").append(getprop("ro.dalvik.vm.native.bridge")).append('\n');
        sb.append("★ 必须含 arm64-v8a：厂商库只有 64 位 ARM 原件\n");
        String libDir = c.getApplicationInfo().nativeLibraryDir;
        sb.append("nativeLibraryDir: ").append(libDir).append('\n');

        sb.append("\n== 原生库 ==\n");
        for (String n : REQUIRED_SO) {
            File f = new File(libDir, n);
            sb.append(f.isFile() ? "✓ " : "× ").append(n)
                    .append(f.isFile() ? "  " + f.length() + " B" : "  (缺)").append('\n');
        }

        sb.append("\n== 资源 / 规则 ==\n");
        File root = ResourceRoot.dir(c);
        String[] rel = {
                "speech_license.licbag", "zh-cn/ptl.dat", "zh-cn/ptl.idx",
                ResourceRoot.RULE_REL, ResourceRoot.ENG_DICT_REL
        };
        for (String r : rel) {
            File f = new File(root, r);
            sb.append(f.isFile() ? "✓ " : "× ").append(r)
                    .append(f.isFile() ? "  " + f.length() + " B" : "").append('\n');
        }
        for (String n : new String[]{"vendor_unified_rule.txt", "upstream_unified_rule.txt",
                "upstream_rule_patch.txt", "user_rules.txt", "merged_unified_rule.txt"}) {
            File f = new File(RuleManager.dir(c), n);
            sb.append(f.isFile() ? "✓ " : "· ").append(n)
                    .append(f.isFile() ? "  " + f.length() + " B" : "  (尚无)").append('\n');
        }
        try {
            sb.append("assets 规则种子 : ").append(
                    Io.readAll(c.getAssets().open("rules/vendor_unified_rule.txt")).length).append(" B\n");
        } catch (Exception e) {
            sb.append("assets 规则种子 : × 缺（先跑 scripts/prepare_vendor.py）\n");
        }
        sb.append("\n== Shizuku ==\n").append(ShizukuClient.describe()).append('\n');
        return sb.toString();
    }
}
