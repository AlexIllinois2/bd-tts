package com.bd.tts.source;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.bd.tts.util.Io;

/**
 * 「用户自选目录（普通路径 / SAF 树）」来源：目录本身就是 midu 资源根
 * （speech_license.licbag + zh-cn/ + 各音色目录）。zip 形态的包由 Provisioner
 * 按需解压到 rig 后同样以目录形态出现。
 */
public final class VoiceSources {
    private VoiceSources() {}

    private static final String[] BASE_DIRS = {"zh-cn", "frontend", "volcano_speech_tracking_log"};

    private static boolean isBase(String n) {
        for (String b : BASE_DIRS) if (b.equals(n)) return true;
        return n.startsWith(".") || n.endsWith(".txt") || n.endsWith(".licbag");
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
