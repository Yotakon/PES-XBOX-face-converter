"""Texturas 8 bpp con paleta (formato compartido por PES 2010 PS2 y PES 5 Xbox)."""
import struct
import zlib

MAGIC = bytes.fromhex("94728529")


def info(sec):
    if sec[:4] != MAGIC:
        raise ValueError("No es una textura PES")
    pix, pal, w, h = struct.unpack_from("<4H", sec, 0x10)
    return pix, pal, w, h


def clut_index(i):
    """Orden de paleta de PS2: intercambia los bloques 8-15 y 16-23 de cada grupo de 32."""
    return (i & ~0x18) | ((i & 0x08) << 1) | ((i & 0x10) >> 1)


def is_grayscale(sec, tolerance=3):
    """True si todos los colores son grises (diferencia entre canales <= tolerance)."""
    _, pal, _, _ = info(sec)
    p = sec[pal:pal + 1024]
    return all(max(p[k:k + 3]) - min(p[k:k + 3]) <= tolerance for k in range(0, 1024, 4))


def tint(sec, rgb, brightness=1.0):
    """Colorea una paleta en escala de grises (pelo de PES 2010) con el color elegido.

    El gris se normaliza a su valor maximo y se multiplica por el color; el alfa no cambia.
    """
    _, pal, _, _ = info(sec)
    out = bytearray(sec)
    p = sec[pal:pal + 1024]
    grays = [sum(p[k:k + 3]) / 3 for k in range(0, 1024, 4)]
    gmax = max(grays) or 1
    for k in range(0, 1024, 4):
        g = grays[k // 4] / gmax * brightness
        for c in range(3):
            out[pal + k + c] = max(0, min(255, round(g * rgb[c])))
    return bytes(out)


def to_png(sec):
    pix, pal, w, h = info(sec)
    p = sec[pal:pal + 1024]
    rows = []
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            k = clut_index(sec[pix + y * w + x]) * 4
            row += bytes((p[k], p[k + 1], p[k + 2], min(255, p[k + 3] * 2)))
        rows.append(bytes(row))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
