package com.flyersoft.moonreader.builtindict;

import android.content.Context;

import com.flyersoft.tools.A;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Imports user-defined online library sites from an editable text file into
 * Moon+ Reader's catalog folder, so they appear in the online library UI.
 *
 * File: <external files dir>/netcatalog_user.txt
 * One site per line:  标题|目录URL[|搜索URL]
 * Lines starting with # are ignored. Catalog URLs are OPDS or HTML pages.
 */
public class NetSites {

    private NetSites() {
    }

    public static void importUserSites() {
        try {
            Context ctx = A.getContext();
            if (ctx == null) {
                return;
            }
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File src = new File(dir, "netcatalog_user.txt");
            if (!src.isFile()) {
                return;
            }
            String folder = A.xml_files_folder;
            if (folder == null || folder.length() == 0) {
                return;
            }
            new File(folder).mkdirs();

            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(src), "UTF-8"));
            int idx = 0;
            String line;
            try {
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.charAt(0) == '#') {
                        continue;
                    }
                    int p1 = line.indexOf('|');
                    if (p1 <= 0) {
                        continue;
                    }
                    String title = line.substring(0, p1).trim();
                    String rest = line.substring(p1 + 1).trim();
                    String main = rest;
                    String search = null;
                    int p2 = rest.indexOf('|');
                    if (p2 >= 0) {
                        main = rest.substring(0, p2).trim();
                        search = rest.substring(p2 + 1).trim();
                    }
                    if (title.length() == 0 || main.length() < 8) {
                        continue;
                    }
                    boolean webview = main.startsWith("webview:");
                    if (webview) {
                        main = main.substring(8).trim();
                    }
                    if (!main.startsWith("http")) {
                        main = "http://" + main;
                    }
                    String host = hostOf(main);
                    StringBuilder x = new StringBuilder();
                    x.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
                    x.append("    <string name=\"site\">").append(esc(host)).append("</string>\n");
                    x.append("    <string name=\"title\">").append(esc(title)).append("</string>\n");
                    x.append("    <string name=\"summary\">").append(esc(host)).append("</string>\n");
                    x.append("    <string name=\"main\">").append(esc(main)).append("</string>\n");
                    if (search != null && search.length() > 4 && search.indexOf('{') >= 0) {
                        x.append("    <string name=\"search\">").append(esc(search)).append("</string>\n");
                    }
                    x.append("    <int name=\"version\" value=\"1\" />\n</map>\n");
                    writeIfChanged(new File(folder, "network2_user_" + idx + ".xml"), x.toString());
                    idx++;
                }
            } finally {
                try { r.close(); } catch (IOException ignore) { }
            }
            // drop leftover files when the list shrank
            while (new File(folder, "network2_user_" + idx + ".xml").isFile()) {
                new File(folder, "network2_user_" + idx + ".xml").delete();
                idx++;
            }
        } catch (Throwable ignore) {
        }
    }

    private static String hostOf(String url) {
        int a = url.indexOf("://");
        int b = url.indexOf('/', a + 3);
        if (a < 0) {
            return url;
        }
        return b > a ? url.substring(a + 3, b) : url.substring(a + 3);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void writeIfChanged(File f, String content) {
        try {
            if (f.isFile() && content.equals(read(f))) {
                return;
            }
            FileWriter w = new FileWriter(f);
            w.write(content);
            w.close();
        } catch (Throwable ignore) {
        }
    }

    private static String read(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[2048];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }
}
