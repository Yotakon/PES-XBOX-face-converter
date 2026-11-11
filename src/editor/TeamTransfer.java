package editor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Exporta e importa un equipo (plantilla, dorsales y formación) a un archivo .pes5eq.
 *
 * Índices de equipo (los mismos que usa la lista de la pestaña Transfer):
 *   0-63    selecciones (23 jugadores, con formación)
 *   74-211  clubes (32 jugadores, con formación)
 * Las demás plantillas (clásicos, ML, tienda...) no tienen bloque de formación y no se exportan.
 */
public class TeamTransfer
{
  static final byte[] MAGIC = { 'P', 'E', 'S', '5', 'E', 'Q' };
  static final int VERSION = 1;

  static final int FORM_BASE = 676744;  // 670512 + 6232, igual que FormPanel / FormFixer
  static final int FORM_SIZE = 628;
  static final int CLUB_NAME_SIZE = 80;  // nombre + abreviatura (Clubs.importNames)
  static final int PLAYER_SIZE = 124;

  public static boolean hasFormation(int team)
  {
    return (team >= 0 && team < 64) || (team >= 74 && team < 212);
  }

  public static boolean isClub(int team)
  {
    return team >= 74 && team < 212;
  }

  public static int slots(int team)
  {
    return team < 74 ? 23 : 32;
  }

  static int idsAdr(int team)
  {
    return team < 74 ? 664054 + team * 23 * 2 : 667458 + (team - 74) * 32 * 2;
  }

  static int numsAdr(int team)
  {
    return team < 74 ? 657712 + team * 23 : 659414 + (team - 74) * 32;
  }

  static int formAdr(int team)
  {
    return FORM_BASE + FORM_SIZE * (team < 64 ? team : team - 10);
  }

  static int clubAdr(int team)
  {
    return Clubs.startAdr + Clubs.size * (team - 74);
  }

  static int playerAdr(int id)
  {
    return id > 4999 ? 14044 + (id - 32768) * PLAYER_SIZE : 36872 + id * PLAYER_SIZE;
  }

  public static String teamName(OptionFile of, int team)
  {
    if (team < 57) {
      return Stat.nation[team];
    }
    if (team < 74) {
      return PES4Utils.extraSquad[team - 57];
    }
    return Clubs.getName(of, team - 74);
  }

  /** Lista de equipos exportables, con su índice al principio: "74 - Nombre". */
  public static String[] exportableTeams(OptionFile of)
  {
    java.util.List<String> l = new java.util.ArrayList<String>();
    for (int t = 0; t < 212; t++) {
      if (hasFormation(t)) {
        l.add(t + " - " + teamName(of, t));
      }
    }
    return l.toArray(new String[l.size()]);
  }

  public static int indexFromLabel(String label)
  {
    return Integer.parseInt(label.substring(0, label.indexOf(' ')));
  }

  public static void export(OptionFile of, int team, File file) throws IOException
  {
    if (!hasFormation(team)) {
      throw new IOException("Ese equipo no tiene formación propia y no se puede exportar");
    }
    int n = slots(team);
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
    try
    {
      out.write(MAGIC);
      out.writeByte(VERSION);
      out.writeShort(team);
      out.writeByte(n);
      out.writeUTF(teamName(of, team));
      out.write(of.data, idsAdr(team), n * 2);
      out.write(of.data, numsAdr(team), n);
      out.write(of.data, formAdr(team), FORM_SIZE);
      if (isClub(team))
      {
        out.writeByte(1);
        out.write(of.data, clubAdr(team), CLUB_NAME_SIZE);
      }
      else
      {
        out.writeByte(0);
      }
      int[] ids = squadIds(of, team);
      int count = 0;
      for (int i = 0; i < ids.length; i++) {
        if (FaceStats.validPlayerId(ids[i])) {
          count++;
        }
      }
      out.writeShort(count);
      for (int i = 0; i < ids.length; i++)
      {
        if (FaceStats.validPlayerId(ids[i]))
        {
          out.writeInt(ids[i]);
          out.write(of.data, playerAdr(ids[i]), PLAYER_SIZE);
        }
      }
    }
    finally
    {
      out.close();
    }
  }

  public static int[] squadIds(OptionFile of, int team)
  {
    int n = slots(team);
    int a = idsAdr(team);
    int[] ids = new int[n];
    for (int i = 0; i < n; i++) {
      ids[i] = of.toInt(of.data[a + i * 2 + 1]) << 8 | of.toInt(of.data[a + i * 2]);
    }
    return ids;
  }

  /** Contenido de un archivo .pes5eq ya leído. */
  public static class TeamFile
  {
    public int team;
    public int slots;
    public String name;
    byte[] ids, nums, form, clubName;
    int[] playerIds;
    byte[][] players;

    public int playerCount()
    {
      return playerIds.length;
    }

    public boolean hasClubName()
    {
      return clubName != null;
    }
  }

  public static TeamFile read(File file) throws IOException
  {
    DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
    try
    {
      byte[] m = new byte[MAGIC.length];
      in.readFully(m);
      for (int i = 0; i < m.length; i++) {
        if (m[i] != MAGIC[i]) {
          throw new IOException("No es un archivo de equipo .pes5eq");
        }
      }
      int v = in.readUnsignedByte();
      if (v != VERSION) {
        throw new IOException("Versión de archivo no soportada: " + v);
      }
      TeamFile t = new TeamFile();
      t.team = in.readUnsignedShort();
      t.slots = in.readUnsignedByte();
      if (t.slots != 23 && t.slots != 32) {
        throw new IOException("Número de jugadores no válido: " + t.slots);
      }
      t.name = in.readUTF();
      t.ids = new byte[t.slots * 2];
      in.readFully(t.ids);
      t.nums = new byte[t.slots];
      in.readFully(t.nums);
      t.form = new byte[FORM_SIZE];
      in.readFully(t.form);
      if (in.readUnsignedByte() == 1)
      {
        t.clubName = new byte[CLUB_NAME_SIZE];
        in.readFully(t.clubName);
      }
      int count = in.readUnsignedShort();
      if (count > t.slots) {
        throw new IOException("Archivo dañado: demasiados jugadores");
      }
      t.playerIds = new int[count];
      t.players = new byte[count][PLAYER_SIZE];
      for (int i = 0; i < count; i++)
      {
        t.playerIds[i] = in.readInt();
        if (!FaceStats.validPlayerId(t.playerIds[i])) {
          throw new IOException("Archivo dañado: ID de jugador " + t.playerIds[i]);
        }
        in.readFully(t.players[i]);
      }
      return t;
    }
    finally
    {
      in.close();
    }
  }

  /**
   * Escribe el equipo leído en el equipo destino.
   * Plantilla (IDs), dorsales y formación siempre. Opcionalmente los datos de los jugadores
   * (sobrescribe a los jugadores con esos mismos IDs) y el nombre del club.
   */
  public static void apply(OptionFile of, TeamFile t, int target, boolean players, boolean clubName) throws IOException
  {
    if (!hasFormation(target)) {
      throw new IOException("El equipo destino no tiene formación propia");
    }
    if (slots(target) != t.slots) {
      throw new IOException("El equipo del archivo tiene " + t.slots + " jugadores y el destino " + slots(target)
          + ". Solo se puede importar selección en selección y club en club.");
    }
    System.arraycopy(t.ids, 0, of.data, idsAdr(target), t.ids.length);
    System.arraycopy(t.nums, 0, of.data, numsAdr(target), t.nums.length);
    System.arraycopy(t.form, 0, of.data, formAdr(target), FORM_SIZE);
    if (players) {
      for (int i = 0; i < t.playerIds.length; i++) {
        System.arraycopy(t.players[i], 0, of.data, playerAdr(t.playerIds[i]), PLAYER_SIZE);
      }
    }
    if (clubName && t.clubName != null && isClub(target)) {
      System.arraycopy(t.clubName, 0, of.data, clubAdr(target), CLUB_NAME_SIZE);
    }
    FormFixer.fixForm(of, target, true);
  }
}
