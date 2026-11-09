package com.midu.tts.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Io {
    private Io() {}

    public static File ensureDir(File d) throws IOException {
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory())
            throw new IOException("无法创建目录 " + d);
        return d;
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    public static String readUtf8(File f) throws IOException {
        return new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
    }

    /** 原子写：先 .tmp 再 rename，避免 TTS 服务读到半截文件。 */
    public static void atomicWrite(File f, byte[] data) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp, false)) {
            os.write(data);
            os.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename 失败 " + tmp + " -> " + f);
    }
    public static void atomicWrite(File f, String text) throws IOException {
        atomicWrite(f, text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "sha-unavailable";
        }
    }

    public static boolean isSafeSegment(String s) {
        return s != null && !s.isEmpty() && !s.contains("/") && !s.contains("\\")
                && !s.equals(".") && !s.equals("..") && !s.contains("\0");
    }
}
