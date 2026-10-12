# -*- coding: utf-8 -*-
"""Pack a directory of .class files into a jar, for d8.

d8 accepts class files, jars and zips but not a directory, so the compiled
classes have to be archived first. The usual tool for that is `jar`, which only
comes with a full JDK; the standard library works everywhere.

Usage:
    python pack_jar.py --classes build/classes --out build/classes.jar
"""

from __future__ import print_function

import argparse
import os
import sys
import zipfile


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--classes', required=True, help='directory holding .class files')
    ap.add_argument('--out', required=True, help='jar to write')
    ap.add_argument('--verbose', action='store_true')
    args = ap.parse_args()

    if not os.path.isdir(args.classes):
        raise SystemExit('not a directory: ' + args.classes)

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir)

    count = 0
    with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED) as jar:
        for root, _dirs, files in os.walk(args.classes):
            for name in sorted(files):
                if not name.endswith('.class'):
                    continue
                path = os.path.join(root, name)
                arcname = os.path.relpath(path, args.classes).replace(os.sep, '/')
                jar.write(path, arcname)
                count += 1
                if args.verbose:
                    print('  + ' + arcname)

    if not count:
        os.remove(args.out)
        raise SystemExit('no .class files under %s - did the compile step run?' % args.classes)

    print('packed %d classes into %s (%d bytes)'
          % (count, args.out, os.path.getsize(args.out)))


if __name__ == '__main__':
    main()
