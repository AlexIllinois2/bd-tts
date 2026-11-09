package com.midu.tts.source;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.midu.tts.util.Io;

/** 内置 assets 与「用户自选目录（普通路径 / SAF 树）」两种来源。 */
public final class VoiceSources {
    private VoiceSources() {}

    private static final String[] BASE_DIRS = {"zh-cn", "frontend", "volcano_speech_tracking_log"};

    private static boolean isBase(String n) {
        for (String b : BASE_DIRS) if (b.equals(n)) return true;
        return n.startsWith(".") || n.endsWith(".txt") || n.endsWith(".licbag");
    }

    public static final class Bundled implements VoiceSource {
        private final Context ctx;
        private final String version;
        public Bundled(Context c) {
            ctx = c.getApplicationContext();
            String v = "0";
            try { v = String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode); }
            catch (PackageManager.NameNotFoundException ignored) {}
            version = v;
        }
        @Override public String id() { return "bundled"; }

        @Override public InputStream open(String rel) throws Exception {
            return ctx.getAssets().open("midu/" + rel);
        }

        @Override public String fingerprint(String rel) throws Exception {
            long len = -1;
            try (AssetFileDescriptor fd = ctx.getAssets().openFd("midu/" + rel)) { len = fd.getLength(); }
            catch (Exception ignored) { }
            return "bundled:" + version + ":" + len;
        }

        @Override public String[] listVoices() throws Exception {
            List<String> out = new ArrayList<>();
            try {
                String idx = new String(Io.readAll(ctx.getAssets().open("midu/voices.txt")),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String s : idx.split("[\\r\\n]+")) {
                    String t = s.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
                }
            } catch (Exception ignored) {
                String[] top = ctx.getAssets().list("midu");
                if (top != null) {
                    for (String n : top) {
                        if (isBase(n)) continue;
                        try (InputStream in = ctx.getAssets().open("midu/" + n + "/ptl.dat")) {
                            out.add(n);
                        } catch (Exception ignored2) {}
                    }
                }
            }
            if (out.isEmpty()) out.add("BV001_24k_streaming");
            return out.toArray(new String[0]);
        }
        @Override public void close() {}
    }

    public static final class Directory implements VoiceSource {
        private final Context ctx;
        private final String path;
        private final boolean saf;
        private DocumentFile tree;

        public Directory(Context c, String p) {
            ctx = c.getApplicationContext();
            path = p;
            saf = p != null && p.startsWith("content://");
            if (saf) {
                try {
                    tree = DocumentFile.fromTreeUri(ctx, Uri.parse(p));
                } catch (Exception ignored) {}
            }
        }
        @Override public String id() { return saf ? "saf:" + path : "dir:" + path; }

        private DocumentFile doc(String rel) {
            DocumentFile d = tree;
            if (d == null) return null;
            for (String seg : rel.split("/")) {
                if (seg.isEmpty()) continue;
                d = d.findFile(seg);
                if (d == null) return null;
            }
            return d;
        }

        @Override public InputStream open(String rel) throws Exception {
            if (saf) {
                DocumentFile d = doc(rel);
                if (d == null) throw new java.io.FileNotFoundException("SAF 找不到 " + rel);
                return ctx.getContentResolver().openInputStream(d.getUri());
            }
            return new FileInputStream(new File(path, rel));
        }

        @Override public String fingerprint(String rel) throws Exception {
            if (saf) {
                DocumentFile d = doc(rel);
                return d == null ? "-1:0" : d.length() + ":" + d.lastModified();
            }
            File f = new File(path, rel);
            return f.exists() ? f.length() + ":" + f.lastModified() : "-1:0";
        }

        @Override public String[] listVoices() throws Exception {
            List<String> out = new ArrayList<>();
            if (saf) {
                if (tree == null) return new String[0];
                for (DocumentFile d : tree.listFiles()) {
                    String n = d.getName();
                    if (n == null || isBase(n) || !d.isDirectory()) continue;
                    if (d.findFile("ptl.dat") != null) out.add(n);
                }
            } else {
                File root = new File(path);
                File[] kids = root.listFiles();
                if (kids != null) {
                    for (File f : kids) {
                        if (!f.isDirectory() || isBase(f.getName())) continue;
                        if (new File(f, "ptl.dat").isFile()) out.add(f.getName());
                    }
                }
            }
            String[] arr = out.toArray(new String[0]);
            Arrays.sort(arr);
            return arr;
        }
        @Override public void close() {}
    }
}
