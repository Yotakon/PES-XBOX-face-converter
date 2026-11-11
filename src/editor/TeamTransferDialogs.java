package editor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.io.File;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileFilter;

/** Ventanas para exportar e importar equipos (.pes5eq). */
public class TeamTransferDialogs
{
  public static final String EXT = "pes5eq";

  static final FileFilter FILTER = new FileFilter()
  {
    public boolean accept(File f)
    {
      return f.isDirectory() || f.getName().toLowerCase().endsWith("." + EXT);
    }

    public String getDescription()
    {
      return "Equipo PES5 (*." + EXT + ")";
    }
  };

  private static File lastDir;

  public static void exportTeams(Component parent, OptionFile of)
  {
    String[] teams = TeamTransfer.exportableTeams(of);
    JList list = new JList(teams);
    list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    list.setVisibleRowCount(20);
    JPanel p = new JPanel(new BorderLayout(4, 4));
    p.add(new JLabel("<html>Elige uno o varios equipos (Ctrl o Mayús para varios).<br>"
        + "Se guarda un archivo ." + EXT + " por equipo con plantilla, dorsales,<br>"
        + "formación, estrategia y los datos de sus jugadores.</html>"), BorderLayout.NORTH);
    p.add(new JScrollPane(list), BorderLayout.CENTER);
    if (JOptionPane.showConfirmDialog(parent, p, "Exportar equipos", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
      return;
    }
    List<?> sel = list.getSelectedValuesList();
    if (sel.isEmpty()) {
      return;
    }
    JFileChooser fc = new JFileChooser(lastDir);
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    fc.setDialogTitle("Carpeta donde guardar los equipos");
    if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File dir = fc.getSelectedFile();
    lastDir = dir;
    if (!dir.isDirectory() && !dir.mkdirs())
    {
      JOptionPane.showMessageDialog(parent, "No se pudo crear la carpeta", "Error", JOptionPane.ERROR_MESSAGE);
      return;
    }
    int ok = 0;
    StringBuilder errors = new StringBuilder();
    for (Object o : sel)
    {
      String label = (String) o;
      int team = TeamTransfer.indexFromLabel(label);
      File f = new File(dir, safeName(label) + "." + EXT);
      try
      {
        TeamTransfer.export(of, team, f);
        ok++;
      }
      catch (Exception e)
      {
        errors.append(label).append(": ").append(e.getMessage()).append('\n');
      }
    }
    String msg = "Equipos exportados: " + ok + "\nCarpeta: " + dir.getAbsolutePath();
    if (errors.length() > 0) {
      msg += "\n\nErrores:\n" + errors;
    }
    JOptionPane.showMessageDialog(parent, msg, "Exportar equipos", JOptionPane.INFORMATION_MESSAGE);
  }

  /** @return true si se importó algo (para refrescar la interfaz). */
  public static boolean importTeam(Component parent, OptionFile of)
  {
    JFileChooser fc = new JFileChooser(lastDir);
    fc.setAcceptAllFileFilterUsed(false);
    fc.addChoosableFileFilter(FILTER);
    fc.setDialogTitle("Importar equipo");
    if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
      return false;
    }
    File f = fc.getSelectedFile();
    lastDir = f.getParentFile();
    TeamTransfer.TeamFile t;
    try
    {
      t = TeamTransfer.read(f);
    }
    catch (Exception e)
    {
      JOptionPane.showMessageDialog(parent, "No se pudo leer el archivo:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
      return false;
    }

    String[] all = TeamTransfer.exportableTeams(of);
    java.util.List<String> compatible = new java.util.ArrayList<String>();
    String preselect = null;
    for (int i = 0; i < all.length; i++)
    {
      int team = TeamTransfer.indexFromLabel(all[i]);
      if (TeamTransfer.slots(team) == t.slots)
      {
        compatible.add(all[i]);
        if (team == t.team) {
          preselect = all[i];
        }
      }
    }
    JComboBox target = new JComboBox(compatible.toArray());
    if (preselect != null) {
      target.setSelectedItem(preselect);
    }
    JCheckBox players = new JCheckBox("Importar también los datos de los jugadores (" + t.playerCount() + ")", true);
    JCheckBox club = new JCheckBox("Importar el nombre y la abreviatura del club", t.hasClubName());
    club.setEnabled(t.hasClubName());

    JPanel p = new JPanel(new GridLayout(0, 1, 2, 2));
    p.add(new JLabel("Archivo: " + t.name + " (" + t.slots + " jugadores)"));
    p.add(new JLabel("Equipo destino (se reemplaza su plantilla y formación):"));
    p.add(target);
    p.add(players);
    p.add(new JLabel("<html><small>Sobrescribe a los jugadores con los mismos IDs en este option file.<br>"
        + "Desmárcalo si los dos option files tienen los mismos jugadores.</small></html>"));
    p.add(club);
    if (JOptionPane.showConfirmDialog(parent, p, "Importar equipo", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
      return false;
    }
    int team = TeamTransfer.indexFromLabel((String) target.getSelectedItem());
    try
    {
      TeamTransfer.apply(of, t, team, players.isSelected(), club.isSelected() && club.isEnabled());
    }
    catch (Exception e)
    {
      JOptionPane.showMessageDialog(parent, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
      return false;
    }
    JOptionPane.showMessageDialog(parent, "Equipo importado en " + target.getSelectedItem()
        + ".\nRecuerda guardar con File > Save.", "Importar equipo", JOptionPane.INFORMATION_MESSAGE);
    return true;
  }

  static String safeName(String s)
  {
    return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
  }
}
