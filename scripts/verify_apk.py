# -*- coding: utf-8 -*-
"""Check that a built apk really contains the mod, and report its signature.

Checks, in order:

1. the expected assets/mrdict/*.txt wordlists are present and non-empty
2. the mod classes are defined in one of the dex files
3. the compiled AndroidManifest.xml registers builtindict.DictActivity with
   the SEARCH and PROCESS_TEXT filters, and carries the expected version
4. resources.arsc is stored uncompressed (Android 11+ needs that)
5. the signing certificate digest

Usage:
    python verify_apk.py --apk build/out/app.apk
    python verify_apk.py --apk app.apk --expect-version 909005
"""

from __future__ import print_function

import argparse
import hashlib
import io
import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dump_manifest import iter_elements, RES_XML_START_ELEMENT, RES_XML_END_ELEMENT  # noqa: E402

try:
    _stdout_keepalive = sys.stdout  # the wrapper owns the buffer; keep it alive
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
except Exception:
    pass

EXPECTED_ASSETS = ['assets/mrdict/dict_cn.txt', 'assets/mrdict/dict_big.txt']
# classes the mod adds; looked up in the *class definition* list of each dex, so a
# mere reference to the name elsewhere (the injected smali carries the DictPopup
# descriptor) is not mistaken for the class being present.
EXPECTED_CLASSES = [
    'Lcom/flyersoft/moonreader/builtindict/DictEngine;',
    'Lcom/flyersoft/moonreader/builtindict/DictActivity;',
    'Lcom/flyersoft/moonreader/builtindict/DictPopup;',
    'Lcom/flyersoft/moonreader/builtindict/NetSites;',
]


def dex_class_names(blob):
    """Return the set of class descriptors *defined* in a dex file.

    dex header layout (offsets are fixed by the format):

        0   magic[8]              56  string_ids_size
        8   checksum              60  string_ids_off
        12  signature[20]         64  type_ids_size
        32  file_size             68  type_ids_off
        36  header_size           ...
        40  endian_tag            96  class_defs_size
        44  link_size            100  class_defs_off
        48  link_off
        52  map_off

    class_def_item starts with class_idx, an index into the type table, and the
    type table holds indices into the string table - so three small lookups give
    the defined classes without parsing any code.
    """
    if blob[:4] not in (b'dex\n', b'dex\x0a'):
        raise ValueError('not a dex file')

    def u32(offset):
        return struct.unpack_from('<I', blob, offset)[0]

    string_ids_size, string_ids_off = u32(56), u32(60)
    type_ids_size, type_ids_off = u32(64), u32(68)
    class_defs_size, class_defs_off = u32(96), u32(100)

    def string_at(index):
        offset = u32(string_ids_off + 4 * index)
        pos = offset
        while blob[pos] & 0x80:      # uleb128 utf16 length
            pos += 1
        pos += 1
        end = blob.index(b'\x00', pos)
        return blob[pos:end].decode('utf-8', 'replace')

    names = set()
    for i in range(class_defs_size):
        class_idx = u32(class_defs_off + 32 * i)
        if class_idx >= type_ids_size:
            continue
        descriptor = string_at(u32(type_ids_off + 4 * class_idx))
        if descriptor.startswith('L') and descriptor.endswith(';'):
            names.add(descriptor)
    return names


class Report(object):
    def __init__(self):
        self.failed = 0

    def ok(self, msg):
        print('  [ OK ] ' + msg)

    def bad(self, msg):
        self.failed += 1
        print('  [FAIL] ' + msg)

    def info(self, msg):
        print('  [info] ' + msg)


def check_assets(z, rep):
    print('assets:')
    names = set(z.namelist())
    for want in EXPECTED_ASSETS:
        if want not in names:
            rep.bad('%s missing' % want)
        else:
            size = z.getinfo(want).file_size
            if size == 0:
                rep.bad('%s is empty' % want)
            else:
                rep.ok('%-32s %10d bytes' % (want, size))


def check_dex(z, rep):
    print('dex:')
    dexes = sorted(n for n in z.namelist() if re.match(r'classes\d*\.dex$', n))
    rep.info('dex files: %s' % ', '.join(dexes))
    found = {}
    total = {}
    for name in dexes:
        try:
            names = dex_class_names(z.read(name))
        except Exception as exc:                            # noqa: BLE001
            rep.bad('cannot parse %s: %s' % (name, exc))
            continue
        total[name] = len(names)
        for descriptor in EXPECTED_CLASSES:
            if descriptor in names:
                found.setdefault(descriptor, []).append(name)
    rep.info('defined classes per dex: %s'
             % ', '.join('%s=%d' % (n, c) for n, c in sorted(total.items())))

    for descriptor in EXPECTED_CLASSES:
        where = found.get(descriptor)
        short = descriptor[1:-1].split('/')[-1]
        if where:
            rep.ok('%-30s defined in %s' % (short, ', '.join(where)))
        else:
            rep.bad('%-30s NOT DEFINED in any dex' % short)


def check_manifest(z, rep, expect_version):
    print('manifest:')
    try:
        axml = z.read('AndroidManifest.xml')
    except KeyError:
        rep.bad('AndroidManifest.xml missing')
        return

    depth = 0
    activity_depth = None
    saw_activity = False
    filters = []
    current = None
    version_code = None
    version_name = None

    for kind, name, attrs in iter_elements(axml):
        if kind == RES_XML_END_ELEMENT:
            if current is not None and depth - 1 == activity_depth:
                filters.append(current)
                current = None
                activity_depth = None
            depth = max(0, depth - 1)
            continue

        kv = dict(attrs)
        if name == 'manifest':
            version_code = kv.get('versionCode')
            version_name = kv.get('versionName')
        if name == 'activity' and 'builtindict.DictActivity' in kv.get('name', ''):
            saw_activity = True
            activity_depth = depth
            current = []
            rep.ok('DictActivity registered (exported=%s, label=%s)'
                   % (kv.get('exported', '?'), kv.get('label', '?')))
        elif current is not None and name == 'action':
            current.append(kv.get('name', ''))
        depth += 1

    if not saw_activity:
        rep.bad('DictActivity is NOT registered in the manifest')
    else:
        for want in ('android.intent.action.SEARCH', 'android.intent.action.PROCESS_TEXT'):
            if any(want in f for f in filters):
                rep.ok('intent filter: %s' % want)
            else:
                rep.bad('intent filter %s missing' % want)

    if version_code is None:
        rep.bad('cannot read versionCode')
    else:
        rep.info('versionCode=%s versionName=%s' % (version_code, version_name))
        if expect_version is not None and str(expect_version) != str(version_code):
            rep.bad('versionCode %s != expected %s' % (version_code, expect_version))


def check_arsc(z, rep):
    print('resources:')
    if 'resources.arsc' not in z.namelist():
        rep.bad('resources.arsc missing')
        return
    info = z.getinfo('resources.arsc')
    if info.compress_type == zipfile.ZIP_STORED:
        rep.ok('resources.arsc is stored (uncompressed)')
    else:
        rep.bad('resources.arsc is compressed - Android 11+ may refuse to install')


def check_signature(z, rep):
    print('signature:')
    certs = [n for n in z.namelist()
             if n.startswith('META-INF/') and n.upper().endswith(('.RSA', '.DSA', '.EC'))]
    if not certs:
        rep.info('no v1 signature block found (may still be v2/v3-only)')
    else:
        rep.ok('v1 signature: %s' % ', '.join(certs))
        for name in certs:
            digest = hashlib.sha256(z.read(name)).hexdigest()
            rep.info('SHA-256 of %s: %s' % (name, digest))
    rep.info('for v2/v3 use: apksigner verify --print-certs <apk>')


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--apk', required=True)
    ap.add_argument('--expect-version', default=None)
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        raise SystemExit('not found: ' + args.apk)

    rep = Report()
    print('apk: %s (%d bytes)' % (args.apk, os.path.getsize(args.apk)))
    with zipfile.ZipFile(args.apk) as z:
        rep.info('archive entries: %d' % len(z.namelist()))
        check_assets(z, rep)
        check_dex(z, rep)
        check_manifest(z, rep, args.expect_version)
        check_arsc(z, rep)
        check_signature(z, rep)

    print('')
    if rep.failed:
        raise SystemExit('%d check(s) FAILED' % rep.failed)
    print('ALL CHECKS PASSED')


if __name__ == '__main__':
    main()
