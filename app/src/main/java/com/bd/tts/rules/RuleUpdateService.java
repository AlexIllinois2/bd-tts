package com.bd.tts.rules;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.bd.tts.BuildConfig;
import com.bd.tts.util.Io;
import com.bd.tts.util.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 定时检查 midu_unified_rule 的产物（ETag 增量），落盘后立即重算三层合并。 */
public class RuleUpdateService extends JobService {

    private static final String TAG = "BdRuleUpdate";
    public static final int JOB_ID = 4201;

    public interface Callback { void done(String message); }

    public static void schedule(Context c) {
        // 定时更新是可选功能，整段必须兜住：JobScheduler 在缺 ACCESS_NETWORK_STATE
        // 或某些 ROM 上会对网络约束 / persisted 任务抛 SecurityException、
        // IllegalArgumentException。漏出去会带崩 BdTtsService.onCreate()，
        // 让系统 TTS 引擎整个不可用（真机实测：绑定超时 + onInit(-1)）。
        try {
            Prefs p = Prefs.get(c);
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!p.autoUpdate()) { js.cancel(JOB_ID); return; }
            long period = TimeUnit.HOURS.toMillis(Math.max(1, p.intervalHours()));
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, RuleUpdateService.class))
                    .setPeriodic(period)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30), JobInfo.BACKOFF_POLICY_LINEAR)
                    .build();
            js.schedule(info);
        } catch (Throwable t) {
            Log.w(TAG, "定时更新任务调度失败，已忽略", t);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try { checkNow(ctx); }
            catch (Throwable t) { Log.w(TAG, "定时更新失败，保留上一版", t); }
            finally { jobFinished(params, false); }
        }, "midu-rule-update").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    public static void checkNowAsync(Context c, Callback cb) {
        new Thread(() -> {
            String msg;
            try { msg = checkNow(c.getApplicationContext()); }
            catch (Throwable t) { msg = "更新失败：" + t.getMessage(); }
            final String m = msg;
            new Handler(Looper.getMainLooper()).post(() -> cb.done(m));
        }, "midu-rule-check").start();
    }

    private static String defaultUrl() {
        return BuildConfig.UPSTREAM_RULE_URL;
    }

    private static final class Fetched {
        final String text;      // null = 304 未变
        final String etag;
        Fetched(String t, String e) { text = t; etag = e; }
    }

    private static Fetched fetch(String urlStr, String oldEtag) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "bd-tts/1.0");
            if (oldEtag != null && !oldEtag.isEmpty()) conn.setRequestProperty("If-None-Match", oldEtag);
            int code = conn.getResponseCode();
            String etag = conn.getHeaderField("ETag");
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return new Fetched(null, oldEtag);
            if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("HTTP " + code + " @ " + urlStr);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) { Io.copy(in, bos); }
            return new Fetched(new String(bos.toByteArray(), StandardCharsets.UTF_8), etag);
        } finally { conn.disconnect(); }
    }

    /** 同步执行；失败时绝不覆盖上一版。返回给 UI 的一段中文结论。 */
    public static synchronized String checkNow(Context c) throws Exception {
        Prefs p = Prefs.get(c);
        String ruleUrl = p.ruleUrl().isEmpty() ? defaultUrl() : p.ruleUrl();

        Fetched rule = fetch(ruleUrl, p.etag("rule"));

        p.lastCheck(System.currentTimeMillis());
        if (rule.text == null) return "已检查：上游无变化（ETag 304）";

        if (!Rules.looksValid(rule.text, 20))
            throw new IllegalStateException("上游 unified_rule.txt 未过合法性闸（规则太少或格式异常），已保留上一版");
        p.etag("rule", rule.etag);

        RuleManager.installUpstream(c, rule.text);
        RuleManager.Report rep = RuleManager.apply(c);
        if (rep.changed) {
            RuleManager.generationAfterChange(c, true);
            p.lastUpdate(System.currentTimeMillis());
            c.sendBroadcast(new Intent(RuleManager.ACTION_RULES_UPDATED)
                    .setPackage(c.getPackageName()));
        }
        return "已更新上游规则：" + rep.summary();
    }
}
