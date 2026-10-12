# -*- coding: utf-8 -*-
"""Patch a decompiled tree so it calls into the built-in dictionary.

Five steps, all idempotent:

1. copy assets/mrdict/*.txt wordlists into the tree, if --dict-src is given
2. register builtindict.DictActivity in AndroidManifest.xml
   (SEARCH + PROCESS_TEXT filters, no launcher icon)
3. add the mrdict_label string
4. set the version in apktool.yml
5. inject the DictPopup.hook prologue into the dictionary launch methods

The prologue bumps .locals by one and inserts

    invoke-static {<ctx>, <word>}, DictPopup;->hook(...)Z
    move-result <newreg>
    if-eqz <newreg>, :mrdict_orig
    return-void

    :mrdict_orig

so the popup takes over when a word is present, and the original body runs
otherwise.
"""

from __future__ import print_function

import argparse
import io
import os
import re
import shutil
import sys

try:
    _stdout_keepalive = sys.stdout  # the wrapper owns the buffer; keep it alive
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
except Exception:
    pass

D = chr(36)  # '$', kept out of string literals so editors cannot mangle it

# tree-local paths, relative to the apktool project root
# (they follow the stock app's layout; adjust if your decompile differs)
SMALI_MAIN = os.path.join('smali_classes3', 'com', 'flyersoft')
DICT_PACKAGE = os.path.join(SMALI_MAIN, 'components', 'DictPackage.smali')
ACTIVITY_TXT = os.path.join(SMALI_MAIN, 'moonreader', 'ActivityTxt.smali')

POPUP = ('Lcom/flyersoft/moonreader/builtindict/'
         'DictPopup;->hook(Landroid/content/Context;Ljava/lang/String;)Z')

# (file, method declaration, (context register, word register))
HOOKS = [
    (ACTIVITY_TXT,
     '.method private lookupWord(Ljava/lang/String;ZZ)V', ('p0', 'p1')),
    (DICT_PACKAGE,
     '.method public static lookupFromSavedDictIntent(Landroid/content/Context;Ljava/lang/String;Z)V',
     ('p0', 'p1')),
    (DICT_PACKAGE,
     '.method public static searchProcessText(Landroid/content/Context;ILjava/lang/String;)V',
     ('p0', 'p2')),
    (DICT_PACKAGE,
     '.method public static searchProcessText(Landroid/content/Context;Lcom/flyersoft/components/DictPackage'
     + D + 'DictItem;Ljava/lang/String;)V',
     ('p0', 'p2')),
]

ACTIVITY_XML = (
    '    <activity android:exported="true" android:label="@string/mrdict_label" '
    'android:name="com.flyersoft.moonreader.builtindict.DictActivity" '
    'android:theme="@android:style/Theme.Material.Light.NoActionBar" '
    'android:windowSoftInputMode="adjustResize|stateVisible">\n'
    '        <intent-filter>\n'
    '            <action android:name="android.intent.action.SEARCH"/>\n'
    '            <category android:name="android.intent.category.DEFAULT"/>\n'
    '        </intent-filter>\n'
    '        <intent-filter>\n'
    '            <action android:name="android.intent.action.PROCESS_TEXT"/>\n'
    '            <category android:name="android.intent.category.DEFAULT"/>\n'
    '            <data android:mimeType="text/plain"/>\n'
    '        </intent-filter>\n'
    '    </activity>\n')

LABEL = '\u5185\u7f6e\u8bcd\u5178'  # built-in dictionary


def read(path):
    with open(path, encoding='utf-8') as fh:
        return fh.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(text)


def hook_method(src, decl, ctx_reg, word_reg):
    """Insert the popup prologue at the head of one method. Returns (src, msg)."""
    at = src.find(decl + '\n')
    if at < 0:
        at = src.find(decl)
        if at < 0:
            return None, 'method not found'
    if 'DictPopup' in src[at:at + 1200]:
        return src, 'already hooked'

    end = src.find('.end method', at)
    body = src[at:end]
    match = re.search(r'(\.locals )(\d+)', body)
    if not match:
        return None, '.locals not found'
    old_n = int(match.group(2))
    new_reg = 'v%d' % old_n
    body = body[:match.start()] + match.group(1) + str(old_n + 1) + body[match.end():]

    lines = body.split('\n')
    insert_at = None
    for i in range(1, len(lines)):
        stripped = lines[i].strip()
        if stripped and not stripped.startswith('.') and not stripped.startswith('#'):
            insert_at = i
            break
    if insert_at is None:
        return None, 'no instruction to insert before'

    prologue = ('    invoke-static {%s, %s}, %s\n'
                '    move-result %s\n'
                '    if-eqz %s, :mrdict_orig\n'
                '    return-void\n'
                '\n'
                '    :mrdict_orig\n') % (ctx_reg, word_reg, POPUP, new_reg, new_reg)
    lines.insert(insert_at, prologue)
    return src[:at] + '\n'.join(lines) + src[end:], 'hooked (.locals %d -> %d)' % (old_n, old_n + 1)


def copy_assets(tree, src_dir):
    dst = os.path.join(tree, 'assets', 'mrdict')
    if not os.path.isdir(dst):
        os.makedirs(dst)
    copied = []
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith('.txt'):
            continue
        shutil.copy(os.path.join(src_dir, name), os.path.join(dst, name))
        copied.append('%s (%d bytes)' % (name, os.path.getsize(os.path.join(dst, name))))
    print('assets copied into assets/mrdict/:')
    for line in copied:
        print('   +', line)
    if not copied:
        print('   (nothing found - is --dict-src right?)')


def patch_manifest(tree):
    path = os.path.join(tree, 'AndroidManifest.xml')
    xml = read(path)
    if 'builtindict.DictActivity' in xml:
        print('manifest: DictActivity already registered')
        return
    anchor = xml.find('<application')
    if anchor < 0:
        raise SystemExit('manifest: no <application> element')
    at = xml.find('>', anchor) + 1
    write(path, xml[:at] + ACTIVITY_XML + xml[at:])
    print('manifest: DictActivity registered (SEARCH + PROCESS_TEXT, no launcher entry)')


def patch_strings(tree):
    path = os.path.join(tree, 'res', 'values', 'strings.xml')
    if not os.path.isfile(path):
        print('strings: res/values/strings.xml not decompiled, skipped')
        return
    xml = read(path)
    if 'mrdict_label' in xml:
        print('strings: mrdict_label already present')
        return
    at = xml.rfind('</resources>')
    line = '    <string name="mrdict_label">%s</string>\n' % LABEL
    write(path, xml[:at] + line + xml[at:])
    print('strings: mrdict_label added')


def patch_version(tree, code, name):
    path = os.path.join(tree, 'apktool.yml')
    if not os.path.isfile(path):
        print('version: apktool.yml not found, skipped')
        return
    yml = read(path)
    yml = re.sub(r'versionCode: \d+', 'versionCode: %d' % code, yml)
    yml = re.sub(r'versionName: \S+', 'versionName: ' + name, yml)
    write(path, yml)
    print('version: %d / %s' % (code, name))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--tree', required=True, help='decompiled apktool project root')
    ap.add_argument('--dict-src', default='', help='dir with dict_*.txt wordlists to embed')
    ap.add_argument('--version-code', type=int, default=909005)
    ap.add_argument('--version-name', default='9.9-mrdict4')
    args = ap.parse_args()

    tree = os.path.abspath(args.tree)
    if not os.path.isdir(tree):
        raise SystemExit('tree not found: ' + tree)

    if args.dict_src:
        copy_assets(tree, os.path.abspath(args.dict_src))
    else:
        print('assets: no --dict-src given, wordlists left untouched')

    patch_manifest(tree)
    patch_strings(tree)
    patch_version(tree, args.version_code, args.version_name)

    failures = 0
    for rel, decl, regs in HOOKS:
        path = os.path.join(tree, rel)
        if not os.path.isfile(path):
            print('hook %-42s FILE MISSING: %s' % (decl.split('(')[0][-40:], rel))
            failures += 1
            continue
        src = read(path)
        patched, msg = hook_method(src, decl, regs[0], regs[1])
        print('hook %-42s %s' % (decl.split('(')[0][-40:], msg))
        if patched is None:
            failures += 1
        elif patched != src:
            write(path, patched)

    if failures:
        raise SystemExit('%d patch step(s) failed - the smali layout of your apk differs; '
                         'adjust the paths/method signatures at the top of this file.' % failures)
    print('ALL PATCHES APPLIED')


if __name__ == '__main__':
    main()
