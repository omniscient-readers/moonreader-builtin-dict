package com.flyersoft.moonreader.builtindict;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Standalone dictionary screen (also the launcher icon "内置词典"). The same
 * engine powers the in-reader popup; English and Chinese are both supported.
 */
public class DictActivity extends Activity implements TextWatcher {

    private EditText input;
    private TextView title;
    private TextView body;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            DictEngine.load(this);
        } catch (Throwable ignore) { }

        float den = getResources().getDisplayMetrics().density;
        int pad = Math.round(16f * den);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入中英文单词或成语");
        input.setTextSize(20f);

        title = new TextView(this);
        title.setTextSize(24f);

        body = new TextView(this);
        body.setTextSize(16f);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(body);

        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        input.addTextChangedListener(this);
        input.requestFocus();

        String word = DictEngine.cleanWord(wordOf(getIntent()));
        if (word.length() > 0) {
            input.setText(word);
        } else {
            show(null);
        }
    }

    private void show(String raw) {
        if (updating) {
            return;
        }
        updating = true;
        try {
            if (raw == null || raw.length() == 0) {
                title.setText("内置词典");
                int n = DictEngine.entryCount();
                String err = DictEngine.getLastError();
                StringBuilder sb = new StringBuilder();
                sb.append("支持中文（汉字、词语、成语）和英汉查询。当前词库条目数：").append(n);
                if (err != null) {
                    sb.append("\n\n[警告] 部分词库加载失败：").append(err);
                }
                sb.append("\n\n阅读时：选中文字点“词典”按钮，释义会直接弹在书页上。\n");
                sb.append("扩展词库：把自定义词条文件放到\n");
                sb.append("/sdcard/Android/data/com.flyersoft.moonreader/files/mrdict_user.txt\n");
                sb.append("（每行：词条+制表符+释义），重新打开本页生效。");
                body.setText(sb.toString());
            } else {
                title.setText(raw);
                String d = DictEngine.lookup(raw);
                if (d != null) {
                    body.setText(d);
                } else {
                    List<String> sug = DictEngine.suggestions(raw, 14);
                    if (!sug.isEmpty()) {
                        StringBuilder sb = new StringBuilder("未收录该词。\n\n你是不是要找：");
                        for (int i = 0; i < sug.size(); i++) {
                            sb.append("\n· ").append(sug.get(i));
                        }
                        body.setText(sb.toString());
                    } else {
                        body.setText("未收录该词。");
                    }
                }
            }
        } catch (Throwable t) {
            try {
                body.setText("查询出错：" + t);
            } catch (Throwable ignore) { }
        } finally {
            updating = false;
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (updating) {
            return;
        }
        try {
            show(input.getText().toString());
        } catch (Throwable ignore) { }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    private static String wordOf(Intent intent) {
        if (intent == null) {
            return null;
        }
        String[] keys = {
                "query", "QUERY", "EXTRA_QUERY", "word", "q", "text",
                "android.intent.extra.PROCESS_TEXT", "android.intent.extra.TEXT"
        };
        for (int i = 0; i < keys.length; i++) {
            String v = intent.getStringExtra(keys[i]);
            if (v != null && DictEngine.cleanWord(v).length() > 0) {
                return v;
            }
        }
        String w = intent.getDataString();
        if (w != null && DictEngine.cleanWord(w).length() > 0) {
            return w;
        }
        return null;
    }
}
