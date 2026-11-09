"""Contenedor .bin de caras/pelos (igual en PES 2010 PS2 y PES 5 Xbox)."""
import struct
import zlib

MAGIC = b"\x00\x06\x01\x00"
HEADER = 0x20


def unpack(data):
    if data[:4] != MAGIC:
        raise ValueError("Cabecera .bin desconocida: %s" % data[:4].hex())
    comp, raw = struct.unpack_from("<2I", data, 4)
    out = zlib.decompress(data[HEADER:HEADER + comp])
    if len(out) != raw:
        raise ValueError("Tamano descomprimido %d != %d" % (len(out), raw))
    return out


def pack(raw):
    comp = zlib.compress(raw, 9)
    return MAGIC + struct.pack("<2I", len(comp), len(raw)) + b"\x00" * (HEADER - 12) + comp


def split(raw):
    n = struct.unpack_from("<I", raw, 0)[0]
    offs = list(struct.unpack_from("<%dI" % n, raw, 8)) + [len(raw)]
    return [raw[offs[i]:offs[i + 1]] for i in range(n)]


def join(sections):
    head = 8 + 4 * len(sections)
    head = (head + 0x1F) & ~0x1F
    out = bytearray(b"\x00" * head)
    struct.pack_into("<II", out, 0, len(sections), 8)
    for i, s in enumerate(sections):
        struct.pack_into("<I", out, 8 + 4 * i, len(out))
        out += s
        while len(out) % 16:
            out += b"\x00"
    return bytes(out)
