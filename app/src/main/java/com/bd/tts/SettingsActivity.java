package com.bd.tts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.bd.tts.engine.EngineController;
import com.bd.tts.rules.RuleManager;
import com.bd.tts.rules.RuleUpdateService;
import com.bd.tts.shizuku.ShizukuClient;
import com.bd.tts.source.ResourceRoot;
import com.bd.tts.util.Diagnostics;
import com.bd.tts.util.Io;
import com.bd.tts.util.Prefs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rikka.shizuku.Shizuku;

/** 引擎设置页：语音来源、音色、后端、韵律、三层规则、自检。 */
public class SettingsActivity extends Activity {

    private static final int REQ_TREE = 101, REQ_IMPORT = 102, REQ_EXPORT = 103;

    private TextView status;
    private TextView mergeReport;
    private TextView diag;
    private RadioGroup sourceGroup;
    private EditText customPath, multittsPath, rigPath, ruleUrl, intervalEdit;
    private EditText speedEdit, pitchEdit, volumeEdit;
    private EditText userRules;
    private EditText qWord, qPron, qValue, qComment;
    private Spinner voiceSpinner, backendSpinner, qType;
    private CheckBox autoUpdate;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                toast(grantResult == PackageManager.PERMISSION_GRANTED ? "Shizuku 已授权" : "Shizuku 被拒绝");
                refreshStatus();
            };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("BD TTS 引擎设置");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(sv);

        status = label(root, "状态：读取中…", 15, true);
        row(root, button("刷新状态", v -> refreshStatus()),
                button("冒烟自检", v -> smoke()),
                button("冒烟并播放", v -> smokeAndPlay()),
                button("系统 TTS 设置", v -> openSystemTtsSettings()));

        section(root, "① 离线语音位置（用户可自选）");
        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbDir = radio("语音包文件夹（zip / apk / 解压目录）", Prefs.SOURCE_DIRECTORY);
        RadioButton rbShizuku = radio("MultiTTS 字节语音包（Shizuku 直读）", Prefs.SOURCE_SHIZUKU);
        sourceGroup.addView(rbDir); sourceGroup.addView(rbShizuku);
        root.addView(sourceGroup);

        customPath = edit(root, "自定义目录（普通路径或 content:// 树）", Prefs.get(this).customPath());
        row(root, button("选择目录 (SAF)", v -> pickTree()),
                button("保存路径", v -> {
                    Prefs.get(this).customPath(customPath.getText().toString().trim());
                    EngineController.get(this).invalidate();
                    toast("已保存");
                }));
        multittsPath = edit(root, "MultiTTS midu 根目录", Prefs.get(this).multittsRoot());
        row(root, button("检测 / 授权 Shizuku", v -> {
                    ShizukuClient.requestPermission(this);
                    refreshStatus();
                }),
                button("保存并试读", v -> {
                    Prefs.get(this).multittsRoot(multittsPath.getText().toString().trim());
                    Prefs.get(this).source(Prefs.SOURCE_SHIZUKU);
                    syncSourceSelection();
                    asyncProbe();
                }));
        row(root, button("刷新音色列表", v -> asyncVoices()),
                button("应用来源并重建", v -> {
                    saveSource();
                    EngineController.get(this).invalidate();
                    toast("已重建，下次合成生效");
                    refreshStatus();
                }));
        voiceSpinner = spinner(root, "当前音色", new String[]{Prefs.get(this).voice()});
        root.addView(button("设为当前音色", v -> {
            Object s = voiceSpinner.getSelectedItem();
            if (s != null) {
                Prefs.get(this).voice(s.toString());
                EngineController.get(this).invalidate();
                toast("音色=" + s + "，下次合成自动重启常驻引擎");
            }
        }));

        section(root, "② 引擎运行方式");
        backendSpinner = spinner(root, "后端", new String[]{
                Prefs.BACKEND_AUTO, Prefs.BACKEND_INPROCESS, Prefs.BACKEND_HOST2});
        rigPath = edit(root, "host2 rig 路径（兼容后端）", Prefs.get(this).host2Rig());
        root.addView(button("保存后端设置", v -> {
            Object s = backendSpinner.getSelectedItem();
            Prefs p = Prefs.get(this);
            if (s != null) p.backend(s.toString());
            p.host2Rig(rigPath.getText().toString().trim());
            EngineController.get(this).invalidate();
            toast("已保存");
        }));

        section(root, "③ 韵律（引擎整数档，缺省 speed=10 pitch=10 volume=100）");
        speedEdit = edit(root, "tts_speed (1..20)", String.valueOf(Prefs.get(this).speed()));
        pitchEdit = edit(root, "tts_pitch (1..20)", String.valueOf(Prefs.get(this).pitch()));
        volumeEdit = edit(root, "tts_volume (0..100)", String.valueOf(Prefs.get(this).volume()));
        root.addView(button("保存韵律", v -> {
            try {
                Prefs.get(this).speed(Integer.parseInt(speedEdit.getText().toString().trim()))
                        .pitch(Integer.parseInt(pitchEdit.getText().toString().trim()))
                        .volume(Integer.parseInt(volumeEdit.getText().toString().trim()));
                toast("已保存");
            } catch (NumberFormatException e) { toast("要填整数"); }
        }));

        section(root, "④ 三份发音规则（用户 > 上游产物 > vendor）");
        mergeReport = label(root, "合并报告：待计算", 14, false);
        row(root, button("立即检查上游更新", v -> {
                    RuleUpdateService.checkNowAsync(this, m -> {
                        toast(m);
                        EngineController.get(this).invalidate();
                        refreshStatus();
                    });
                }),
                button("重新合并三份", v -> applyRules()));
        autoUpdate = new CheckBox(this);
        autoUpdate.setText("自动定时检查更新");
        autoUpdate.setChecked(Prefs.get(this).autoUpdate());
        root.addView(autoUpdate);
        intervalEdit = edit(root, "检查间隔（小时，>=1）", String.valueOf(Prefs.get(this).intervalHours()));
        ruleUrl = edit(root, "上游 unified_rule.txt URL（留空用默认）", Prefs.get(this).ruleUrl());
        root.addView(button("保存更新设置", v -> {
            Prefs p = Prefs.get(this);
            int h = 6;
            try { h = Integer.parseInt(intervalEdit.getText().toString().trim()); } catch (Exception ignored) {}
            p.autoUpdate(autoUpdate.isChecked()).intervalHours(h)
                    .ruleUrl(ruleUrl.getText().toString().trim());
            RuleUpdateService.schedule(this);
            toast("已保存并重新排程");
        }));

        section(root, "④.1 快速生成一行（对齐上游工具页表单）");
        qWord = edit(root, "字词（如 行 / 姐姐）", "");
        qPron = edit(root, "读音（如 hang2 / *3 *1 / none5）", "");
        qType = spinner(root, "规则类型", new String[]{"TEXT", "WORD_INSIDE", "PINYIN", "POS"});
        qValue = edit(root, "匹配值（留空自动用字词全值）", "");
        qComment = edit(root, "注释（可空）", "");
        row(root, button("生成并追加到用户规则", v -> appendQuickRule()),
                button("回滚上游到上一版", v -> {
                    try {
                        toast(RuleManager.rollbackUpstream(this) ? "已回滚" : "没有上一版");
                        applyRules();
                    } catch (Exception e) { toast("回滚失败：" + e.getMessage()); }
                }));

        section(root, "④.2 用户规则文件（直接编辑 / 导入导出）");
        userRules = new EditText(this);
        userRules.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        userRules.setMinLines(8);
        userRules.setTextSize(13f);
        root.addView(userRules, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row(root, button("保存并应用", v -> {
                    try {
                        RuleManager.setUserText(this, userRules.getText().toString());
                        applyRules();
                    } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
                }),
                button("导入文件", v -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE), REQ_IMPORT)),
                button("导出文件", v -> startActivityForResult(
                        new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                                .putExtra(Intent.EXTRA_TITLE, "user_rules.txt"), REQ_EXPORT)));
        row(root, button("查看合并结果", v -> showText("合并后的 unified_rule.txt", safe(() -> RuleManager.mergedText(this)))),
                button("查看冲突报告", v -> showText("冲突 / 计数", safe(() ->
                        Io.readUtf8(new java.io.File(RuleManager.dir(this), "merge_report.txt"))))),
                button("环境诊断", v -> showText("环境自检", Diagnostics.report(this))));
        diag = label(root, "", 12, false);

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {}
        syncSourceSelection();
        loadUserRules();
        refreshStatus();
        applyRules();
    }

    @Override protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ------------------------------------------------------------ UI helpers

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private RadioButton radio(String text, String tag) {
        RadioButton r = new RadioButton(this);
        r.setText(text); r.setTag(tag);
        return r;
    }
    private TextView label(LinearLayout root, String text, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(size);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        root.addView(t);
        return t;
    }
    private void section(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(16f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        root.addView(t);
    }
    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(12f); b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
    private void row(LinearLayout root, Button... bs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : bs) r.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private EditText edit(LinearLayout root, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value == null ? "" : value);
        e.setTextSize(13f); e.setSingleLine(true);
        root.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }
    private Spinner spinner(LinearLayout root, String hint, String[] items) {
        Spinner s = new Spinner(this);
        List<String> list = new ArrayList<>(Arrays.asList(items));
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list));
        root.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private String safe(CallableText t) {
        try { return t.call(); } catch (Exception e) { return "读取失败：" + e.getMessage(); }
    }
    private interface CallableText { String call() throws Exception; }
    private void showText(String title, String body) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(body); tv.setTextSize(12f);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setPositiveButton("关闭", null).show();
    }

    // ------------------------------------------------------------ 行为

    private void syncSourceSelection() {
        String s = Prefs.get(this).source();
        for (int i = 0; i < sourceGroup.getChildCount(); i++) {
            View v = sourceGroup.getChildAt(i);
            if (v instanceof RadioButton && s.equals(v.getTag())) {
                ((RadioButton) v).setChecked(true);
                break;
            }
        }
        String backend = Prefs.get(this).backend();
        for (int i = 0; i < backendSpinner.getCount(); i++) {
            if (backend.equals(backendSpinner.getItemAtPosition(i))) { backendSpinner.setSelection(i); break; }
        }
    }

    private void saveSource() {
        int id = sourceGroup.getCheckedRadioButtonId();
        View v = sourceGroup.findViewById(id);
        if (v != null && v.getTag() != null) Prefs.get(this).source(v.getTag().toString());
        Prefs.get(this).customPath(customPath.getText().toString().trim())
                .multittsRoot(multittsPath.getText().toString().trim());
    }

    private void loadUserRules() {
        try { userRules.setText(RuleManager.userText(this)); }
        catch (Exception e) { userRules.setText("# 读取失败：" + e.getMessage()); }
    }

    private void applyRules() {
        try {
            RuleManager.Report r = RuleManager.apply(this);
            if (r.changed) {
                RuleManager.generationAfterChange(this, true);
                EngineController.get(this).invalidate();
            }
            mergeReport.setText("合并报告：" + r.summary() + "\n写入：" + r.enginePath);
            refreshStatus();
        } catch (Exception e) {
            mergeReport.setText("合并失败：" + e.getMessage());
            toast("合并失败：" + e.getMessage());
        }
    }

    private void refreshStatus() {
        String s = "引擎：" + EngineController.get(this).status()
                + "\n后端：" + EngineController.get(this).lastBackend()
                + "\n来源：" + Prefs.get(this).source()
                + "\n音色：" + Prefs.get(this).voice()
                + "\nShizuku：" + ShizukuClient.describe()
                + "\n规则代数：" + Prefs.get(this).generation()
                + "\n上次检查：" + (Prefs.get(this).lastCheck() == 0 ? "从未" :
                     new java.util.Date(Prefs.get(this).lastCheck()).toString());
        status.setText(s);
        diag.setText(Diagnostics.report(this));
        try {
            mergeReport.setText("合并报告：" + safe(() ->
                    Io.readUtf8(new java.io.File(RuleManager.dir(this), "merge_report.txt")).split("\n")[0]));
        } catch (Exception ignored) {}
    }

    private void smoke() {
        status.setText("冒烟中…");
        new Thread(() -> {
            String msg = EngineController.get(SettingsActivity.this).smoke();
            main.post(() -> { toast(msg); refreshStatus(); });
        }).start();
    }

    /** 冒烟 + 播放（AudioTrack），用于 MuMu 上听一下有没有声。 */
    private void smokeAndPlay() {
        new Thread(() -> {
            try {
                byte[] pcm = EngineController.get(this).synthesize("你好，很高兴认识你。", 10, 10, 100);
                play(pcm);
                main.post(() -> toast("播放完成 " + pcm.length + " B"));
            } catch (Exception e) { main.post(() -> toast("失败：" + e.getMessage())); }
        }).start();
    }
    private void play(byte[] pcm) {
        int min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min, 8192)).build();
        t.play();
        t.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
        t.stop(); t.release();
    }

    private void openSystemTtsSettings() {
        try { startActivity(new Intent("android.settings.TTS_SETTINGS")); }
        catch (Exception e) { toast("打不开系统 TTS 设置：" + e.getMessage()); }
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    private void asyncVoices() {
        new Thread(() -> {
            String[] v;
            String err = "";
            try {
                saveSource();
                v = ResourceRoot.resolve(this, Prefs.get(this).voice()).listVoices();
            } catch (Exception e) { v = new String[0]; err = e.getMessage(); }
            final String[] vv = v; final String ee = err;
            main.post(() -> {
                ArrayAdapter<String> a = new ArrayAdapter<>(SettingsActivity.this,
                        android.R.layout.simple_spinner_item,
                        vv.length == 0 ? new String[]{Prefs.get(this).voice()} : vv);
                voiceSpinner.setAdapter(a);
                toast(vv.length == 0 ? "没枚举到音色：" + ee : "找到 " + vv.length + " 个音色");
            });
        }).start();
    }

    private void asyncProbe() {
        new Thread(() -> {
            String msg;
            try {
                com.bd.tts.source.VoiceSource s = ResourceRoot.resolve(this, Prefs.get(this).voice());
                String st = s.fingerprint("zh-cn/ptl.dat");
                msg = st.startsWith("-1:") ? "读不到 zh-cn/ptl.dat（检查路径 / Shizuku 权限）"
                        : "读到 zh-cn/ptl.dat：" + st;
                s.close();
            } catch (Exception e) { msg = "失败：" + e.getMessage(); }
            final String m = msg;
            main.post(() -> toast(m));
        }).start();
    }

    private void appendQuickRule() {
        String word = qWord.getText().toString().trim();
        String pron = qPron.getText().toString().trim();
        String type = qType.getSelectedItem() == null ? "TEXT" : qType.getSelectedItem().toString();
        String val = qValue.getText().toString().trim();
        String cm = qComment.getText().toString().trim();
        if (word.isEmpty() || pron.isEmpty()) { toast("字词和读音必填"); return; }
        String cond;
        if (type.equals("WORD_INSIDE") || type.equals("POS") || type.equals("PINYIN")) {
            cond = type + "(" + (val.isEmpty() ? word : val) + ")";
        } else {
            // 上游工具页：匹配值留空或 () 时，自动用字词的完整值圈定目标。
            cond = "TEXT(" + (val.isEmpty() ? "(" + word + ")" : val) + ")";
        }
        String line = word + "\t" + pron + "\t" + cond + (cm.isEmpty() ? "" : "\t# " + cm);
        userRules.setText(userRules.getText().toString().replaceAll("\\s*$", "") + "\n" + line + "\n");
        toast("已追加：" + line);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_TREE) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                customPath.setText(uri.toString());
                Prefs.get(this).customPath(uri.toString()).source(Prefs.SOURCE_DIRECTORY);
                syncSourceSelection();
                EngineController.get(this).invalidate();
                toast("已选择目录树：" + uri);
            } else if (req == REQ_IMPORT) {
                byte[] d = Io.readAll(getContentResolver().openInputStream(uri));
                userRules.setText(new String(d, java.nio.charset.StandardCharsets.UTF_8));
                toast("已导入，点「保存并应用」生效");
            } else if (req == REQ_EXPORT) {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(userRules.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.close();
                    toast("已导出");
                }
            }
        } catch (Exception e) {
            toast("操作失败：" + e.getMessage());
        }
    }
}
