# -*- coding: utf-8 -*-
"""Print the binary AndroidManifest.xml of an apk as text.

Small AXML reader, no dependencies, mainly used to confirm that a patch landed
in the compiled archive without needing aapt.

Usage:
    python dump_manifest.py --apk app.apk
    python dump_manifest.py --apk app.apk --filter builtindict
    python dump_manifest.py --apk app.apk --out manifest.txt
"""

from __future__ import print_function

import argparse
import io
import struct
import sys
import zipfile

try:
    _stdout_keepalive = sys.stdout  # keep a reference: the wrapper owns the buffer
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
except Exception:
    pass

RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12


def parse_string_pool(buf, off):
    """Decode a RES_STRING_POOL chunk at ``off`` into a list of str."""
    c_type, header_size, chunk_size = struct.unpack_from('<HHI', buf, off)
    if c_type != RES_STRING_POOL:
        raise ValueError('chunk at %d is not a string pool (0x%04x)' % (off, c_type))
    count, _style_count, flags, strings_start, _styles_start = struct.unpack_from('<IIIII', buf, off + 8)
    utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from('<%dI' % count, buf, off + 28)

    out = []
    for rel in offsets:
        p = off + strings_start + rel
        if utf8:
            # two lengths: UTF-16 code units, then UTF-8 byte count
            nbytes = 0
            for which in range(2):
                length = buf[p]
                if length & 0x80:
                    length = ((length & 0x7F) << 8) | buf[p + 1]
                    p += 2
                else:
                    p += 1
                if which == 1:
                    nbytes = length
            end = p + nbytes
            out.append(buf[p:end].decode('utf-8', 'replace'))
        else:
            length = struct.unpack_from('<H', buf, p)[0]
            p += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack_from('<H', buf, p)[0]
                p += 2
            out.append(buf[p:p + 2 * length].decode('utf-16-le', 'replace'))
    return out


def attr_value(strings, raw_index, value_type, data):
    if raw_index != 0xFFFFFFFF:
        return strings[raw_index]
    if value_type == TYPE_STRING:
        return strings[data] if data != 0xFFFFFFFF else '?'
    if value_type == TYPE_INT_BOOLEAN:
        return 'true' if data else 'false'
    if value_type == TYPE_INT_DEC:
        signed = data if data < 0x80000000 else data - 0x100000000
        return str(signed)
    if value_type == TYPE_REFERENCE:
        return '@0x%08x' % data
    return 't%02x/0x%08x' % (value_type, data)


def iter_elements(axml):
    """Yield (kind, name, attrs) for every element in a binary AXML document."""
    strings = parse_string_pool(axml, 8)
    off = 8 + struct.unpack_from('<HHI', axml, 8)[2]
    while off + 8 <= len(axml):
        c_type, header_size, chunk_size = struct.unpack_from('<HHI', axml, off)
        if chunk_size <= 0:
            break
        if c_type in (RES_XML_START_ELEMENT, RES_XML_END_ELEMENT):
            base = off + 16
            _ns, name_idx = struct.unpack_from('<II', axml, base)
            attrs = []
            if c_type == RES_XML_START_ELEMENT:
                attr_start, attr_size, attr_count = struct.unpack_from('<HHH', axml, base + 8)
                for i in range(attr_count):
                    ao = base + attr_start + i * attr_size
                    _ans, a_name, a_raw, _a_size, _a_res0, a_type, a_data = \
                        struct.unpack_from('<IIIHBBI', axml, ao)
                    attrs.append((strings[a_name], attr_value(strings, a_raw, a_type, a_data)))
            yield c_type, strings[name_idx], attrs
        off += chunk_size


def dump(apk, member, out, filt):
    with zipfile.ZipFile(apk) as z:
        buf = z.read(member)
    lines = []
    depth = 0
    for kind, name, attrs in iter_elements(buf):
        if kind == RES_XML_END_ELEMENT:
            depth = max(0, depth - 1)
            continue
        text = '  ' * depth + '<' + name
        if attrs:
            text += ' ' + ' '.join('%s=%s' % kv for kv in attrs)
        text += '>'
        if not filt or filt in text:
            lines.append(text)
        depth += 1
    if out:
        with open(out, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write('\n'.join(lines) + '\n')
        print('wrote %d lines to %s' % (len(lines), out))
    else:
        print('\n'.join(lines))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--apk', required=True)
    ap.add_argument('--member', default='AndroidManifest.xml',
                    help='archive member to dump (default: AndroidManifest.xml)')
    ap.add_argument('--filter', default='', help='only print lines containing this substring')
    ap.add_argument('--out', default='', help='write to this file instead of stdout')
    args = ap.parse_args()
    dump(args.apk, args.member, args.out, args.filter)


if __name__ == '__main__':
    main()
