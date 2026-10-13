"""Verify both JNI ABIs, uncompressed APK alignment and ELF PT_LOAD alignment."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk, open(sys.argv[1], 'rb') as stream:
    for abi in ('arm64-v8a', 'x86_64'):
        name = f'lib/{abi}/libparallelnest_core.so'
        entry = apk.getinfo(name)
        assert entry.compress_type == zipfile.ZIP_STORED, f'{name} must be uncompressed'
        stream.seek(entry.header_offset)
        header = stream.read(30)
        name_len, extra_len = struct.unpack_from('<HH', header, 26)
        offset = entry.header_offset + 30 + name_len + extra_len
        assert offset % 16384 == 0, f'{name} APK alignment is {offset % 16384}'
        elf = apk.read(name)
        assert elf[:5] == b'\x7fELF\x02', f'{name} is not ELF64'
        phoff = struct.unpack_from('<Q', elf, 32)[0]
        phentsize, phnum = struct.unpack_from('<HH', elf, 54)
        alignments = [struct.unpack_from('<Q', elf, phoff + i * phentsize + 48)[0] for i in range(phnum) if struct.unpack_from('<I', elf, phoff + i * phentsize)[0] == 1]
        assert alignments and all(x >= 16384 for x in alignments), f'{name}: {alignments}'
        print(f'{name}: {entry.file_size:,} bytes; APK/ELF alignment PASS ({alignments})')
