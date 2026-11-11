package editor;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Campos de cara y pelo de un jugador de PES5 / WE9.
 *
 * Posiciones tomadas de PES5_WE9_LE_OF_Team_Editor (moth1995, player_data.py),
 * relativas al inicio de los datos del jugador (registro + 48), igual que la
 * clase Stat. Se pueden cambiar sin recompilar con un archivo "caras.properties"
 * junto al editor, con el formato: clave=posicion,desplazamiento,mascara
 *
 *   tipoCara=60,2,3
 *   numeroCara=53,3,511
 *   piel=43,1,3
 *   pelo=45,0,2047
 */
public class FaceStats
{
  public static final int TYPE_BUILD = 0;
  public static final int TYPE_SPECIAL = 1;
  public static final int TYPE_NORMAL = 2;

  public static final String[] TYPE_NAMES = { "Creada en el editor", "Especial", "Normal (predefinida)", "Desconocido (3)" };

  public static final String CONFIG_FILE = "caras.properties";

  public final Stat faceType;
  public final Stat face;
  public final Stat skin;
  public final Stat hair;

  /** Texto que indica de dónde salieron las posiciones (para mostrarlo al usuario). */
  public final String source;

  public FaceStats(OptionFile of)
  {
    Properties p = new Properties();
    String src = "posiciones por defecto (PES5_WE9_LE_OF_Team_Editor)";
    File f = new File(CONFIG_FILE);
    if (f.isFile())
    {
      try
      {
        InputStream in = new FileInputStream(f);
        try
        {
          p.load(in);
          src = "posiciones de " + f.getAbsolutePath();
        }
        finally
        {
          in.close();
        }
      }
      catch (Exception e)
      {
        System.err.println("No se pudo leer " + CONFIG_FILE + ": " + e);
      }
    }
    this.source = src;
    this.faceType = make(of, p, "tipoCara", 60, 2, 3, "Tipo de cara");
    this.face = make(of, p, "numeroCara", 53, 3, 0x1FF, "Número de cara");
    this.skin = make(of, p, "piel", 43, 1, 3, "Color de piel");
    this.hair = make(of, p, "pelo", 45, 0, 0x7FF, "Pelo");
  }

  private static Stat make(OptionFile of, Properties p, String key, int off, int shift, int mask, String name)
  {
    String v = p.getProperty(key);
    if (v != null)
    {
      String[] parts = v.split(",");
      if (parts.length == 3)
      {
        try
        {
          int o = Integer.parseInt(parts[0].trim());
          int s = Integer.parseInt(parts[1].trim());
          int m = Integer.parseInt(parts[2].trim());
          // Stat lee 2 bytes: el campo tiene que caber en 16 bits y la posición en el registro.
          if (o >= 1 && o <= 75 && s >= 0 && m > 0 && ((long) m << s) <= 0xFFFF)
          {
            off = o;
            shift = s;
            mask = m;
          }
          else
          {
            System.err.println(CONFIG_FILE + ": valor fuera de rango para " + key + ", uso el valor por defecto");
          }
        }
        catch (NumberFormatException e)
        {
          System.err.println(CONFIG_FILE + ": valor no numérico para " + key + ", uso el valor por defecto");
        }
      }
    }
    return new Stat(of, 0, off, shift, mask, name);
  }

  public static String typeName(int t)
  {
    if (t >= 0 && t < TYPE_NAMES.length) {
      return TYPE_NAMES[t];
    }
    return String.valueOf(t);
  }

  /** IDs de todos los jugadores del option file: 1..4999 y los editados 32768..32951. */
  public static int[] allPlayerIds()
  {
    int[] ids = new int[4999 + 184];
    int n = 0;
    for (int i = 1; i < 5000; i++) {
      ids[n++] = i;
    }
    for (int i = 32768; i < 32952; i++) {
      ids[n++] = i;
    }
    return ids;
  }

  public static boolean validPlayerId(int id)
  {
    return (id >= 1 && id < 5000) || (id >= 32768 && id < 32952);
  }
}
