package com.example.aibalance;

import org.json.JSONArray;
import org.json.JSONObject;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 通过各平台开放接口查询账户余额。
 * 全部为 HTTPS + Bearer Token 鉴权，使用系统自带 HttpURLConnection，无第三方依赖。
 */
public class BalanceFetcher {

    public static class Result {
        public final boolean ok;
        public final CharSequence text;

        Result(boolean ok, CharSequence text) {
            this.ok = ok;
            this.text = text;
        }
    }

    public static Result fetch(Provider provider, String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return new Result(false, "未设置 API Key，请写入 aibalance_keys.json 后重开 App");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(provider.endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);

            if (code < 200 || code >= 300) {
                String err = extractError(body);
                return new Result(false, "HTTP " + code + (err.isEmpty() ? "" : "：" + err));
            }
            return parse(provider, body);
        } catch (Exception e) {
            String msg = e.getMessage();
            return new Result(false, "网络错误" + (msg == null ? "" : "：" + msg));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Result parse(Provider p, String body) {
        try {
            JSONObject root = new JSONObject(body);
            switch (p.id) {
                case "moonshot": {
                    // {"code":0,"data":{"available_balance":x,"voucher_balance":y,"cash_balance":z}}
                    JSONObject data = root.optJSONObject("data");
                    if (data == null || !data.has("available_balance")) {
                        return new Result(false, "查询失败：" + extractError(body));
                    }
                    double avail = data.optDouble("available_balance");
                    double cash = data.optDouble("cash_balance");
                    double voucher = data.optDouble("voucher_balance");
                    String text = String.format(Locale.CHINA, "¥ %.2f", avail)
                            + String.format(Locale.CHINA, "\n现金 ¥%.2f · 代金券 ¥%.2f", cash, voucher);
                    return new Result(true, text);
                }
                case "deepseek": {
                    // {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"9.71",...}]}
                    JSONArray infos = root.optJSONArray("balance_infos");
                    if (infos == null || infos.length() == 0) {
                        return new Result(false, "查询失败：" + extractError(body));
                    }
                    JSONObject info = infos.getJSONObject(0);
                    String currency = info.optString("currency", "CNY");
                    String total = info.optString("total_balance", "?");
                    String granted = info.optString("granted_balance", "0");
                    String toppedUp = info.optString("topped_up_balance", "0");
                    String symbol = "CNY".equalsIgnoreCase(currency) ? "¥" : currency + " ";
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    sb.append(symbol + total);
                    sb.append("\n");
                    int start = sb.length();
                    sb.append("充值 " + symbol + toppedUp + " · 赠送 " + symbol + granted);
                    sb.setSpan(new RelativeSizeSpan(0.7f), start, sb.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Color.parseColor("#60A5FA")), start, sb.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    return new Result(true, sb);
                }
                case "siliconflow": {
                    // {"code":20000,"message":"OK","data":{"balance":"x","chargeBalance":"y","totalBalance":"z"}}
                    JSONObject data = root.optJSONObject("data");
                    if (data == null) {
                        return new Result(false, "查询失败：" + extractError(body));
                    }
                    String total = data.has("totalBalance") ? data.optString("totalBalance")
                            : data.optString("balance", "?");
                    String balance = data.optString("balance", "0");
                    String charge = data.optString("chargeBalance", "0");
                    String text = "¥ " + total
                            + "\n充值 ¥" + charge + " · 赠送 ¥" + balance;
                    return new Result(true, text);
                }
                default:
                    return new Result(false, "未知的服务商类型");
            }
        } catch (Exception e) {
            return new Result(false, "返回数据解析失败");
        }
    }

    /** 尽量从错误响应中提取 message 字段 */
    private static String extractError(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) return error.optString("message", "");
            String msg = root.optString("message", "");
            if (!msg.isEmpty() && !"OK".equalsIgnoreCase(msg)) return msg;
        } catch (Exception ignored) {
        }
        return body.length() > 120 ? body.substring(0, 120) : body;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
}
