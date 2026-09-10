package com.example.aibalance;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int COLOR_OK = Color.parseColor("#4ADE80");
    private static final int COLOR_ERR = Color.parseColor("#F87171");
    private static final int COLOR_LOADING = Color.parseColor("#FACC15");
    private static final int COLOR_BG_PEAK = Color.parseColor("#FB9D9D");
    private static final int COLOR_BG_OFFPEAK = Color.parseColor("#2E7D32");
    private static final TimeZone BJ = TimeZone.getTimeZone("Asia/Shanghai");

    private SharedPreferences prefs;
    private ExecutorService executor;
    private Handler handler;
    private Runnable ticker;
    private TextView clock;
    private Boolean lastPeak;
    private final List<Provider> providers = new ArrayList<>();
    private final List<CardHolder> holders = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("api_keys", Context.MODE_PRIVATE);
        executor = Executors.newCachedThreadPool();

        // 仅显示 DeepSeek 余额
        providers.add(new Provider("deepseek", "DeepSeek",
                "https://api.deepseek.com/user/balance"));

        // 启动时自动从文件读取 Key（零输入，无需在手表上打字）
        int loaded = loadKeysFromFile();
        if (loaded > 0) {
            Toast.makeText(this, "已载入", Toast.LENGTH_SHORT).show();
        }

        LinearLayout container = findViewById(R.id.container);
        clock = findViewById(R.id.tvClock);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Provider p : providers) {
            View card = inflater.inflate(R.layout.item_provider, container, false);
            CardHolder h = new CardHolder(p, card);
            holders.add(h);
            bindCard(h);
            container.addView(card);
        }

        refreshAll();
        updatePeakStatus();
        startClockTicker();
    }

    /**
     * 前台时每秒刷新一次时钟：用 SystemClock.elapsedRealtime 对齐到整秒，避免累积漂移。
     */
    private void startClockTicker() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        ticker = new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                updatePeakStatus();
                long delay = 1000 - (SystemClock.elapsedRealtime() % 1000);
                handler.postDelayed(this, delay);
            }
        };
        handler.post(ticker);
    }

    /**
     * 从手表上的 aibalance_keys.json 读取各平台 Key，写入 SharedPreferences。
     * <p>
     * 优先读取 App 私有目录（无需任何权限，推荐）：
     * /sdcard/Android/data/com.example.aibalance/files/aibalance_keys.json
     * 兼容读取（需要读存储权限，失败则跳过）：
     * /sdcard/Download/aibalance_keys.json
     *
     * @return 成功载入的 Key 数量
     */
    private int loadKeysFromFile() {
        List<File> candidates = new ArrayList<>();
        File privateFile = new File(getExternalFilesDir(null), "aibalance_keys.json");
        candidates.add(privateFile);
        try {
            File downloadFile = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "aibalance_keys.json");
            candidates.add(downloadFile);
        } catch (Exception ignored) {
        }

        int loaded = 0;
        for (File f : candidates) {
            if (f == null || !f.exists()) continue;
            String content;
            try {
                content = readFile(f);
            } catch (SecurityException se) {
                continue; // 没有存储权限，跳过 Download 路径
            } catch (Exception e) {
                continue;
            }
            try {
                JSONObject json = new JSONObject(content);
                SharedPreferences.Editor ed = prefs.edit();
                for (Provider p : providers) {
                    if (json.has(p.id)) {
                        String v = json.optString(p.id, "").trim();
                        ed.putString(p.id, v);
                        loaded++;
                    }
                }
                ed.apply();
            } catch (Exception ignored) {
            }
            if (loaded > 0) break; // 私有目录优先，成功即止
        }
        return loaded;
    }

    private static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private void bindCard(final CardHolder h) {
        h.name.setText(h.provider.name);
        if ("deepseek".equals(h.provider.id)) {
            h.icon.setImageResource(R.drawable.ic_deepseek);
            h.icon.setVisibility(View.VISIBLE);
        } else {
            h.icon.setVisibility(View.GONE);
        }
        h.btnRefresh.setOnClickListener(v -> refresh(h));
    }

    private void refreshAll() {
        for (CardHolder h : holders) {
            refresh(h);
        }
    }

    private void refresh(final CardHolder h) {
        final String key = prefs.getString(h.provider.id, "");
        // 查询中：按钮显示“查询中…”并禁用，余额区保持不变
        h.btnRefresh.setEnabled(false);
        h.btnRefresh.setTextSize(TypedValue.COMPLEX_UNIT_PX, h.btnRefresh.getTextSize() - 2f);
        h.btnRefresh.setText("查询中…");
        executor.execute(() -> {
            final BalanceFetcher.Result r = BalanceFetcher.fetch(h.provider, key);
            runOnUiThread(() -> {
                h.balance.setText(r.text);
                h.balance.setTextColor(r.ok ? COLOR_OK : COLOR_ERR);
                h.btnRefresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                h.btnRefresh.setText("刷新");
                h.btnRefresh.setEnabled(true);
                updatePeakStatus();
                Toast.makeText(this, r.ok ? "刷新成功" : "刷新失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * 展示 DeepSeek 当前计费时段与北京时间。
     * <p>
     * 定价规则（https://api-docs.deepseek.com/zh-cn/quick_start/pricing/）：
     * 高峰时段为北京时间周一至周五 09:00-12:00、14:00-18:00，其余为空闲时段。
     * 必须用显式的 Asia/Shanghai 时区计算，不能依赖手表本地时区。
     */
    private void updatePeakStatus() {
        if (clock == null || holders.isEmpty()) return;
        CardHolder h = holders.get(0);

        Calendar cal = new GregorianCalendar(BJ);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        boolean weekday = dow >= Calendar.MONDAY && dow <= Calendar.FRIDAY;
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int minutes = hour * 60 + minute;

        boolean peak = weekday
                && ((minutes >= 9 * 60 && minutes < 12 * 60)
                || (minutes >= 14 * 60 && minutes < 18 * 60));

        clock.setText(String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second));

        if (lastPeak == null || lastPeak != peak) {
            lastPeak = peak;
            h.tag.setText(peak ? "梁文峰" : "梁文谷");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(peak ? COLOR_BG_PEAK : COLOR_BG_OFFPEAK);
            bg.setCornerRadius(getResources().getDisplayMetrics().density * 12f);
            h.tag.setBackground(bg);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && ticker != null) handler.removeCallbacks(ticker);
        if (executor != null) executor.shutdownNow();
    }

    private static class CardHolder {
        final Provider provider;
        final TextView name;
        final TextView balance;
        final TextView tag;
        final Button btnRefresh;
        final ImageView icon;

        CardHolder(Provider provider, View card) {
            this.provider = provider;
            this.name = card.findViewById(R.id.tvName);
            this.balance = card.findViewById(R.id.tvBalance);
            this.tag = card.findViewById(R.id.tvTag);
            this.btnRefresh = card.findViewById(R.id.btnRefresh);
            this.icon = card.findViewById(R.id.ivIcon);
        }
    }
}
