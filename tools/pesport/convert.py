"""Conversion de caras y pelos de PES 2010 PS2 a PES 5 Xbox."""
import struct

from . import container, ps2_model, texture, xbox_model
from .xbox_model import Morph, Part, XboxModel

POS_SCALE = 1 / 16          # PS2 int16 -> unidades Xbox
UV_SCALE = 1 / 4096
NORMAL_SCALE = -8 / 32767   # Xbox guarda normales de longitud 8 y con signo invertido

# Entradas de la tabla de grupos de piel de PES 5 Xbox (24 bytes), copiadas de archivos reales.
_PAD = b"\xff" * 12
GROUP_RIGID = b"\x01\x00\x00\x00" + _PAD + struct.pack("<f", 0.0) + b"\x00" * 4
GROUP_SKIN_A = b"\x02\x00\x7f\x00" + _PAD + struct.pack("<f", 2.0) + b"\x00" * 4
GROUP_SKIN_B = b"\x02\x00\x7f\x00" + _PAD + struct.pack("<f", 8.0) + b"\x00" * 4

BASE_STATE = {3: 0, 0x0D: 7, 4: 0}

# Borde exterior de la cara de PES 5 (union con la cabeza del cuerpo). Medido en el archivo
# Xbox original, igual en los dos LOD. PES 2010 tiene los mismos puntos con error de
# cuantizacion (hasta 0.57); se ajustan a estas posiciones exactas para cerrar la union.
FACE_RIM = [
    (4.158400058746338, -19.040000915527344, 27.360000610351562),
    (4.958399772644043, -46.400001525878906, 34.400001525878906),
    (6.078400135040283, -61.599998474121094, 32.79999923706055),
    (7.358399868011475, -36.79999923706055, 32.31999969482422),
    (10.399999618530273, 5.599999904632568, 20.0),
    (10.55840015411377, -28.799999237060547, 30.559999465942383),
    (10.55840015411377, -13.600000381469727, 28.15999984741211),
    (18.878400802612305, -80.80000305175781, 10.399999618530273),
    (18.878400802612305, -73.5999984741211, 23.68000030517578),
    (26.47711944580078, 13.583999633789062, 7.199999809265137),
]
FACE_RIM += [(x, y, -z) for x, y, z in FACE_RIM]
RIM_TOLERANCE = 1.0

# UV exactos de la pieza de ojos (hueso 102) en PES 5, medidos en el archivo Xbox original.
# PES 2010 usa los mismos puntos redondeados a 1/4096 y con eso el juego no mueve los ojos
# (confirmado en el juego). Se ajusta cada UV al valor de PES 5 mas cercano.
EYE_BONE = 102
EYE_U = (0.7343800067901611, 0.7617200016975403, 0.8164100050926208, 0.8710899949073792,
         0.8984400033950806)
EYE_V = (0.78125, 0.875, 0.9921900033950806)
EYE_UV_TOLERANCE = 2 / 256

# Huesos de PES 2010 que no existen en PES 5 (None = eliminar).
BONE_MAP = {103: None, 111: 114}


class ConversionError(Exception):
    pass


def _bones(ps2_bones):
    out = []
    for b in ps2_bones:
        b = BONE_MAP.get(b, b)
        if b is not None and b not in out:
            out.append(b)
    return out


def _snap_rim(v):
    best, dist = None, RIM_TOLERANCE
    for r in FACE_RIM:
        d = ((v[0] - r[0]) ** 2 + (v[1] - r[1]) ** 2 + (v[2] - r[2]) ** 2) ** 0.5
        if d < dist:
            best, dist = r, d
    return v if best is None else best + tuple(v[3:])


def _snap_eye_uv(v):
    def near(x, table):
        best = min(table, key=lambda t: abs(t - x))
        if abs(best - x) > EYE_UV_TOLERANCE:
            raise ConversionError("UV de ojo fuera de la plantilla de PES 5 (%.4f)" % x)
        return best
    return tuple(v[:6]) + (near(v[6], EYE_U), near(v[7], EYE_V))


def _hair_group(params):
    # PES 2010: (373.0, -118.0) en la parte frontal, (1019.6, -764.6) en el resto.
    return GROUP_SKIN_A if params[0] < 700 else GROUP_SKIN_B


def _plan(model, kind, xbox_bones):
    """Agrupa chunks PS2 en partes Xbox: lista de (grupo_bytes, estado_extra, [chunks]).

    El tag 2 de Xbox es el indice del hueso en la lista de huesos del modelo, igual que
    u32[13] en PS2. Los chunks atados a huesos que PES 5 no tiene (103 = molde de barba de
    PES 2010) se descartan.
    """
    def xbone(c):
        b = BONE_MAP.get(model.bones[c.bone], model.bones[c.bone])
        return None if b is None else xbox_bones.index(b)

    chunks = [c for c in model.chunks if xbone(c) is not None]
    plan = []
    if kind == "face":
        for c in chunks:
            if c.kind == 2:
                plan.append((GROUP_RIGID, {2: xbone(c)}, [c]))
        skin = {}
        for c in chunks:
            if c.kind != 2:
                skin.setdefault(xbone(c), []).append(c)
        for b, lst in skin.items():
            plan.append((GROUP_SKIN_A, {2: b}, lst))
    else:
        for c in chunks:
            key = (_hair_group(c.params), xbone(c))
            for g, st, lst in plan:
                if (g, st[2]) == key:
                    lst.append(c)
                    break
            else:
                plan.append((key[0], {2: key[1], 0x0A: 1}, [c]))
    return plan


def convert_model(ps2_bytes, kind):
    model = ps2_model.read(ps2_bytes)
    bones = _bones(model.bones)
    plan = _plan(model, kind, bones)

    groups = []
    vertices = []
    parts = []
    base_to_xbox = {}

    for group, extra, chunks in plan:
        if group not in groups:
            groups.append(group)
        vstart = len(vertices)
        lookup = {}
        tris = []
        for c in chunks:
            local = []
            for i, (p, n, uv) in enumerate(zip(c.positions, c.normals, c.uvs)):
                v = (p[0] * POS_SCALE, p[1] * POS_SCALE, p[2] * POS_SCALE,
                     n[0] * NORMAL_SCALE, n[1] * NORMAL_SCALE, n[2] * NORMAL_SCALE,
                     uv[0] * UV_SCALE, uv[1] * UV_SCALE)
                if v not in lookup:
                    lookup[v] = len(vertices)
                    vertices.append(v)
                local.append(lookup[v])
            if c.morph_range:
                bs, first, cnt = c.morph_range
                for j in range(cnt):
                    base_to_xbox.setdefault(bs + j, local[first + j])
            tris += [(local[a], local[b], local[cc]) for a, b, cc in c.triangles()]
        strip = xbox_model.build_strip(tris)
        if ([xbox_model.canonical(t) for t in xbox_model.strip_triangles(strip)]
                != [xbox_model.canonical(t) for t in tris]):
            raise ConversionError("La tira generada no reproduce los triangulos")
        state = dict(BASE_STATE)
        state[1] = groups.index(group)
        state.update(extra)
        parts.append(Part(state, strip, vstart, len(vertices) - vstart, len(tris)))

    if len(vertices) > 0xFFFF:
        raise ConversionError("Demasiados vertices (%d)" % len(vertices))

    if kind == "face":
        vertices = [_snap_rim(v) for v in vertices]
        for p in parts:
            if bones[p.state[2]] == EYE_BONE:
                for i in range(p.vstart, p.vstart + p.vcount):
                    vertices[i] = _snap_eye_uv(vertices[i])

    morphs = []
    for mid, entries in enumerate(model.morphs):
        acc = {}
        for dx, dy, dz, base in entries:
            if base >= model.morph_base_count:
                raise ConversionError("Morph %d apunta a un vertice inexistente (%d)" % (mid, base))
            if base not in base_to_xbox:      # vertice de un chunk descartado
                continue
            acc.setdefault(base_to_xbox[base], (dx * POS_SCALE, dy * POS_SCALE, dz * POS_SCALE))
        idx = sorted(acc)
        morphs.append(Morph(mid, idx, [acc[i] for i in idx]))

    return xbox_model.write(XboxModel(groups, bones, vertices, morphs, parts))


def detect_kind(sections):
    """'face' si la tercera seccion es textura y la segunda modelo; 'hair' si hay dos texturas."""
    if len(sections) != 3:
        raise ConversionError("Se esperaban 3 secciones, hay %d" % len(sections))
    if sections[1][:4] == texture.MAGIC:
        return "hair"
    return "face"


def convert_bin(ps2_bin, hair_rgb=None, hair_brightness=1.0):
    """Convierte un .bin de PES 2010 PS2 en un .bin de PES 5 Xbox.

    hair_rgb: color (r, g, b) 0-255 para teñir el pelo; obligatorio si el pelo esta en gris.
    """
    sections = container.split(container.unpack(ps2_bin))
    kind = detect_kind(sections)
    if struct.unpack_from("<I", sections[0], 0)[0] != ps2_model.MAGIC:
        raise ConversionError("El archivo no es un modelo de PES 2010 PS2")

    if kind == "face":
        out = [convert_model(sections[0], "face"), convert_model(sections[1], "face"), sections[2]]
    else:
        texs = sections[1:]
        if any(texture.is_grayscale(t) for t in texs):
            if hair_rgb is None:
                raise ConversionError("El pelo esta en escala de grises: indica un color")
            texs = [texture.tint(t, hair_rgb, hair_brightness) for t in texs]
        out = [convert_model(sections[0], "hair")] + list(texs)
    return kind, container.pack(container.join(out))
