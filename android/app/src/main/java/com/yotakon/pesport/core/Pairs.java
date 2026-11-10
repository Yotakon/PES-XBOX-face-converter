package com.yotakon.pesport.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parejas FACE → HAIR por número de archivo dentro del 0_text.afs, nombres de jugador de cada
 * FACE y nombres de los .bin extraídos. Las parejas salen de la base de datos de jugadores
 * ({@link PlayerDb}); el usuario puede cambiar cualquiera a mano.
 */
public final class Pairs {
    public static final int NONE = -1;

    private static final Pattern FACE_NAME = Pattern.compile("^(\\d+) FACE( - .*)?\\.bin$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAIR_NAME = Pattern.compile("^(\\d+) HAIR \\(slot (\\d+)\\)( - .*)?\\.bin$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONE_HAIR_NAME = Pattern.compile("^HAIR (\\d+) \\(sin FACE\\)\\.bin$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    /** De dónde sale el HAIR de una FACE. */
    public enum Source {
        /** De la base de datos de jugadores (con HAIR propio o genérico). */
        DB,
        /** FACE fuera de la base de datos: HAIR deducido por el orden de los archivos. */
        DEDUCED,
        /** FACE fuera de la base de datos y hay menos HAIRS libres que FACES: hay que elegirlo. */
        AMBIGUOUS,
        /** Elegido a mano. */
        MANUAL
    }

    /** Nombre que se muestra para una FACE que no está en la base de datos. */
    public static final String NOT_IMPLEMENTED = "No Implementado";

    private final TreeMap<Integer, Integer> map = new TreeMap<>();
    private final TreeMap<Integer, List<String>> names = new TreeMap<>();
    private final TreeMap<Integer, Source> sources = new TreeMap<>();
    private final TreeMap<Integer, List<Integer>> candidates = new TreeMap<>();

    /**
     * Parejas de la base de datos: cada jugador con FACE especial da FACE → su HAIR especial, o
     * nada si usa un peinado genérico. Solo se aceptan números que de verdad son FACE / HAIR.
     */
    public static Pairs fromDb(List<PlayerDb.Player> players, int firstFace, int firstHair,
                               Set<Integer> faces, Set<Integer> hairs) {
        Pairs p = new Pairs();
        for (PlayerDb.Player pl : players) {
            int face = pl.faceSlot(firstFace);
            if (face == NONE || !faces.contains(face)) {
                continue;
            }
            p.addName(face, pl.name);
            p.sources.put(face, Source.DB);
            int hair = pl.hairSlot(firstHair);
            if (hair != NONE && hairs.contains(hair) && !p.has(face)) {
                p.set(face, hair);
            }
        }
        return p;
    }

    /**
     * Deduce el HAIR de las FACES que no están en la base de datos. En el AFS los HAIRS van en el
     * mismo orden que sus FACES (en PES 2010 PS2: 819 parejas de la base de datos sin ninguna
     * inversión, y nunca hay más HAIRS que FACES entre dos parejas). Entre dos parejas conocidas,
     * si hay tantos HAIRS libres como FACES sin jugador, van uno a uno en orden; si no hay HAIRS,
     * esas FACES no tienen; si hay menos, quedan {@link Source#AMBIGUOUS} con los candidatos.
     *
     * @param faces números de FACE del AFS, ordenados
     * @param hairs números de HAIR del AFS, ordenados
     */
    public void inferMissing(List<Integer> faces, List<Integer> hairs) {
        List<int[]> anchors = new ArrayList<>();
        anchors.add(new int[]{-1, -1});
        int lastHair = -1;
        for (int fi = 0; fi < faces.size(); fi++) {
            int face = faces.get(fi);
            if (sources.get(face) != Source.DB || !has(face)) {
                continue;
            }
            int hi = Collections.binarySearch(hairs, hairFor(face));
            if (hi <= lastHair) {
                continue;  // fuera de orden: no sirve de referencia
            }
            anchors.add(new int[]{fi, hi});
            lastHair = hi;
        }
        anchors.add(new int[]{faces.size(), hairs.size()});
        for (int a = 0; a + 1 < anchors.size(); a++) {
            int[] from = anchors.get(a), to = anchors.get(a + 1);
            List<Integer> open = new ArrayList<>();
            for (int fi = from[0] + 1; fi < to[0]; fi++) {
                if (!sources.containsKey(faces.get(fi))) {
                    open.add(faces.get(fi));
                }
            }
            List<Integer> free = new ArrayList<>(hairs.subList(from[1] + 1, to[1]));
            for (int k = 0; k < open.size(); k++) {
                int face = open.get(k);
                if (free.size() == open.size()) {
                    set(face, free.get(k));
                    sources.put(face, Source.DEDUCED);
                } else if (free.isEmpty()) {
                    sources.put(face, Source.DEDUCED);
                } else {
                    sources.put(face, Source.AMBIGUOUS);
                    candidates.put(face, free);
                }
            }
        }
    }

    /** Origen del HAIR de esta FACE (null si no se sabe nada). */
    public Source source(int face) {
        return sources.get(face);
    }

    /** HAIRS posibles de una FACE {@link Source#AMBIGUOUS} (vacío si no es ambigua). */
    public List<Integer> candidates(int face) {
        List<Integer> l = candidates.get(face);
        return l == null ? Collections.<Integer>emptyList() : l;
    }

    /** Cuántas FACES tienen este origen. */
    public int count(Source s) {
        int n = 0;
        for (Source v : sources.values()) {
            if (v == s) {
                n++;
            }
        }
        return n;
    }

    /** Cuántas FACES con este origen tienen HAIR. */
    public int countWithHair(Source s) {
        int n = 0;
        for (Map.Entry<Integer, Source> e : sources.entrySet()) {
            if (e.getValue() == s && has(e.getKey())) {
                n++;
            }
        }
        return n;
    }

    /** Número de HAIR de un FACE, o {@link #NONE}. */
    public int hairFor(int face) {
        Integer h = map.get(face);
        return h == null ? NONE : h;
    }

    /** Primer FACE emparejado con este HAIR, o {@link #NONE}. */
    public int faceFor(int hair) {
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (e.getValue() == hair) {
                return e.getKey();
            }
        }
        return NONE;
    }

    public void set(int face, int hair) {
        if (hair == NONE) {
            map.remove(face);
        } else {
            map.put(face, hair);
        }
    }

    public boolean has(int face) {
        return map.containsKey(face);
    }

    public int size() {
        return map.size();
    }

    public boolean isHairUsed(int hair) {
        return map.containsValue(hair);
    }

    public void addName(int face, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        List<String> l = names.get(face);
        if (l == null) {
            l = new ArrayList<>();
            names.put(face, l);
        }
        if (!l.contains(name)) {
            l.add(name);
        }
    }

    /** Jugadores que usan esta FACE (normalmente uno). */
    public List<String> names(int face) {
        List<String> l = names.get(face);
        return l == null ? Collections.<String>emptyList() : l;
    }

    /**
     * Jugadores separados por " / "; {@link #NOT_IMPLEMENTED} si la FACE no está en la base de
     * datos; "" si no se cargó ninguna base de datos.
     */
    public String label(int face) {
        StringBuilder sb = new StringBuilder();
        for (String n : names(face)) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(n);
        }
        if (sb.length() == 0 && !sources.isEmpty() && sources.get(face) != Source.DB) {
            return NOT_IMPLEMENTED;
        }
        return sb.toString();
    }

    /** Copia con las mismas parejas, nombres y orígenes. */
    public Pairs copy() {
        Pairs p = new Pairs();
        p.map.putAll(map);
        p.sources.putAll(sources);
        p.candidates.putAll(candidates);
        for (Map.Entry<Integer, List<String>> e : names.entrySet()) {
            p.names.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return p;
    }

    // ------------------------------------------------------------ cambios a mano

    /** Aplica los cambios del usuario ({@link #NONE} = "Sin HAIR"). */
    public void apply(Map<Integer, Integer> overrides) {
        for (Map.Entry<Integer, Integer> e : overrides.entrySet()) {
            setManual(e.getKey(), e.getValue());
        }
    }

    /** Cambio a mano: la FACE deja de ser deducida o ambigua. */
    public void setManual(int face, int hair) {
        set(face, hair);
        if (sources.get(face) != Source.DB) {
            sources.put(face, Source.MANUAL);
        }
        candidates.remove(face);
    }

    /** Una línea "FACE=HAIR" por cambio; HAIR = -1 es "Sin HAIR". */
    public static String serializeOverrides(Map<Integer, Integer> overrides) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : overrides.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    public static TreeMap<Integer, Integer> parseOverrides(String text) {
        TreeMap<Integer, Integer> out = new TreeMap<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (t.isEmpty() || t.startsWith("#") || eq < 0) {
                continue;
            }
            try {
                int face = Integer.parseInt(t.substring(0, eq).trim());
                int hair = Integer.parseInt(t.substring(eq + 1).trim());
                out.put(face, hair < 0 ? NONE : hair);
            } catch (NumberFormatException ignored) {
                // línea no válida: se ignora
            }
        }
        return out;
    }

    // ------------------------------------------------------------ nombres de archivo

    private static String suffix(String player) {
        String s = player == null ? "" : PlayerDb.safeName(player);
        return s.isEmpty() ? "" : " - " + s;
    }

    /** "1701 FACE - RONALDINHO.bin" (sin nombre de jugador: "1701 FACE.bin"). */
    public static String faceName(int face, String player) {
        return face + " FACE" + suffix(player) + ".bin";
    }

    /** El HAIR se nombra con el número de su FACE para que queden juntos al ordenar. */
    public static String hairName(int face, int hair, String player) {
        return face + " HAIR (slot " + hair + ")" + suffix(player) + ".bin";
    }

    public static String loneHairName(int hair) {
        return "HAIR " + hair + " (sin FACE).bin";
    }

    /** Lo que se deduce del nombre de un .bin. Los campos que no aparecen valen {@link #NONE}. */
    public static final class Name {
        /** FACE al que pertenece (por nombre "N FACE…" o "N HAIR (slot M)…"). */
        public final int face;
        /** Número de HAIR en el AFS ("N HAIR (slot M)…" o "HAIR M (sin FACE).bin"). */
        public final int hair;
        /** Primer número del nombre, para nombres de otras herramientas (p. ej. unnamed_1701.bin). */
        public final int slot;
        /** true si el nombre sigue el formato de esta app. */
        public final boolean ours;
        /** Jugador que aparece en el nombre ("" si no hay). */
        public final String player;

        Name(int face, int hair, int slot, boolean ours, String player) {
            this.face = face;
            this.hair = hair;
            this.slot = slot;
            this.ours = ours;
            this.player = player;
        }
    }

    private static String playerOf(String group) {
        return group == null ? "" : group.substring(3).trim();
    }

    public static Name parseName(String name) {
        Matcher m = FACE_NAME.matcher(name);
        if (m.matches()) {
            int f = toInt(m.group(1));
            return new Name(f, NONE, f, true, playerOf(m.group(2)));
        }
        m = HAIR_NAME.matcher(name);
        if (m.matches()) {
            int h = toInt(m.group(2));
            return new Name(toInt(m.group(1)), h, h, true, playerOf(m.group(3)));
        }
        m = LONE_HAIR_NAME.matcher(name);
        if (m.matches()) {
            int h = toInt(m.group(1));
            return new Name(NONE, h, h, true, "");
        }
        m = NUMBER.matcher(name);
        return new Name(NONE, NONE, m.find() ? toInt(m.group(1)) : NONE, false, "");
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}
