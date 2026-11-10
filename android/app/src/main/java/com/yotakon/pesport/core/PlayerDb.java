package com.yotakon.pesport.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Base de datos de jugadores de PES 2010 PS2 dentro del 0_text.afs (archivos 32 y 33, iguales en
 * caras y pelos). Da el nombre, la FACE y el HAIR de cada jugador.
 *
 * <p>Formato del archivo: contenedor {@code 00 06 00 00}; en 0x20 la tabla de secciones
 * (u32 número, u32 8, u32 offsets relativos a 0x20). La sección 0 es {@code 00 00 01 00},
 * u32 tamaño comprimido, u32 tamaño descomprimido y zlib en +0x20. Descomprimida son registros de
 * 124 bytes: nombre en japonés (UTF-16, 32 bytes), nombre (ASCII, 16 bytes) y datos desde +48.
 *
 * <p>Campos (como en los editores de option file de PES 2007-2010):
 * <ul>
 * <li>+88, bits 6-7: color de piel (0-3).</li>
 * <li>+92, 12 bits: peinado. Desde 1044 es un HAIR especial: archivo = primer HAIR + (id - 1044).</li>
 * <li>+100, bits 5-15: número de FACE dentro de su grupo de piel.</li>
 * <li>+102, bits 0-1: tipo de cara; 2 = FACE especial (tiene archivo en el AFS).</li>
 * </ul>
 * Las FACES del AFS están agrupadas por color de piel en el orden 3, 2, 0, 1; el tamaño de cada
 * grupo está en el archivo 505 ({@link #groupStarts}).
 */
public final class PlayerDb {
    public static final int RECORD = 124;
    /** Archivos del 0_text.afs donde está la base de datos. */
    public static final int[] AFS_ENTRIES = {32, 33};
    /** Primer peinado que es un HAIR especial (el primer archivo de HAIR del AFS). */
    public static final int FIRST_SPECIAL_HAIR = 1044;
    /** Archivo del 0_text.afs con el número de FACES de cada grupo de piel (sección 0, u16 x4). */
    public static final int GROUP_TABLE_ENTRY = 505;
    /** Orden de los grupos de piel en el bloque de FACES del AFS. */
    static final int[] GROUP_ORDER = {3, 2, 0, 1};
    /**
     * Inicio de cada grupo (índice = color de piel), contado desde el primer archivo de FACE.
     * Valores del 0_text.afs de PES 2010 PS2 (grupos de 800, 341, 126 y 164 FACES); se usan si
     * no se puede leer el archivo {@link #GROUP_TABLE_ENTRY}.
     */
    public static final int[] DEFAULT_GROUP_START = {290, 1090, 164, 0};

    /**
     * Inicio de cada grupo de piel leído del archivo 505, o null si no tiene el formato esperado
     * o los grupos no suman {@code faceCount}.
     */
    public static int[] groupStarts(byte[] entry505, int faceCount) {
        try {
            List<byte[]> secs = Container.split(Container.unpack(entry505));
            byte[] s0 = secs.get(0);
            int[] size = new int[4];
            int total = 0;
            for (int k = 0; k < 4; k++) {
                size[k] = Bytes.u16(s0, 2 * k);
                total += size[k];
            }
            if (total != faceCount) {
                return null;
            }
            int[] start = new int[4];
            int at = 0;
            for (int g : GROUP_ORDER) {
                start[g] = at;
                at += size[g];
            }
            return start;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static final class Player {
        public final int index;
        public final String name;
        public final int skin;
        public final boolean specialFace;
        public final int faceId;
        public final int hairId;

        Player(int index, String name, int skin, boolean specialFace, int faceId, int hairId) {
            this.index = index;
            this.name = name;
            this.skin = skin;
            this.specialFace = specialFace;
            this.faceId = faceId;
            this.hairId = hairId;
        }

        /** Archivo de FACE en el AFS, o {@link Pairs#NONE} si el jugador no tiene cara especial. */
        public int faceSlot(int firstFace, int[] groupStart) {
            return specialFace ? firstFace + groupStart[skin] + faceId : Pairs.NONE;
        }

        /** Archivo de HAIR en el AFS, o {@link Pairs#NONE} si usa un peinado genérico. */
        public int hairSlot(int firstHair) {
            return hairId >= FIRST_SPECIAL_HAIR ? firstHair + hairId - FIRST_SPECIAL_HAIR : Pairs.NONE;
        }
    }

    private PlayerDb() {
    }

    /** Lee los jugadores de un archivo del AFS; lista vacía si no es la base de datos. */
    public static List<Player> read(byte[] entry) {
        List<Player> out = new ArrayList<>();
        byte[] raw = unpack(entry);
        if (raw == null) {
            return out;
        }
        for (int i = 0; i + RECORD <= raw.length; i += RECORD) {
            int skin = (Bytes.u16(raw, i + 88) >> 6) & 3;
            int hair = Bytes.u16(raw, i + 92) & 0xFFF;
            int face = (Bytes.u16(raw, i + 100) >> 5) & 0x7FF;
            boolean special = (Bytes.u16(raw, i + 102) & 3) == 2;
            out.add(new Player(i / RECORD, name(raw, i + 32), skin, special, face, hair));
        }
        return out;
    }

    private static String name(byte[] raw, int off) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 16 && raw[off + k] != 0; k++) {
            sb.append((char) (raw[off + k] & 0xFF));
        }
        return sb.toString().trim();
    }

    /** Sección 0 descomprimida, o null si el archivo no tiene el formato de la base de datos. */
    private static byte[] unpack(byte[] e) {
        try {
            if (e.length < 0x40 || e[0] != 0 || e[1] != 6 || e[2] != 0 || e[3] != 0) {
                return null;
            }
            int n = Bytes.u32i(e, 0x20);
            if (n < 1 || n > 64 || Bytes.u32(e, 0x24) != 8) {
                return null;
            }
            int sec = 0x20 + Bytes.u32i(e, 0x28);
            if (Bytes.u32(e, sec) != 0x00010000L) {
                return null;
            }
            int comp = Bytes.u32i(e, sec + 4);
            int raw = Bytes.u32i(e, sec + 8);
            if (raw % RECORD != 0 || raw == 0 || raw > 4 * 1024 * 1024 || sec + 0x20 + comp > e.length) {
                return null;
            }
            Inflater inf = new Inflater();
            try {
                inf.setInput(e, sec + 0x20, comp);
                byte[] out = new byte[raw];
                int got = 0;
                while (got < raw && !inf.finished()) {
                    int r = inf.inflate(out, got, raw - got);
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) {
                        break;
                    }
                    got += r;
                }
                return got == raw ? out : null;
            } finally {
                inf.end();
            }
        } catch (ConversionException | DataFormatException ex) {
            return null;
        }
    }

    /** Nombre apto para archivo: letras, números, espacio, punto, guion y apóstrofo. */
    public static String safeName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9 .'\\-]", "").replaceAll("\\s+", " ").trim();
        return s.toUpperCase(Locale.ROOT);
    }
}
