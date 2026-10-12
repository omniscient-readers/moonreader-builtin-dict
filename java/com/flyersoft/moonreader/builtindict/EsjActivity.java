package com.flyersoft.moonreader.builtindict;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ESJ Zone (esjzone) in-app browser for the online library.
 * A real WebView with persistent cookies, so the site's login and comment
 * system work inside the reader. Tapping a .txt/.epub download link downloads
 * it (with the browser cookies attached) and opens it in Moon+ Reader's
 * normal reading interface.
 */
public class EsjActivity extends Activity {

    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private LinearLayout root;
    private boolean downloading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        float den = getResources().getDisplayMetrics().density;
        int pad = Math.round(10f * den);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F2F2);

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF136BCE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = mkBarBtn("‹", den, new View.OnClickListener() {
            public void onClick(View v) {
                goBack();
            }
        });
        final TextView title = new TextView(this);
        title.setText("ESJ Zone 輕小說");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setPadding(pad, 0, pad, 0);
        TextView reload = mkBarBtn("⟳", den, new View.OnClickListener() {
            public void onClick(View v) {
                try { web.reload(); } catch (Throwable ignore) { }
            }
        });
        TextView home = mkBarBtn("⌂", den, new View.OnClickListener() {
            public void onClick(View v) {
                try { web.loadUrl(startUrl()); } catch (Throwable ignore) { }
            }
        });
        TextView close = mkBarBtn("✕", den, new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(home, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        bar.addView(reload, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        bar.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.getProgressDrawable().setColorFilter(0xFF136BCE, android.graphics.PorterDuff.Mode.SRC_IN);

        status = new TextView(this);
        status.setTextSize(14f);
        status.setTextColor(0xFF333333);
        status.setVisibility(View.GONE);
        status.setPadding(pad, pad, pad, pad);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        try {
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            String ua = s.getUserAgentString();
            if (ua != null) {
                s.setUserAgentString(ua.replace("; wv", ""));
            }
        } catch (Throwable ignore) { }
        try {
            CookieSyncManager.createInstance(this);
        } catch (Throwable ignore) { }
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
        } catch (Throwable ignore) { }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null && (url.startsWith("mailto:") || url.startsWith("intent:")
                        || url.startsWith("market:"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Throwable ignore) { }
                    return true;
                }
                try {
                    v.loadUrl(url);
                } catch (Throwable ignore) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String agent, String disposition,
                                        String mimetype, long length) {
                handleDownload(url, disposition, mimetype);
            }
        });

        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(46f * den)));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(3f * Math.max(1f, den))));
        root.addView(web, webLp);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        String url = getIntent() == null ? null : getIntent().getStringExtra("url");
        if (url != null && url.toLowerCase().startsWith("webview:")) {
            url = url.substring(8);
        }
        if (url == null || url.length() == 0) {
            url = startUrl();
        }
        web.loadUrl(url);
    }

    /**
     * Injected at the head of the online-library item click handler. When the
     * tapped catalog is an ESJ Zone site, opens the in-app ESJ browser and
     * returns true (skipping the app's own OPDS/HTML handling).
     */
    public static boolean hookOnLibraryTap(View v, Activity act) {
        try {
            if (v == null || act == null) {
                return false;
            }
            Object tag = v.getTag();
            if (!(tag instanceof Integer)) {
                return false;
            }
            int idx = ((Integer) tag).intValue();
            Class<?> n = Class.forName("com.flyersoft.opds.N");
            java.util.ArrayList libs = (java.util.ArrayList) n.getMethod("getLibraries").invoke(null);
            if (libs == null || idx < 0 || idx >= libs.size()) {
                return false;
            }
            Object site = libs.get(idx);
            java.lang.reflect.Field f = site.getClass().getField("mainUrl");
            String url = (String) f.get(site);
            if (url != null && (url.toLowerCase().contains("esjzone")
                    || url.toLowerCase().startsWith("webview:"))) {
                Intent i = new Intent(act, EsjActivity.class);
                i.putExtra("url", url);
                act.startActivity(i);
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private String startUrl() {
        return "https://www.esjzone.one/";
    }

    private TextView mkBarBtn(String label, float den, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(18f);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Math.round(14f * den), 0, Math.round(14f * den), 0);
        t.setOnClickListener(l);
        return t;
    }

    private void goBack() {
        try {
            if (web.canGoBack()) {
                web.goBack();
            } else {
                finish();
            }
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            CookieSyncManager.getInstance().stopSync();
        } catch (Throwable ignore) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            CookieSyncManager.getInstance().startSync();
        } catch (Throwable ignore) { }
    }

    private static boolean isBookDownload(String url, String disposition, String mimetype) {
        if (disposition != null && disposition.toLowerCase().contains("attachment")) {
            return true;
        }
        if (mimetype != null && (mimetype.contains("octet-stream") || mimetype.contains("epub")
                || mimetype.contains("text/plain"))) {
            String u = url == null ? "" : url.toLowerCase();
            if (u.endsWith(".txt") || u.endsWith(".epub") || u.contains("download")) {
                return true;
            }
        }
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.endsWith(".txt") || u.endsWith(".epub");
    }

    private void handleDownload(final String url, final String disposition, final String mimetype) {
        if (downloading) {
            return;
        }
        if (!isBookDownload(url, disposition, mimetype)) {
            try {
                web.loadUrl(url);
            } catch (Throwable ignore) { }
            return;
        }
        downloading = true;
        String name = guessName(url, disposition);
        final File dir = new File(getExternalFilesDir(null), "esj");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        final File out = new File(dir, name);
        status.setVisibility(View.VISIBLE);
        status.setText("开始下载：" + name);
        Toast.makeText(this, "下载完成后自动进入阅读", Toast.LENGTH_SHORT).show();
        final String cookie = CookieManager.getInstance() == null ? null
                : CookieManager.getInstance().getCookie(url);
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    if (cookie != null && cookie.length() > 0) {
                        c.setRequestProperty("Cookie", cookie);
                    }
                    c.setRequestProperty("User-Agent", web.getSettings().getUserAgentString());
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        c = (HttpURLConnection) new URL(loc).openConnection();
                        c.setConnectTimeout(20000);
                        c.setReadTimeout(60000);
                        if (cookie != null && cookie.length() > 0) {
                            c.setRequestProperty("Cookie", cookie);
                        }
                        code = c.getResponseCode();
                    }
                    if (code == 200) {
                        InputStream in = c.getInputStream();
                        FileOutputStream fo = new FileOutputStream(out);
                        byte[] buf = new byte[16384];
                        long total = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fo.write(buf, 0, n);
                            total += n;
                        }
                        fo.close();
                        in.close();
                        ok = total > 0;
                    } else {
                        err = "HTTP " + code + "（未登录？请先在页面里登录）";
                    }
                } catch (final Throwable t) {
                    err = String.valueOf(t);
                }
                final boolean fok = ok;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        downloading = false;
                        if (fok) {
                            status.setVisibility(View.GONE);
                            openInReader(out.getAbsolutePath());
                        } else {
                            status.setText("下载失败：" + ferr);
                        }
                    }
                });
            }
        }).start();
    }

    private String guessName(String url, String disposition) {
        String name = "book_" + System.currentTimeMillis() + ".txt";
        try {
            if (disposition != null) {
                int i = disposition.toLowerCase().indexOf("filename=");
                if (i >= 0) {
                    name = disposition.substring(i + 9).replace("\"", "").split(";")[0].trim();
                }
            }
            if (name.indexOf('.') < 0 && url != null) {
                String u = url.split("\\?")[0];
                int s = u.lastIndexOf('/');
                if (s >= 0 && u.substring(s + 1).length() > 0) {
                    name = u.substring(s + 1);
                }
            }
            if (!name.toLowerCase().endsWith(".txt") && !name.toLowerCase().endsWith(".epub")) {
                name = name + ".txt";
            }
        } catch (Throwable ignore) {
        }
        return name;
    }

    private void openInReader(String path) {
        try {
            Intent i = new Intent(this, Class.forName("com.flyersoft.moonreader.ActivityTxt"));
            i.setAction(String.valueOf(System.currentTimeMillis()));
            i.putExtra("bookFile", path);
            i.putExtra("fromMain", true);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开阅读器", Toast.LENGTH_SHORT).show();
        }
    }
}
