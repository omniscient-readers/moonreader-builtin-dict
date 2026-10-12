# -*- coding: utf-8 -*-
"""Download the jar toolchain into tools/. Standard library only.

    python scripts/fetch-tools.py
    python scripts/fetch-tools.py --only apktool r8
    python scripts/fetch-tools.py --ecj-version 3.42.0 --force

Files already present are skipped unless --force, so an interrupted run can be
resumed. If a host is blocked, pass --mirror for the maven artifacts, or fetch
that file in a browser and drop it into tools/ under the name printed here.
"""

from __future__ import print_function

import argparse
import hashlib
import io
import os
import shutil
import sys
import urllib.error
import urllib.request

try:
    _stdout_keepalive = sys.stdout
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, 'tools')
UA = 'Mozilla/5.0 (compatible; moonreader-builtin-dict/1.0)'


def maven_central(path):
    return 'https://repo1.maven.org/maven2/' + path


def maven_google(path):
    return 'https://maven.google.com/' + path


def build_targets(args, mirror):
    """name -> ordered list of candidate urls."""

    def central(path):
        urls = []
        if mirror:
            urls.append(mirror.rstrip('/') + '/central/' + path)
            urls.append(mirror.rstrip('/') + '/' + path)
        urls.append(maven_central(path))
        return urls

    def google(path):
        urls = []
        if mirror:
            urls.append(mirror.rstrip('/') + '/google/' + path)
        urls.append(maven_google(path))
        urls.append('https://maven.aliyun.com/repository/google/' + path)
        return urls

    apktool = 'apktool_%s.jar' % args.apktool_version
    signer = 'uber-apk-signer-%s.jar' % args.signer_version
    ecj = 'ecj-%s.jar' % args.ecj_version
    r8 = 'r8-%s.jar' % args.r8_version

    return {
        'apktool.jar': [
            'https://github.com/iBotPeaches/Apktool/releases/download/v%s/%s'
            % (args.apktool_version, apktool),
            'https://ghproxy.net/https://github.com/iBotPeaches/Apktool/releases/download/v%s/%s'
            % (args.apktool_version, apktool),
        ],
        'ecj.jar': central('org/eclipse/jdt/ecj/%s/%s' % (args.ecj_version, ecj)),
        'r8.jar': google('com/android/tools/r8/%s/%s' % (args.r8_version, r8)),
        'uber-apk-signer.jar': [
            'https://github.com/patrickfav/uber-apk-signer/releases/download/v%s/%s'
            % (args.signer_version, signer),
            'https://ghproxy.net/https://github.com/patrickfav/uber-apk-signer/releases/download/v%s/%s'
            % (args.signer_version, signer),
        ],
        'android.jar': [
            'https://raw.githubusercontent.com/Sable/android-platforms/master/%s/android.jar'
            % args.android_platform,
            'https://ghproxy.net/https://raw.githubusercontent.com/Sable/android-platforms/master/%s/android.jar'
            % args.android_platform,
        ],
    }


def fetch(url, dest):
    request = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(request, timeout=300) as response:
        if response.status != 200:
            raise IOError('HTTP %s' % response.status)
        with open(dest, 'wb') as out:
            shutil.copyfileobj(response, out, length=1 << 20)
    return os.path.getsize(dest)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--apktool-version', default='2.11.1')
    ap.add_argument('--ecj-version', default='3.42.0')
    ap.add_argument('--r8-version', default='8.11.18')
    ap.add_argument('--signer-version', default='1.3.0')
    ap.add_argument('--android-platform', default='android-34')
    ap.add_argument('--mirror', default='', help='maven mirror root, e.g. https://maven.aliyun.com/repository')
    ap.add_argument('--only', nargs='*', default=[], help='fetch only these files (e.g. apktool.jar r8.jar)')
    ap.add_argument('--force', action='store_true', help='re-download even if present')
    args = ap.parse_args()

    if not os.path.isdir(TOOLS):
        os.makedirs(TOOLS)

    targets = build_targets(args, args.mirror)
    names = [n for n in targets if not args.only or n in args.only or n.split('.')[0] in args.only]

    failed = []
    for name in names:
        dest = os.path.join(TOOLS, name)
        if os.path.isfile(dest) and not args.force and os.path.getsize(dest) > 1024:
            print('skip  %-22s already present (%.1f MB)'
                  % (name, os.path.getsize(dest) / 1048576.0))
            continue
        ok = False
        for url in targets[name]:
            print('get   %-22s <- %s' % (name, url))
            try:
                size = fetch(url, dest)
                if size < 4096:
                    raise IOError('suspiciously small (%d bytes)' % size)
                print('ok    %-22s %.1f MB  sha256=%s'
                      % (name, size / 1048576.0, sha256(dest)[:16]))
                ok = True
                break
            except Exception as exc:                       # noqa: BLE001
                print('      failed: %s' % exc)
                if os.path.isfile(dest):
                    os.remove(dest)
        if not ok:
            failed.append(name)

    print('')
    missing = [n for n in targets if not os.path.isfile(os.path.join(TOOLS, n))]
    if failed or missing:
        print('could not fetch: %s' % ', '.join(sorted(set(failed) | set(missing))))
        print('fetch these by hand into %s :' % TOOLS)
        for name in sorted(set(failed) | set(missing)):
            print('  %s' % name)
            for url in targets[name]:
                print('      %s' % url)
        return 1
    print('toolchain complete in %s' % TOOLS)
    print('next: scripts/gen-keystore.ps1   then   build.ps1 -BaseApk <stock apk>')
    return 0


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as fh:
        for block in iter(lambda: fh.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


if __name__ == '__main__':
    sys.exit(main())
