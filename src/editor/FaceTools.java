package editor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Operaciones en bloque sobre las caras (sin interfaz gráfica).
 * En los CSV el número de cara va como en el juego (valor guardado + 1) y la piel de 1 a 4.
 */
public class FaceTools
{
  public static class Change
  {
    public final int id;
    public final String name;
    public final int oldType, oldFace, newType, newFace;
    public final String note;

    Change(int id, String name, int oldType, int oldFace, int newType, int newFace, String note)
    {
      this.id = id;
      this.name = name;
      this.oldType = oldType;
      this.oldFace = oldFace;
      this.newType = newType;
      this.newFace = newFace;
      this.note = note;
    }
  }

  /**
   * Pasa a cara normal todos los jugadores con cara especial de la lista.
   * El número de cara nuevo es el antiguo + offset (en valores guardados).
   * Si queda fuera de rango, el jugador no se toca y se anota en el informe.
   */
  public static List<Change> removeSpecial(OptionFile of, FaceStats faces, int[] ids, int offset)
  {
    List<Change> out = new ArrayList<Change>();
    for (int i = 0; i < ids.length; i++)
    {
      int id = ids[i];
      if (!FaceStats.validPlayerId(id) || faces.faceType.getValue(id) != FaceStats.TYPE_SPECIAL) {
        continue;
      }
      int oldFace = faces.face.getValue(id);
      int newFace = oldFace + offset;
      String name = new Player(of, id, 0).name;
      if (newFace < 0 || newFace > faces.face.mask)
      {
        out.add(new Change(id, name, FaceStats.TYPE_SPECIAL, oldFace, FaceStats.TYPE_SPECIAL, oldFace, "sin cambios: número fuera de rango"));
        continue;
      }
      faces.faceType.setValue(id, FaceStats.TYPE_NORMAL);
      faces.face.setValue(id, newFace);
      out.add(new Change(id, name, FaceStats.TYPE_SPECIAL, oldFace, FaceStats.TYPE_NORMAL, newFace, ""));
    }
    return out;
  }

  public static void writeReport(List<Change> changes, File file) throws IOException
  {
    PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
    try
    {
      w.print('\uFEFF'); // BOM para que Excel lea bien los acentos
      w.println("ID;NOMBRE;TIPO ANTES;CARA ANTES;TIPO DESPUES;CARA DESPUES;NOTA");
      for (Change c : changes) {
        w.println(c.id + ";" + csv(c.name) + ";" + c.oldType + ";" + (c.oldFace + 1) + ";" + c.newType + ";" + (c.newFace + 1) + ";" + c.note);
      }
    }
    finally
    {
      w.close();
    }
  }

  /** Exporta ID, nombre, tipo de cara, número de cara, piel y pelo de todos los jugadores. */
  public static int exportCsv(OptionFile of, FaceStats faces, File file) throws IOException
  {
    int[] ids = FaceStats.allPlayerIds();
    PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
    int n = 0;
    try
    {
      w.print('\uFEFF');
      w.println("ID;NOMBRE;TIPO CARA;NUMERO CARA;PIEL;PELO;TIPO (texto)");
      for (int i = 0; i < ids.length; i++)
      {
        int id = ids[i];
        int t = faces.faceType.getValue(id);
        w.println(id + ";" + csv(new Player(of, id, 0).name) + ";" + t + ";" + (faces.face.getValue(id) + 1) + ";"
            + (faces.skin.getValue(id) + 1) + ";" + faces.hair.getValue(id) + ";" + FaceStats.typeName(t));
        n++;
      }
    }
    finally
    {
      w.close();
    }
    return n;
  }

  public static class ImportResult
  {
    public int changedPlayers;
    public final List<String> errors = new ArrayList<String>();
  }

  /**
   * Importa un CSV con columna ID y cualquiera de: TIPO CARA, NUMERO CARA, PIEL, PELO.
   * Separador ';' o ','. Las celdas vacías no cambian nada.
   */
  public static ImportResult importCsv(OptionFile of, FaceStats faces, File file) throws IOException
  {
    ImportResult r = new ImportResult();
    BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    try
    {
      String header = in.readLine();
      if (header == null)
      {
        r.errors.add("El archivo está vacío");
        return r;
      }
      if (header.length() > 0 && header.charAt(0) == '\uFEFF') {
        header = header.substring(1);
      }
      String sep = header.indexOf(';') >= 0 ? ";" : ",";
      String[] cols = header.split(sep, -1);
      int cId = find(cols, "ID");
      int cType = find(cols, "TIPO CARA");
      int cFace = find(cols, "NUMERO CARA");
      int cSkin = find(cols, "PIEL");
      int cHair = find(cols, "PELO");
      if (cId < 0)
      {
        r.errors.add("Falta la columna ID");
        return r;
      }
      String line;
      int lineNo = 1;
      while ((line = in.readLine()) != null)
      {
        lineNo++;
        if (line.trim().length() == 0) {
          continue;
        }
        String[] v = splitCsv(line, sep.charAt(0));
        try
        {
          int id = Integer.parseInt(cell(v, cId));
          if (!FaceStats.validPlayerId(id))
          {
            r.errors.add("Línea " + lineNo + ": ID " + id + " no existe");
            continue;
          }
          boolean changed = false;
          changed |= apply(faces.faceType, id, cell(v, cType), 0, 2, 0, r, lineNo, "TIPO CARA");
          changed |= apply(faces.face, id, cell(v, cFace), 1, faces.face.mask + 1, -1, r, lineNo, "NUMERO CARA");
          changed |= apply(faces.skin, id, cell(v, cSkin), 1, faces.skin.mask + 1, -1, r, lineNo, "PIEL");
          changed |= apply(faces.hair, id, cell(v, cHair), 0, faces.hair.mask, 0, r, lineNo, "PELO");
          if (changed) {
            r.changedPlayers++;
          }
        }
        catch (NumberFormatException e)
        {
          r.errors.add("Línea " + lineNo + ": ID no válido");
        }
      }
    }
    finally
    {
      in.close();
    }
    return r;
  }

  private static boolean apply(Stat st, int id, String val, int min, int max, int adjust, ImportResult r, int lineNo, String col)
  {
    if (val == null || val.length() == 0) {
      return false;
    }
    int x;
    try
    {
      x = Integer.parseInt(val);
    }
    catch (NumberFormatException e)
    {
      r.errors.add("Línea " + lineNo + ": " + col + " no es un número");
      return false;
    }
    if (x < min || x > max)
    {
      r.errors.add("Línea " + lineNo + ": " + col + " fuera de rango (" + min + "-" + max + ")");
      return false;
    }
    int raw = x + adjust;
    if (st.getValue(id) == raw) {
      return false;
    }
    st.setValue(id, raw);
    return true;
  }

  private static int find(String[] cols, String name)
  {
    for (int i = 0; i < cols.length; i++) {
      if (cols[i].trim().replace("\"", "").equalsIgnoreCase(name)) {
        return i;
      }
    }
    return -1;
  }

  private static String cell(String[] v, int i)
  {
    if (i < 0 || i >= v.length) {
      return null;
    }
    return v[i].trim();
  }

  /** Divide una línea CSV respetando comillas dobles. */
  static String[] splitCsv(String line, char sep)
  {
    List<String> out = new ArrayList<String>();
    StringBuilder cur = new StringBuilder();
    boolean q = false;
    for (int i = 0; i < line.length(); i++)
    {
      char c = line.charAt(i);
      if (q)
      {
        if (c == '"')
        {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"')
          {
            cur.append('"');
            i++;
          }
          else
          {
            q = false;
          }
        }
        else
        {
          cur.append(c);
        }
      }
      else if (c == '"')
      {
        q = true;
      }
      else if (c == sep)
      {
        out.add(cur.toString());
        cur.setLength(0);
      }
      else
      {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out.toArray(new String[out.size()]);
  }

  static String csv(String s)
  {
    if (s.indexOf(';') >= 0 || s.indexOf(',') >= 0 || s.indexOf('"') >= 0) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }
}
