package com.flyersoft.moonreader.builtindict;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Built-in dictionary engine: English-Chinese + Chinese (chars, words, idioms).
 * Data: TSV lines "word\tdefinition", UTF-8, loaded from assets once and kept
 * in memory (HashMap + a sorted key array for prefix suggestions).
 */
public class DictEngine {

    private static HashMap<String, String> exact;
    private static String[] keysSorted;
    private static HashMap<String, String> irregular;
    private static volatile boolean loaded;
    private static String lastError;

    private DictEngine() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String getLastError() {
        return lastError;
    }

    public static synchronized void load(Context ctx) {
        if (loaded) {
            return;
        }
        HashMap<String, String> ex = new HashMap<String, String>();
        HashMap<String, String> ir = new HashMap<String, String>();
        putIrregulars(ir);

        String err = null;
        String[] assets = {"mrdict/dict_zh.txt", "mrdict/dict_cn.txt", "mrdict/dict_big.txt"};
        for (int i = 0; i < assets.length; i++) {
            try {
                AssetManager am = ctx.getAssets();
                InputStream in = am.open(assets[i]);
                try {
                    readInto(ex, new BufferedReader(new InputStreamReader(in, "UTF-8")));
                } finally {
                    try { in.close(); } catch (IOException ignore) { }
                }
            } catch (Throwable t) {
                if (err == null) {
                    err = assets[i] + ": " + t;
                }
            }
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, "mrdict_user.txt");
            if (f != null && f.isFile() && f.canRead()) {
                InputStream in = new FileInputStream(f);
                try {
                    readInto(ex, new BufferedReader(new InputStreamReader(in, "UTF-8")));
                } finally {
                    try { in.close(); } catch (IOException ignore) { }
                }
            }
        } catch (Throwable ignore) { }

        String[] keys = ex.keySet().toArray(new String[ex.size()]);
        Arrays.sort(keys);
        exact = ex;
        keysSorted = keys;
        irregular = ir;
        lastError = err;
        loaded = true;
    }

    public static int entryCount() {
        return exact == null ? 0 : exact.size();
    }

    /** Strip punctuation/quotes around a selected word; keeps CJK and word chars. */
    public static String cleanWord(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int a = 0;
        int b = s.length() - 1;
        while (a <= b && !isWordChar(s.charAt(a))) {
            a++;
        }
        while (b >= a && !isWordChar(s.charAt(b))) {
            b--;
        }
        if (a > b) {
            return "";
        }
        return s.substring(a, b + 1);
    }

    private static boolean isWordChar(char c) {
        if (c >= 0x4e00 && c <= 0x9fff) {
            return true; // CJK unified
        }
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '\'' || c == '-';
    }

    private static boolean hasCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                return true;
            }
        }
        return false;
    }

    /** Unified lookup: exact -> English inflections -> Chinese head-shrink. */
    public static String lookup(String raw) {
        if (exact == null || raw == null) {
            return null;
        }
        String w = raw.trim().toLowerCase();
        if (w.length() == 0 || w.length() > 64) {
            return null;
        }
        String d = exact.get(w);
        if (d != null) {
            return d;
        }
        if (irregular != null && !hasCJK(w)) {
            String base = irregular.get(w);
            if (base != null) {
                d = exact.get(base);
                if (d != null) {
                    return d;
                }
            }
            String[] suffixes = {"ies", "ves", "ily", "es", "ed", "ing", "est", "er", "ly", "s", "d"};
            for (int i = 0; i < suffixes.length; i++) {
                String s = suffixes[i];
                if (w.endsWith(s)) {
                    int cut = w.length() - s.length();
                    if (cut < 2) {
                        continue;
                    }
                    String stem = w.substring(0, cut);
                    String[] candidates = {deDouble(stem), stem + "e", stem + "y", stem + "f", stem + "fe"};
                    for (int j = 0; j < candidates.length; j++) {
                        d = exact.get(candidates[j]);
                        if (d != null) {
                            return d;
                        }
                    }
                }
            }
        }
        if (hasCJK(w) && w.length() > 2) {
            // shrink from the tail: 孩子们 -> 孩子, 钢筋混凝土 -> 钢筋
            for (int cut = w.length() - 1; cut >= 2; cut--) {
                d = exact.get(w.substring(0, cut));
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    /** Prefix suggestions; for Chinese falls back to the longest matching head. */
    public static List<String> suggestions(String raw, int limit) {
        List<String> out = new ArrayList<String>();
        if (keysSorted == null || raw == null) {
            return out;
        }
        String w = raw.trim().toLowerCase();
        if (w.length() == 0) {
            return out;
        }
        collect(w, limit, out);
        if (out.isEmpty() && hasCJK(w) && w.length() > 2) {
            for (int cut = w.length() - 1; cut >= 2 && out.isEmpty(); cut--) {
                collect(w.substring(0, cut), limit, out);
            }
        }
        return out;
    }

    private static void collect(String prefix, int limit, List<String> out) {
        int lo = lowerBound(keysSorted, prefix);
        for (int i = lo; i < keysSorted.length && out.size() < limit; i++) {
            if (!keysSorted[i].startsWith(prefix)) {
                break;
            }
            out.add(keysSorted[i]);
        }
    }

    private static int lowerBound(String[] a, String key) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid].compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static String deDouble(String w) {
        int len = w.length();
        if (len >= 3) {
            char last = w.charAt(len - 1);
            char prev = w.charAt(len - 2);
            if (last == prev && "bdgmnprt".indexOf(last) >= 0) {
                return w.substring(0, len - 1);
            }
        }
        return w;
    }

    private static void readInto(HashMap<String, String> ex, BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            int t = line.indexOf('\t');
            if (t <= 0 || t + 1 >= line.length()) {
                continue;
            }
            String w = line.substring(0, t).trim().toLowerCase();
            String d = line.substring(t + 1).trim();
            if (w.length() == 0 || d.length() == 0 || w.length() > 64 || d.length() > 500) {
                continue;
            }
            if (!ex.containsKey(w)) {
                ex.put(w, d);
            }
        }
    }

    private static void put(HashMap<String, String> m, String from, String to) {
        m.put(from, to);
    }

    private static void putIrregulars(HashMap<String, String> m) {
        put(m, "went", "go"); put(m, "gone", "go");
        put(m, "was", "be"); put(m, "were", "be"); put(m, "been", "be"); put(m, "am", "be");
        put(m, "is", "be"); put(m, "are", "be");
        put(m, "did", "do"); put(m, "done", "do"); put(m, "does", "do");
        put(m, "had", "have"); put(m, "has", "have");
        put(m, "said", "say");
        put(m, "made", "make");
        put(m, "took", "take"); put(m, "taken", "take");
        put(m, "came", "come");
        put(m, "saw", "see"); put(m, "seen", "see");
        put(m, "knew", "know"); put(m, "known", "know");
        put(m, "got", "get"); put(m, "gotten", "get");
        put(m, "gave", "give"); put(m, "given", "give");
        put(m, "told", "tell");
        put(m, "found", "find");
        put(m, "felt", "feel");
        put(m, "left", "leave");
        put(m, "kept", "keep");
        put(m, "held", "hold");
        put(m, "brought", "bring");
        put(m, "thought", "think");
        put(m, "ran", "run");
        put(m, "wrote", "write"); put(m, "written", "write");
        put(m, "grew", "grow"); put(m, "grown", "grow");
        put(m, "built", "build");
        put(m, "sent", "send");
        put(m, "spent", "spend");
        put(m, "met", "meet");
        put(m, "sat", "sit");
        put(m, "stood", "stand");
        put(m, "lost", "lose");
        put(m, "paid", "pay");
        put(m, "sold", "sell");
        put(m, "bought", "buy");
        put(m, "caught", "catch");
        put(m, "taught", "teach");
        put(m, "won", "win");
        put(m, "fell", "fall"); put(m, "fallen", "fall");
        put(m, "ate", "eat"); put(m, "eaten", "eat");
        put(m, "slept", "sleep");
        put(m, "flew", "fly"); put(m, "flown", "fly");
        put(m, "drew", "draw"); put(m, "drawn", "draw");
        put(m, "broke", "break"); put(m, "broken", "break");
        put(m, "chose", "choose"); put(m, "chosen", "choose");
        put(m, "spoke", "speak"); put(m, "spoken", "speak");
        put(m, "dug", "dig");
        put(m, "sang", "sing"); put(m, "sung", "sing");
        put(m, "swam", "swim"); put(m, "swum", "swim");
        put(m, "rode", "ride"); put(m, "ridden", "ride");
        put(m, "drove", "drive"); put(m, "driven", "drive");
        put(m, "wore", "wear"); put(m, "worn", "wear");
        put(m, "heard", "hear");
        put(m, "began", "begin"); put(m, "begun", "begin");
        put(m, "became", "become");
        put(m, "drank", "drink"); put(m, "drunk", "drink");
    }
}
