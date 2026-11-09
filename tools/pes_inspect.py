#!/usr/bin/env python3
"""Herramienta de analisis para archivos de caras/pelos de PES (PS2 / Xbox).

Solo usa la libreria estandar de Python.

Uso:
  pes_inspect.py info   ARCHIVO            Informe de estructura (cabecera, zlib, tablas)
  pes_inspect.py unpack ARCHIVO SALIDA     Descomprime un .bin (WESYS/zlib) a SALIDA
  pes_inspect.py afs-list ARCHIVO.afs      Lista las entradas de un AFS
  pes_inspect.py afs-extract ARCHIVO.afs DIR [IDX ...]   Extrae entradas de un AFS
  pes_inspect.py hexdump ARCHIVO [OFFSET] [LONGITUD]
"""
import os
import struct
import sys
import zlib

WESYS_MAGIC = b"WESYS"


# ---------------------------------------------------------------- utilidades

def hexdump(data, base=0, length=256):
    out = []
    for i in range(0, min(len(data), length), 16):
        chunk = data[i:i + 16]
        hx = " ".join(f"{b:02x}" for b in chunk)
        asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        out.append(f"{base + i:08x}  {hx:<47}  {asc}")
    return "\n".join(out)


def u32(data, off):
    return struct.unpack_from("<I", data, off)[0]


# ---------------------------------------------------------------- compresion

def wesys_header(data):
    """Cabecera comprimida de Konami: 3 bytes + 'WESYS' + tam_comp + tam_descomp."""
    if len(data) >= 16 and data[3:8] == WESYS_MAGIC:
        return {"comp_size": u32(data, 8), "raw_size": u32(data, 12), "data_off": 16}
    # Cabecera usada por PES 2010 PS2 y PES 5 Xbox: 00 06 01 00, comp, raw, relleno hasta 0x20
    if len(data) >= 0x22 and data[:4] == b"\x00\x06\x01\x00" and data[0x20] == 0x78:
        return {"comp_size": u32(data, 4), "raw_size": u32(data, 8), "data_off": 0x20}
    return None


def try_zlib(data, off):
    try:
        d = zlib.decompressobj()
        raw = d.decompress(data[off:])
        if d.eof:
            return raw, len(data) - off - len(d.unused_data)
    except zlib.error:
        pass
    return None, 0


def find_zlib_streams(data, limit=64):
    """Busca streams zlib (78 01/5e/9c/da) en cualquier offset."""
    found = []
    i = 0
    while i < len(data) - 2 and len(found) < limit:
        if data[i] == 0x78 and data[i + 1] in (0x01, 0x5E, 0x9C, 0xDA):
            raw, used = try_zlib(data, i)
            if raw is not None and len(raw) > 0:
                found.append((i, used, len(raw)))
                i += used
                continue
        i += 1
    return found


def unpack(data):
    h = wesys_header(data)
    if h:
        raw, _ = try_zlib(data, h["data_off"])
        if raw is None:
            raise ValueError("Cabecera WESYS valida pero el zlib no descomprime")
        if len(raw) != h["raw_size"]:
            print(f"aviso: tamano descomprimido {len(raw)} != cabecera {h['raw_size']}",
                  file=sys.stderr)
        return raw
    raw, _ = try_zlib(data, 0)
    if raw is not None:
        return raw
    return None


# ---------------------------------------------------------------- AFS

def afs_entries(data):
    if data[:4] not in (b"AFS\x00", b"AFS\x20"):
        raise ValueError("No es un AFS (magic distinto de 'AFS')")
    count = u32(data, 4)
    return [(i, u32(data, 8 + i * 8), u32(data, 12 + i * 8)) for i in range(count)]


# ---------------------------------------------------------------- analisis

def entropy(block):
    import math
    if not block:
        return 0.0
    freq = [0] * 256
    for b in block:
        freq[b] += 1
    n = len(block)
    return -sum(c / n * math.log2(c / n) for c in freq if c)


def guess_offset_table(data, max_entries=64):
    """Interpreta los primeros u32 como posible tabla de offsets crecientes."""
    vals = []
    for off in range(0, min(len(data), max_entries * 4), 4):
        vals.append(u32(data, off))
    best = []
    for start in range(len(vals)):
        seq = []
        prev = -1
        for v in vals[start:]:
            if prev < v < len(data) and v % 4 == 0:
                seq.append(v)
                prev = v
            else:
                break
        if len(seq) > len(best):
            best = seq
    return best


def find_palettes(data, step=16):
    """Busca bloques de 1024 bytes (256 colores RGBA) con alfa tipo PS2 (<=0x80)."""
    hits = []
    for off in range(0, len(data) - 1024, step):
        alphas = data[off + 3:off + 1024:4]
        if all(a <= 0x80 for a in alphas) and alphas.count(0x80) > 128:
            hits.append(off)
    # compactar consecutivos
    merged = []
    for h in hits:
        if not merged or h - merged[-1] >= 1024:
            merged.append(h)
    return merged


def report(path, data, indent=""):
    p = lambda s="": print(indent + s)
    p(f"Archivo: {path}  ({len(data)} bytes, 0x{len(data):x})")
    p(f"Magic: {data[:8].hex(' ')}  {data[:8]!r}")

    h = wesys_header(data)
    if h:
        p(f"Cabecera WESYS: comprimido={h['comp_size']} descomprimido={h['raw_size']}")
        raw = unpack(data)
        p(f"Descomprimido OK: {len(raw)} bytes")
        p("--- contenido descomprimido ---")
        report(path + " [descomprimido]", raw, indent + "  ")
        return

    if data[:4] in (b"AFS\x00", b"AFS\x20"):
        ents = afs_entries(data)
        p(f"Contenedor AFS con {len(ents)} entradas")
        return

    streams = find_zlib_streams(data)
    if streams:
        p(f"Streams zlib encontrados: {len(streams)}")
        for off, used, size in streams[:16]:
            p(f"  offset 0x{off:x}  comprimido {used}  descomprimido {size}")

    table = guess_offset_table(data)
    if len(table) >= 2:
        p(f"Posible tabla de offsets al inicio: {[hex(v) for v in table[:16]]}")

    pals = find_palettes(data)
    if pals:
        p(f"Posibles paletas 256 colores estilo PS2 (alfa<=0x80): {[hex(o) for o in pals[:16]]}")

    p("Entropia por bloques de 4 KB (alta ~8 = comprimido/textura, baja = tablas/ceros):")
    line = []
    for off in range(0, len(data), 4096):
        line.append(f"{entropy(data[off:off + 4096]):.1f}")
    p("  " + " ".join(line[:64]) + (" ..." if len(line) > 64 else ""))
    p("Primeros 128 bytes:")
    for l in hexdump(data, 0, 128).splitlines():
        p("  " + l)


# ---------------------------------------------------------------- CLI

def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1
    cmd, path = argv[1], argv[2]
    with open(path, "rb") as f:
        data = f.read()

    if cmd == "info":
        report(path, data)
    elif cmd == "unpack":
        raw = unpack(data)
        if raw is None:
            print("El archivo no esta comprimido (ni WESYS ni zlib al inicio)", file=sys.stderr)
            return 2
        with open(argv[3], "wb") as f:
            f.write(raw)
        print(f"{len(data)} -> {len(raw)} bytes en {argv[3]}")
    elif cmd == "afs-list":
        for i, off, size in afs_entries(data):
            if size:
                print(f"{i:5d}  offset 0x{off:08x}  tam {size}")
    elif cmd == "afs-extract":
        out = argv[3]
        wanted = {int(x) for x in argv[4:]}
        os.makedirs(out, exist_ok=True)
        for i, off, size in afs_entries(data):
            if size and (not wanted or i in wanted):
                with open(os.path.join(out, f"unnamed_{i}.bin"), "wb") as f:
                    f.write(data[off:off + size])
        print("Hecho")
    elif cmd == "hexdump":
        off = int(argv[3], 0) if len(argv) > 3 else 0
        ln = int(argv[4], 0) if len(argv) > 4 else 256
        print(hexdump(data[off:], off, ln))
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
