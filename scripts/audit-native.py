#!/usr/bin/env python3
"""Check ABI and 16 KiB PT_LOAD alignment for every packaged ELF file."""
import argparse
import struct
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('native_directory', type=Path)
args = p.parse_args()
count = 0
for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
    directory = args.native_directory / abi
    if not directory.is_dir():
        raise SystemExit('Missing ABI directory: ' + abi)
    for file in sorted(directory.glob('*.so')):
        data = file.read_bytes()
        if data[:6] != b'\x7fELF\x02\x01':
            raise SystemExit('Not a little-endian ELF64: ' + str(file))
        if struct.unpack_from('<H', data, 18)[0] != machine:
            raise SystemExit('Wrong ABI: ' + str(file))
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, total = struct.unpack_from('<HH', data, 54)
        loads = []
        for index in range(total):
            header = offset + size * index
            if struct.unpack_from('<I', data, header)[0] == 1:
                alignment = struct.unpack_from('<Q', data, header + 48)[0]
                loads.append(alignment)
        if not loads or min(loads) < 16384:
            raise SystemExit('Insufficient page alignment: ' + str(file))
        count += 1
print(f'Checked {count} ELF files across both ABIs; every PT_LOAD supports 16 KiB alignment')
