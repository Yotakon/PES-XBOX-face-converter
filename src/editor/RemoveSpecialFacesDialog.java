package editor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Herramienta "Quitar caras especiales": pasa los jugadores con cara especial a cara normal
 * (número de cara = número actual + desplazamiento). Por defecto guarda el resultado en un
 * option file aparte y deja intacto el que está abierto.
 */
public class RemoveSpecialFacesDialog
  extends JDialog
{
  /** Guarda el option file abierto en el archivo indicado. Lo implementa Editor. */
  public interface Saver
  {
    boolean saveCopy(File file);
    File suggestedCopy();
  }

  private final OptionFile of;
  private final Saver saver;
  private final Runnable refresh;
  private final JRadioButton allRadio = new JRadioButton("Todos los jugadores", true);
  private final JRadioButton teamRadio = new JRadioButton("Solo los jugadores de este equipo:");
  private final JComboBox teamBox = new JComboBox();
  private final JSpinner offsetSpin = new JSpinner(new SpinnerNumberModel(0, -511, 511, 1));
  private final JCheckBox separateBox = new JCheckBox("Guardar en un option file aparte (el archivo abierto no cambia)", true);
  private final JCheckBox reportBox = new JCheckBox("Guardar informe CSV con los cambios (sirve para deshacerlos)", true);

  public RemoveSpecialFacesDialog(Frame owner, OptionFile of, Saver saver, Runnable refresh)
  {
    super(owner, "Quitar caras especiales", true);
    this.of = of;
    this.saver = saver;
    this.refresh = refresh;

    ButtonGroup g = new ButtonGroup();
    g.add(allRadio);
    g.add(teamRadio);

    JPanel scope = new JPanel(new GridLayout(3, 1));
    scope.setBorder(BorderFactory.createTitledBorder("Jugadores"));
    scope.add(allRadio);
    scope.add(teamRadio);
    scope.add(teamBox);

    JPanel num = new JPanel(new BorderLayout(4, 4));
    num.setBorder(BorderFactory.createTitledBorder("Número de cara normal"));
    num.add(new JLabel("<html>Cada jugador pasa a <b>cara normal</b> con su número actual más este desplazamiento.<br>"
        + "Con 0 conserva el mismo número. Si en tu juego las caras especiales están en otra<br>"
        + "posición de la tabla de caras normales, pon aquí la diferencia (relinkeo).</html>"), BorderLayout.NORTH);
    JPanel numRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
    numRow.add(new JLabel("Desplazamiento:"));
    numRow.add(offsetSpin);
    num.add(numRow, BorderLayout.CENTER);

    JPanel opts = new JPanel(new GridLayout(2, 1));
    opts.setBorder(BorderFactory.createTitledBorder("Resultado"));
    opts.add(separateBox);
    opts.add(reportBox);

    JPanel center = new JPanel(new BorderLayout());
    center.add(scope, BorderLayout.NORTH);
    center.add(num, BorderLayout.CENTER);
    center.add(opts, BorderLayout.SOUTH);

    JButton ok = new JButton("Aplicar");
    ok.addActionListener(new ActionListener()
    {
      public void actionPerformed(ActionEvent e)
      {
        run();
      }
    });
    JButton cancel = new JButton("Cancelar");
    cancel.addActionListener(new ActionListener()
    {
      public void actionPerformed(ActionEvent e)
      {
        setVisible(false);
      }
    });
    JPanel buttons = new JPanel();
    buttons.add(ok);
    buttons.add(cancel);

    getContentPane().add(center, BorderLayout.CENTER);
    getContentPane().add(buttons, BorderLayout.SOUTH);
    pack();
    setLocationRelativeTo(owner);
  }

  public void showDialog()
  {
    teamBox.setModel(new javax.swing.DefaultComboBoxModel(TeamTransfer.exportableTeams(of)));
    setVisible(true);
  }

  private void run()
  {
    try
    {
      offsetSpin.commitEdit();
    }
    catch (java.text.ParseException e)
    {
      // se usa el último valor válido
    }
    int offset = ((Integer) offsetSpin.getValue()).intValue();
    int[] ids;
    String scopeText;
    if (allRadio.isSelected())
    {
      ids = FaceStats.allPlayerIds();
      scopeText = "todos los jugadores";
    }
    else
    {
      String label = (String) teamBox.getSelectedItem();
      int team = TeamTransfer.indexFromLabel(label);
      ids = TeamTransfer.squadIds(of, team);
      scopeText = "los jugadores de " + label;
    }

    File target = null;
    if (separateBox.isSelected())
    {
      JFileChooser fc = new JFileChooser();
      fc.setDialogTitle("Guardar el option file sin caras especiales");
      fc.setSelectedFile(saver.suggestedCopy());
      if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
        return;
      }
      target = fc.getSelectedFile();
      if (target.exists() && JOptionPane.showConfirmDialog(this, target.getName() + " ya existe. ¿Reemplazarlo?",
          "Reemplazar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
        return;
      }
    }
    else if (JOptionPane.showConfirmDialog(this, "Se modificará el option file abierto (" + scopeText
        + ").\nLos cambios se guardan cuando uses File > Save. ¿Continuar?", "Confirmar",
        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
      return;
    }

    FaceStats faces = new FaceStats(of);
    byte[] backup = target != null ? (byte[]) of.data.clone() : null;
    List<FaceTools.Change> changes = FaceTools.removeSpecial(of, faces, ids, offset);
    int changed = 0;
    for (FaceTools.Change c : changes) {
      if (c.newType != c.oldType) {
        changed++;
      }
    }
    int skipped = changes.size() - changed;

    StringBuilder msg = new StringBuilder();
    msg.append("Jugadores con cara especial encontrados: ").append(changes.size()).append('\n');
    msg.append("Pasados a cara normal: ").append(changed).append('\n');
    if (skipped > 0) {
      msg.append("Sin cambios (número fuera de rango): ").append(skipped).append('\n');
    }

    if (target != null)
    {
      boolean ok = changed == 0 || saver.saveCopy(target);
      System.arraycopy(backup, 0, of.data, 0, backup.length);
      if (!ok)
      {
        JOptionPane.showMessageDialog(this, "No se pudo guardar " + target.getName(), "Error", JOptionPane.ERROR_MESSAGE);
        return;
      }
      if (changed > 0) {
        msg.append("\nGuardado en: ").append(target.getAbsolutePath()).append('\n');
      }
    }
    else if (refresh != null)
    {
      refresh.run();
    }

    if (reportBox.isSelected() && !changes.isEmpty())
    {
      File base = target != null ? target : saver.suggestedCopy();
      File report = new File(base.getParentFile(), base.getName() + "_caras_especiales.csv");
      try
      {
        FaceTools.writeReport(changes, report);
        msg.append("Informe: ").append(report.getAbsolutePath());
      }
      catch (Exception e)
      {
        msg.append("No se pudo guardar el informe: ").append(e.getMessage());
      }
    }
    JOptionPane.showMessageDialog(this, msg.toString(), "Quitar caras especiales", JOptionPane.INFORMATION_MESSAGE);
    setVisible(false);
  }
}
