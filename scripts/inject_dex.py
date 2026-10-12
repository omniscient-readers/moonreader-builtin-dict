# -*- coding: utf-8 -*-
"""Add the compiled mod dex to a rebuilt apk as classes8.dex.

The mod lives in its own dex instead of being merged into the app's
smali_classes* folders. The archive is rewritten entry by entry so the only
change is the new dex, which keeps resources.arsc stored uncompressed.

Usage:
    python inject_dex.py --apk rebuilt.apk --dex classes.dex --out merged.apk
"""

from __future__ import print_function

import argparse
import os
import sys
import zipfile


def next_dex_name(names, want='classes8.dex'):
    """Pick the first free classesN.dex slot, preferring the requested name."""
    if want not in names:
        return want
    n = 2
    while ('classes%d.dex' % n) in names:
        n += 1
    return 'classes%d.dex' % n


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--apk', required=True, help='apk produced by apktool b')
    ap.add_argument('--dex', required=True, help='compiled classes.dex of the mod')
    ap.add_argument('--out', required=True, help='output apk')
    ap.add_argument('--name', default='classes8.dex', help='preferred dex slot name')
    args = ap.parse_args()

    for path in (args.apk, args.dex):
        if not os.path.isfile(path):
            raise SystemExit('not found: ' + path)

    with open(args.dex, 'rb') as fh:
        dex_bytes = fh.read()
    if dex_bytes[:4] not in (b'dex\n', b'dex\x0a'):
        print('warning: %s does not start with the dex magic' % args.dex, file=sys.stderr)

    with zipfile.ZipFile(args.apk) as zin:
        names = zin.namelist()
        slot = next_dex_name(names, args.name)
        if slot != args.name:
            print('note: %s already taken, using %s' % (args.name, slot))

        with zipfile.ZipFile(args.out, 'w') as zout:
            for name in names:
                info = zin.getinfo(name)
                # resources.arsc must stay STORED (uncompressed); apktool already
                # stored it, so preserve whatever compression each entry had.
                compress = (zipfile.ZIP_STORED
                            if info.compress_type == zipfile.ZIP_STORED
                            else zipfile.ZIP_DEFLATED)
                zout.writestr(info, zin.read(name), compress_type=compress)
            zout.writestr(slot, dex_bytes, compress_type=zipfile.ZIP_DEFLATED)

    print('%s -> %s (%d bytes, mod dex %d bytes in %s)'
          % (args.apk, args.out, os.path.getsize(args.out), len(dex_bytes), slot))


if __name__ == '__main__':
    main()
