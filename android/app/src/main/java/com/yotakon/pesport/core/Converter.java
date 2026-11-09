package com.yotakon.pesport.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversion de caras y pelos de PES 2010 PS2 a PES 5 Xbox.
 *
 * Port exacto de pesport/convert.py (la version validada en el juego). Las pruebas comparan
 * la salida byte a byte con la de Python.
 */
public final class Converter {
    public enum Kind { FACE, HAIR }

    /** Resultado de una conversion. */
    public static final class Result {
        public final Kind kind;
        public final byte[] data;
        /** Textura principal convertida (cara o pelo teñido), para vista previa. */
        public final byte[] previewTexture;

        Result(Kind kind, byte[] data, byte[] previewTexture) {
            this.kind = kind;
            this.data = data;
            this.previewTexture = previewTexture;
        }
    }

    /** Informacion de un .bin antes de convertir (para la interfaz). */
    public static final class Info {
        public final Kind kind;
        public final boolean needsColor;

        Info(Kind kind, boolean needsColor) {
            this.kind = kind;
            this.needsColor = needsColor;
        }
    }

    static final double POS_SCALE = 1.0 / 16;
    static final double UV_SCALE = 1.0 / 4096;
    static final double NORMAL_SCALE = -8.0 / 32767;

    private static final byte[] GROUP_RIGID = group(1, 0x00, 0.0f);
    private static final byte[] GROUP_SKIN_A = group(2, 0x7F, 2.0f);
    private static final byte[] GROUP_SKIN_B = group(2, 0x7F, 8.0f);

    private static final double[][] FACE_RIM;
    private static final double RIM_TOLERANCE = 1.0;

    static {
        double[][] half = {
                {4.158400058746338, -19.040000915527344, 27.360000610351562},
                {4.958399772644043, -46.400001525878906, 34.400001525878906},
                {6.078400135040283, -61.599998474121094, 32.79999923706055},
                {7.358399868011475, -36.79999923706055, 32.31999969482422},
                {10.399999618530273, 5.599999904632568, 20.0},
                {10.55840015411377, -28.799999237060547, 30.559999465942383},
                {10.55840015411377, -13.600000381469727, 28.15999984741211},
                {18.878400802612305, -80.80000305175781, 10.399999618530273},
                {18.878400802612305, -73.5999984741211, 23.68000030517578},
                {26.47711944580078, 13.583999633789062, 7.199999809265137},
        };
        FACE_RIM = new double[20][];
        for (int i = 0; i < 10; i++) {
            FACE_RIM[i] = half[i];
            FACE_RIM[10 + i] = new double[]{half[i][0], half[i][1], -half[i][2]};
        }
    }

    private static final int EYE_BONE = 102;
    private static final double[] EYE_U = {0.7343800067901611, 0.7617200016975403, 0.8164100050926208,
            0.8710899949073792, 0.8984400033950806};
    private static final double[] EYE_V = {0.78125, 0.875, 0.9921900033950806};
    private static final double EYE_UV_TOLERANCE = 2.0 / 256;

    /** Huesos de PES 2010 que no existen en PES 5 (null = descartar). */
    private static final Map<Integer, Integer> BONE_MAP = new HashMap<>();

    static {
        BONE_MAP.put(103, null);
        BONE_MAP.put(111, 114);
    }

    private Converter() {
    }

    private static byte[] group(int n, int x, float f) {
        ByteBuf b = new ByteBuf();
        b.u16(n);
        b.u16(x);
        byte[] pad = new byte[12];
        Arrays.fill(pad, (byte) 0xFF);
        b.put(pad);
        b.f32(f);
        b.u32(0);
        return b.toArray();
    }

    private static Integer mapBone(int b) {
        return BONE_MAP.containsKey(b) ? BONE_MAP.get(b) : Integer.valueOf(b);
    }

    private static List<Integer> xboxBones(List<Integer> ps2) {
        List<Integer> out = new ArrayList<>();
        for (int b : ps2) {
            Integer m = mapBone(b);
            if (m != null && !out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private static byte[] hairGroup(double[] params) {
        return params[0] < 700 ? GROUP_SKIN_A : GROUP_SKIN_B;
    }

    private static final class PlanEntry {
        final byte[] group;
        final LinkedHashMap<Integer, Integer> extra = new LinkedHashMap<>();
        final List<Ps2Model.Chunk> chunks = new ArrayList<>();

        PlanEntry(byte[] group) {
            this.group = group;
        }
    }

    private static Integer xbone(Ps2Model m, Ps2Model.Chunk c, List<Integer> xbones) {
        if (c.bone < 0 || c.bone >= m.bones.size()) {
            throw new ConversionException("Indice de hueso invalido: " + c.bone);
        }
        Integer b = mapBone(m.bones.get(c.bone));
        return b == null ? null : xbones.indexOf(b);
    }

    private static List<PlanEntry> plan(Ps2Model m, Kind kind, List<Integer> xbones) {
        List<Ps2Model.Chunk> chunks = new ArrayList<>();
        for (Ps2Model.Chunk c : m.chunks) {
            if (xbone(m, c, xbones) != null) {
                chunks.add(c);
            }
        }
        List<PlanEntry> plan = new ArrayList<>();
        if (kind == Kind.FACE) {
            for (Ps2Model.Chunk c : chunks) {
                if (c.kind == 2) {
                    PlanEntry e = new PlanEntry(GROUP_RIGID);
                    e.extra.put(2, xbone(m, c, xbones));
                    e.chunks.add(c);
                    plan.add(e);
                }
            }
            LinkedHashMap<Integer, PlanEntry> skin = new LinkedHashMap<>();
            for (Ps2Model.Chunk c : chunks) {
                if (c.kind != 2) {
                    int b = xbone(m, c, xbones);
                    PlanEntry e = skin.get(b);
                    if (e == null) {
                        e = new PlanEntry(GROUP_SKIN_A);
                        e.extra.put(2, b);
                        skin.put(b, e);
                    }
                    e.chunks.add(c);
                }
            }
            plan.addAll(skin.values());
        } else {
            for (Ps2Model.Chunk c : chunks) {
                byte[] g = hairGroup(c.params);
                int b = xbone(m, c, xbones);
                PlanEntry found = null;
                for (PlanEntry e : plan) {
                    if (e.group == g && e.extra.get(2) == b) {
                        found = e;
                        break;
                    }
                }
                if (found == null) {
                    found = new PlanEntry(g);
                    found.extra.put(2, b);
                    found.extra.put(0x0A, 1);
                    plan.add(found);
                }
                found.chunks.add(c);
            }
        }
        return plan;
    }

    /** Clave de vertice con la igualdad de Python (0.0 == -0.0). */
    private static List<Double> key(double[] v) {
        List<Double> k = new ArrayList<>(v.length);
        for (double x : v) {
            k.add(x + 0.0);
        }
        return k;
    }

    private static double[] snapRim(double[] v) {
        double[] best = null;
        double dist = RIM_TOLERANCE;
        for (double[] r : FACE_RIM) {
            double dx = v[0] - r[0], dy = v[1] - r[1], dz = v[2] - r[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < dist) {
                best = r;
                dist = d;
            }
        }
        if (best == null) {
            return v;
        }
        double[] o = v.clone();
        o[0] = best[0];
        o[1] = best[1];
        o[2] = best[2];
        return o;
    }

    private static double nearest(double x, double[] table) {
        double best = table[0];
        for (double t : table) {
            if (Math.abs(t - x) < Math.abs(best - x)) {
                best = t;
            }
        }
        if (Math.abs(best - x) > EYE_UV_TOLERANCE) {
            throw new ConversionException(String.format("UV de ojo fuera de la plantilla de PES 5 (%.4f)", x));
        }
        return best;
    }

    private static boolean sameTriangles(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(XboxModel.canonical(a.get(i)), XboxModel.canonical(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    static byte[] convertModel(byte[] ps2Bytes, Kind kind) {
        Ps2Model model = Ps2Model.read(ps2Bytes);
        List<Integer> bones = xboxBones(model.bones);
        XboxModel out = new XboxModel();
        out.bones = bones;
        Map<Integer, Integer> baseToXbox = new HashMap<>();

        for (PlanEntry pe : plan(model, kind, bones)) {
            if (!out.groups.contains(pe.group)) {
                out.groups.add(pe.group);
            }
            int vstart = out.vertices.size();
            Map<List<Double>, Integer> lookup = new HashMap<>();
            List<int[]> tris = new ArrayList<>();
            for (Ps2Model.Chunk c : pe.chunks) {
                int n = c.positions.length;
                if (c.normals.length != n || c.uvs.length != n) {
                    throw new ConversionException("Chunk con datos de vertices incompletos");
                }
                int[] local = new int[n];
                for (int i = 0; i < n; i++) {
                    int[] p = c.positions[i], nr = c.normals[i], uv = c.uvs[i];
                    double[] v = {p[0] * POS_SCALE, p[1] * POS_SCALE, p[2] * POS_SCALE,
                            nr[0] * NORMAL_SCALE, nr[1] * NORMAL_SCALE, nr[2] * NORMAL_SCALE,
                            uv[0] * UV_SCALE, uv[1] * UV_SCALE};
                    List<Double> k = key(v);
                    Integer idx = lookup.get(k);
                    if (idx == null) {
                        idx = out.vertices.size();
                        lookup.put(k, idx);
                        out.vertices.add(v);
                    }
                    local[i] = idx;
                }
                if (c.morphRange != null) {
                    int bs = c.morphRange[0], first = c.morphRange[1], cnt = c.morphRange[2];
                    if (first < 0 || first + cnt > n) {
                        throw new ConversionException("Rango de morph fuera del chunk");
                    }
                    for (int j = 0; j < cnt; j++) {
                        if (!baseToXbox.containsKey(bs + j)) {
                            baseToXbox.put(bs + j, local[first + j]);
                        }
                    }
                }
                for (int[] t : c.triangles()) {
                    for (int x : t) {
                        if (x < 0 || x >= n) {
                            throw new ConversionException("Indice de vertice fuera de rango en la tira");
                        }
                    }
                    tris.add(new int[]{local[t[0]], local[t[1]], local[t[2]]});
                }
            }
            int[] strip = XboxModel.buildStrip(tris);
            if (!sameTriangles(XboxModel.stripTriangles(strip), tris)) {
                throw new ConversionException("La tira generada no reproduce los triangulos");
            }
            LinkedHashMap<Integer, Integer> state = new LinkedHashMap<>();
            state.put(3, 0);
            state.put(0x0D, 7);
            state.put(4, 0);
            state.put(1, out.groups.indexOf(pe.group));
            state.putAll(pe.extra);
            out.parts.add(new XboxModel.Part(state, strip, vstart, out.vertices.size() - vstart, tris.size()));
        }

        if (out.vertices.size() > 0xFFFF) {
            throw new ConversionException("Demasiados vertices (" + out.vertices.size() + ")");
        }

        if (kind == Kind.FACE) {
            for (int i = 0; i < out.vertices.size(); i++) {
                out.vertices.set(i, snapRim(out.vertices.get(i)));
            }
            for (XboxModel.Part p : out.parts) {
                if (bones.get(p.state.get(2)) == EYE_BONE) {
                    for (int i = p.vstart; i < p.vstart + p.vcount; i++) {
                        double[] v = out.vertices.get(i).clone();
                        v[6] = nearest(v[6], EYE_U);
                        v[7] = nearest(v[7], EYE_V);
                        out.vertices.set(i, v);
                    }
                }
            }
        }

        for (int mid = 0; mid < model.morphs.size(); mid++) {
            LinkedHashMap<Integer, double[]> acc = new LinkedHashMap<>();
            for (int[] e : model.morphs.get(mid)) {
                int base = e[3];
                if (base >= model.morphBaseCount) {
                    throw new ConversionException("Morph " + mid + " apunta a un vertice inexistente (" + base + ")");
                }
                Integer xi = baseToXbox.get(base);
                if (xi == null) {
                    continue;   // vertice de un chunk descartado
                }
                if (!acc.containsKey(xi)) {
                    acc.put(xi, new double[]{e[0] * POS_SCALE, e[1] * POS_SCALE, e[2] * POS_SCALE});
                }
            }
            int[] idx = new int[acc.size()];
            int j = 0;
            for (int i : acc.keySet()) {
                idx[j++] = i;
            }
            Arrays.sort(idx);
            double[][] d = new double[idx.length][];
            for (int i = 0; i < idx.length; i++) {
                d[i] = acc.get(idx[i]);
            }
            out.morphs.add(new XboxModel.Morph(mid, idx, d));
        }
        return out.write();
    }

    private static Kind detectKind(List<byte[]> sections) {
        if (sections.size() != 3) {
            throw new ConversionException("Se esperaban 3 secciones, hay " + sections.size());
        }
        return Texture.isTexture(sections.get(1)) ? Kind.HAIR : Kind.FACE;
    }

    private static List<byte[]> ps2Sections(byte[] bin) {
        List<byte[]> s = Container.split(Container.unpack(bin));
        detectKind(s);
        if (Bytes.u32(s.get(0), 0) != Ps2Model.MAGIC) {
            if (Bytes.u32(s.get(0), 0) == XboxModel.MAGIC) {
                throw new ConversionException("El archivo ya es de PES 5 Xbox");
            }
            throw new ConversionException("El archivo no es un modelo de PES 2010 PS2");
        }
        return s;
    }

    /**
     * Textura que tendra el archivo convertido (cara, o pelo teñido con el color indicado),
     * sin convertir los modelos. Para la vista previa del selector de color.
     */
    public static byte[] previewTexture(byte[] ps2Bin, Integer hairRgb, double hairBrightness) {
        List<byte[]> s = ps2Sections(ps2Bin);
        if (detectKind(s) == Kind.FACE) {
            return s.get(2);
        }
        byte[] t = s.get(1);
        if (hairRgb != null && Texture.isGrayscale(t)) {
            t = Texture.tint(t, (hairRgb >> 16) & 0xFF, (hairRgb >> 8) & 0xFF, hairRgb & 0xFF, hairBrightness);
        }
        return t;
    }

    /** Analiza un .bin de PES 2010 PS2 sin convertirlo. */
    public static Info inspect(byte[] ps2Bin) {
        List<byte[]> s = ps2Sections(ps2Bin);
        Kind kind = detectKind(s);
        boolean gray = kind == Kind.HAIR && (Texture.isGrayscale(s.get(1)) || Texture.isGrayscale(s.get(2)));
        return new Info(kind, gray);
    }

    /**
     * Convierte un .bin de PES 2010 PS2 en un .bin de PES 5 Xbox.
     *
     * @param hairRgb color 0xRRGGBB para teñir el pelo, o null. Obligatorio si el pelo esta en gris.
     */
    public static Result convert(byte[] ps2Bin, Integer hairRgb, double hairBrightness) {
        List<byte[]> s = ps2Sections(ps2Bin);
        Kind kind = detectKind(s);
        List<byte[]> out = new ArrayList<>();
        byte[] preview;
        if (kind == Kind.FACE) {
            out.add(convertModel(s.get(0), Kind.FACE));
            out.add(convertModel(s.get(1), Kind.FACE));
            out.add(s.get(2));
            preview = s.get(2);
        } else {
            byte[] t1 = s.get(1), t2 = s.get(2);
            if (Texture.isGrayscale(t1) || Texture.isGrayscale(t2)) {
                if (hairRgb == null) {
                    throw new ConversionException("El pelo esta en escala de grises: indica un color");
                }
                int r = (hairRgb >> 16) & 0xFF, g = (hairRgb >> 8) & 0xFF, b = hairRgb & 0xFF;
                t1 = Texture.tint(t1, r, g, b, hairBrightness);
                t2 = Texture.tint(t2, r, g, b, hairBrightness);
            }
            out.add(convertModel(s.get(0), Kind.HAIR));
            out.add(t1);
            out.add(t2);
            preview = t1;
        }
        return new Result(kind, Container.pack(Container.join(out)), preview);
    }
}
