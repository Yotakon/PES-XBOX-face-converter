package com.yotakon.pesport.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parejas FACE → HAIR por número de archivo dentro del 0_text.afs, y nombres de los .bin
 * extraídos. El 0_text.afs no guarda qué HAIR usa cada FACE (eso está en la base de datos de
 * jugadores del juego), así que las parejas vienen de esta tabla.
 */
public final class Pairs {
    public static final int NONE = -1;

    /**
     * Parejas comprobadas en el 0_text.afs de PES 2010 PS2: {FACE, HAIR}.
     * 1701/4593 = Ronaldinho (idénticos byte a byte a los .bin de muestra del repositorio).
     */
    public static final int[][] KNOWN = {{1701, 4593}};

    private static final Pattern FACE_NAME = Pattern.compile("^(\\d+) FACE\\.bin$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAIR_NAME = Pattern.compile("^(\\d+) HAIR \\(slot (\\d+)\\)\\.bin$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONE_HAIR_NAME = Pattern.compile("^HAIR (\\d+) \\(sin FACE\\)\\.bin$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private final TreeMap<Integer, Integer> map = new TreeMap<>();

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

    /** Una línea "FACE=HAIR" por pareja. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    public static Pairs parse(String text) {
        Pairs p = new Pairs();
        if (text == null) {
            return p;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (t.isEmpty() || t.startsWith("#") || eq < 0) {
                continue;
            }
            try {
                p.set(Integer.parseInt(t.substring(0, eq).trim()), Integer.parseInt(t.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // línea no válida: se ignora
            }
        }
        return p;
    }

    // ------------------------------------------------------------ nombres de archivo

    public static String faceName(int face) {
        return face + " FACE.bin";
    }

    /** El HAIR se nombra con el número de su FACE para que queden juntos al ordenar. */
    public static String hairName(int face, int hair) {
        return face + " HAIR (slot " + hair + ").bin";
    }

    public static String loneHairName(int hair) {
        return "HAIR " + hair + " (sin FACE).bin";
    }

    /** Lo que se deduce del nombre de un .bin. Los campos que no aparecen valen {@link #NONE}. */
    public static final class Name {
        /** FACE al que pertenece (por nombre "N FACE.bin" o "N HAIR (slot M).bin"). */
        public final int face;
        /** Número de HAIR en el AFS ("N HAIR (slot M).bin" o "HAIR M (sin FACE).bin"). */
        public final int hair;
        /** Primer número del nombre, para nombres de otras herramientas (p. ej. unnamed_1701.bin). */
        public final int slot;
        /** true si el nombre sigue el formato de esta app. */
        public final boolean ours;

        Name(int face, int hair, int slot, boolean ours) {
            this.face = face;
            this.hair = hair;
            this.slot = slot;
            this.ours = ours;
        }
    }

    public static Name parseName(String name) {
        Matcher m = FACE_NAME.matcher(name);
        if (m.matches()) {
            int f = toInt(m.group(1));
            return new Name(f, NONE, f, true);
        }
        m = HAIR_NAME.matcher(name);
        if (m.matches()) {
            int h = toInt(m.group(2));
            return new Name(toInt(m.group(1)), h, h, true);
        }
        m = LONE_HAIR_NAME.matcher(name);
        if (m.matches()) {
            int h = toInt(m.group(1));
            return new Name(NONE, h, h, true);
        }
        m = NUMBER.matcher(name);
        return new Name(NONE, NONE, m.find() ? toInt(m.group(1)) : NONE, false);
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}
