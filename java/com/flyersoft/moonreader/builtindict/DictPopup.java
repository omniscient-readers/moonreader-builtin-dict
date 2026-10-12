package com.flyersoft.moonreader.builtindict;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.List;

/**
 * In-reader dictionary card hooked into Moon+ Reader's dictionary launch
 * points. Two tabs inside one card:
 *   - 百科: embedded WebView on Baidu Baike (encyclopedic coverage of proper
 *     nouns, plants, places, characters...); links stay inside the card.
 *   - 词典: instant offline lookup from the local dictionary.
 * Default tab: 词典 when the local dictionary hits, otherwise 百科.
 */
public class DictPopup {

    private static final int BLUE = 0xFF136BCE;
    private static final int GRAY = 0xFF666666;

    private static Dlg current;
    private static String currentWord;

    /** Dialog subclass carrying view references (Dialog has no tag). */
    private static class Dlg extends Dialog {
        TextView title;
        TextView tabBaike;
        TextView tabDict;
        TextView loading;
        TextView dictBody;
        ScrollView dictScroll;
        DictWebView web;
        FrameLayout content;
        String webWord;
        boolean baikeTab;

        Dlg(Activity a) {
            super(a);
        }
    }

    /** Destroys itself after detach so repeated lookups do not leak. */
    private static class DictWebView extends WebView {
        DictWebView(Context c) {
            super(c);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            try {
                stopLoading();
                destroy();
            } catch (Throwable ignore) { }
        }
    }

    private DictPopup() {
    }

    /**
     * Called from injected smali at the head of every dictionary launch path.
     * Returns false when the word is empty so the original flow can proceed.
     */
    public static boolean hook(Context ctx, String raw) {
        try {
            final String word = DictEngine.cleanWord(raw);
            if (word.length() == 0) {
                return false;
            }
            if (!(ctx instanceof Activity)) {
                return false;
            }
            Activity act = (Activity) ctx;
            currentWord = word;
            showFor(act, word);
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    private static void dismissCurrent() {
        try {
            if (current != null && current.isShowing()) {
                current.dismiss();
            }
        } catch (Throwable ignore) { }
        current = null;
    }

    private static void showFor(final Activity act, final String word) {
        dismissCurrent();
        final Dlg d = buildDialog(act);
        current = d;
        d.show();
        fillDict(d, word);
        boolean localHit = DictEngine.isLoaded() && DictEngine.lookup(word) != null;
        setTab(d, !localHit); // miss -> Baike, hit -> dictionary
        if (!DictEngine.isLoaded()) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        DictEngine.load(act);
                    } catch (Throwable ignore) { }
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                if (current == d && d.isShowing() && !d.baikeTab) {
                                    fillDict(d, word);
                                }
                            } catch (Throwable ignore) { }
                        }
                    });
                }
            }).start();
        }
    }

    private static void fillDict(Dlg d, String word) {
        if (d.dictBody == null) {
            return;
        }
        d.title.setText(word);
        String def = DictEngine.lookup(word);
        StringBuilder sb = new StringBuilder();
        if (def != null) {
            sb.append(def);
            if (sb.length() > 900) {
                sb.setLength(900);
                sb.append("……");
            }
        } else {
            sb.append("本地词典未收录，请查看“百科”标签。");
            List<String> sug = DictEngine.suggestions(word, 10);
            if (!sug.isEmpty()) {
                sb.append("\n\n你是不是要找：");
                for (int i = 0; i < sug.size(); i++) {
                    sb.append("\n· ").append(sug.get(i));
                }
            }
            String err = DictEngine.getLastError();
            if (err != null) {
                sb.append("\n\n[").append(err).append(']');
            }
        }
        d.dictBody.setText(sb.toString());
    }

    private static void setTab(final Dlg d, boolean baike) {
        d.baikeTab = baike;
        styleTab(d.tabBaike, baike);
        styleTab(d.tabDict, !baike);
        if (baike) {
            d.web.setVisibility(View.VISIBLE);
            d.dictScroll.setVisibility(View.GONE);
            if (d.webWord == null || !d.webWord.equals(currentWord)) {
                d.webWord = currentWord;
                d.loading.setVisibility(View.VISIBLE);
                d.loading.setText("百度百科加载中…");
                d.web.loadUrl(baikeUrl(currentWord));
            } else {
                d.loading.setVisibility(View.GONE);
            }
        } else {
            d.web.setVisibility(View.GONE);
            d.loading.setVisibility(View.GONE);
            d.dictScroll.setVisibility(View.VISIBLE);
        }
        applySize(d, baike);
    }

    /** 词典 tab shrinks the card to its content; 百科 tab keeps full height. */
    private static void applySize(final Dlg d, final boolean baike) {
        final Window w = d.getWindow();
        if (w == null) {
            return;
        }
        float den = d.getContext().getResources().getDisplayMetrics().density;
        int mx = Math.round(14f * den);
        int my = Math.round((baike ? 40f : 20f) * den);
        w.getDecorView().setPadding(mx, my, mx, my);
        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) d.content.getLayoutParams();
        if (baike) {
            clp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            clp.height = 0;
            clp.weight = 1f;
            d.content.setLayoutParams(clp);
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
        } else {
            clp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            clp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            clp.weight = 0f;
            d.content.setLayoutParams(clp);
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            d.content.post(new Runnable() {
                public void run() {
                    try {
                        int availW = d.content.getWidth();
                        if (availW <= 0) {
                            availW = d.getContext().getResources().getDisplayMetrics().widthPixels - mx * 2;
                        }
                        float den2 = d.getContext().getResources().getDisplayMetrics().density;
                        int pad = Math.round(16f * den2);
                        int maxH = (int) (d.getContext().getResources().getDisplayMetrics().heightPixels * 0.45f);
                        int specW = View.MeasureSpec.makeMeasureSpec(Math.max(100, availW - pad * 2),
                                View.MeasureSpec.AT_MOST);
                        d.dictBody.measure(specW, View.MeasureSpec.makeMeasureSpec(0,
                                View.MeasureSpec.UNSPECIFIED));
                        int h = d.dictBody.getMeasuredHeight() + pad * 2;
                        if (h > maxH) {
                            h = maxH;
                        }
                        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) d.dictScroll.getLayoutParams();
                        slp.height = h;
                        d.dictScroll.setLayoutParams(slp);
                    } catch (Throwable ignore) {
                    }
                }
            });
        }
    }

    private static void styleTab(TextView t, boolean selected) {
        t.setTextColor(selected ? BLUE : GRAY);
        t.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static String baikeUrl(String word) {
        try {
            return "https://baike.baidu.com/item/" + URLEncoder.encode(word, "UTF-8");
        } catch (Throwable t) {
            return "https://baike.baidu.com/item/" + word;
        }
    }

    private static Dlg buildDialog(final Activity act) {
        final Dlg d = new Dlg(act);
        float den = act.getResources().getDisplayMetrics().density;
        int pad = Math.round(16f * den);

        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFFFFFFFF);
        card.setCornerRadius(12f * den);
        card.setStroke(1, 0xFFE0E0E0);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundDrawable(card);

        // title row: word + close
        TextView title = new TextView(act);
        title.setTextSize(20f);
        title.setTextColor(0xFF222222);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(17f);
        close.setTextColor(0xFF999999);
        close.setPadding(Math.round(10f * den), 0, 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { d.dismiss(); } catch (Throwable ignore) { }
            }
        });
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // tab row: 百科 / 词典
        TextView tabBaike = new TextView(act);
        tabBaike.setText("百 科");
        tabBaike.setTextSize(16f);
        tabBaike.setGravity(Gravity.CENTER);
        tabBaike.setPadding(0, Math.round(8f * den), 0, Math.round(8f * den));
        tabBaike.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setTab(d, true);
            }
        });
        TextView tabDict = new TextView(act);
        tabDict.setText("词 典");
        tabDict.setTextSize(16f);
        tabDict.setGravity(Gravity.CENTER);
        tabDict.setPadding(0, Math.round(8f * den), 0, Math.round(8f * den));
        tabDict.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setTab(d, false);
            }
        });
        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tabBaike, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabDict, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        View divider = new View(act);
        divider.setBackgroundColor(0xFFE8E8E8);

        // content area
        FrameLayout content = new FrameLayout(act);

        final DictWebView web = new DictWebView(act);
        WebSettings s = web.getSettings();
        try {
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(false);
            String ua = s.getUserAgentString();
            if (ua != null) {
                s.setUserAgentString(ua.replace("; wv", ""));
            }
        } catch (Throwable ignore) { }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // keep every link inside the card
                try {
                    v.loadUrl(url);
                } catch (Throwable ignore) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                d.loading.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                d.loading.setText("百科加载失败（可能无网络）。\n\n可切换“词典”标签使用离线词库。");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                if (newProgress >= 80) {
                    d.loading.setVisibility(View.GONE);
                }
            }
        });

        TextView dictBody = new TextView(act);
        dictBody.setTextSize(16f);
        dictBody.setTextColor(0xFF333333);
        dictBody.setLineSpacing(0f, 1.2f);
        ScrollView dictScroll = new ScrollView(act);
        dictScroll.addView(dictBody);

        TextView loading = new TextView(act);
        loading.setTextSize(15f);
        loading.setTextColor(0xFF888888);
        loading.setGravity(Gravity.CENTER);

        content.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(dictScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(loading, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.setMargins(0, Math.round(10f * den), 0, 0);
        root.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(1f * Math.max(1f, den / 2f))));
        root.addView(content, contentLp);

        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(root);
        d.setCanceledOnTouchOutside(true);

        d.title = title;
        d.tabBaike = tabBaike;
        d.tabDict = tabDict;
        d.dictBody = dictBody;
        d.dictScroll = dictScroll;
        d.loading = loading;
        d.web = web;
        d.content = content;

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new GradientDrawable());
            int mx = Math.round(14f * den);
            int my = Math.round(20f * den);
            w.getDecorView().setPadding(mx, my, mx, my);
            w.setGravity(Gravity.CENTER);
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }
}
