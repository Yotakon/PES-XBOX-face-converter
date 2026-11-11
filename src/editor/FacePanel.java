package editor;

import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Panel "Cara y pelo" del diálogo de jugador.
 * El número de cara se muestra como valor guardado + 1 (igual que en el juego y en COFPES).
 */
public class FacePanel
  extends JPanel
{
  private final FaceStats faces;
  final JComboBox typeBox;
  final JSpinner faceSpin;
  final JSpinner skinSpin;
  final JSpinner hairSpin;

  public FacePanel(FaceStats faces)
  {
    super(new GridLayout(4, 2, 4, 2));
    this.faces = faces;
    setBorder(BorderFactory.createTitledBorder("Cara y pelo"));
    typeBox = new JComboBox(new String[] { FaceStats.TYPE_NAMES[0], FaceStats.TYPE_NAMES[1], FaceStats.TYPE_NAMES[2] });
    faceSpin = new JSpinner(new SpinnerNumberModel(1, 1, faces.face.mask + 1, 1));
    skinSpin = new JSpinner(new SpinnerNumberModel(1, 1, faces.skin.mask + 1, 1));
    hairSpin = new JSpinner(new SpinnerNumberModel(0, 0, faces.hair.mask, 1));
    // Sin separador de miles (mostraría "1,030" o "1.030")
    faceSpin.setEditor(new JSpinner.NumberEditor(faceSpin, "#"));
    skinSpin.setEditor(new JSpinner.NumberEditor(skinSpin, "#"));
    hairSpin.setEditor(new JSpinner.NumberEditor(hairSpin, "#"));
    add(new JLabel("Tipo de cara"));
    add(typeBox);
    add(new JLabel("Número de cara"));
    add(faceSpin);
    add(new JLabel("Color de piel"));
    add(skinSpin);
    add(new JLabel("ID de pelo"));
    add(hairSpin);
    setToolTipText("Cara normal + número = relinkeo. " + faces.source);
  }

  // Valores leídos al abrir el jugador. Solo se escribe lo que el usuario cambió,
  // para no tocar bits que el editor no entiende (por ejemplo un tipo de cara 3).
  private int oldType, oldFace, oldSkin, oldHair;

  public void load(int player)
  {
    oldType = faces.faceType.getValue(player);
    oldFace = faces.face.getValue(player);
    oldSkin = faces.skin.getValue(player);
    oldHair = faces.hair.getValue(player);
    typeBox.setSelectedIndex(oldType > 2 ? 0 : oldType);
    faceSpin.setValue(Integer.valueOf(oldFace + 1));
    skinSpin.setValue(Integer.valueOf(oldSkin + 1));
    hairSpin.setValue(Integer.valueOf(oldHair));
  }

  private static void commit(JSpinner s)
  {
    try
    {
      s.commitEdit();
    }
    catch (java.text.ParseException e)
    {
      // texto inválido: JSpinner conserva el último valor válido
    }
  }

  public void save(int player)
  {
    commit(faceSpin);
    commit(skinSpin);
    commit(hairSpin);
    int t = typeBox.getSelectedIndex();
    if (t != (oldType > 2 ? 0 : oldType)) {
      faces.faceType.setValue(player, t);
    }
    int f = ((Integer) faceSpin.getValue()).intValue() - 1;
    if (f != oldFace) {
      faces.face.setValue(player, f);
    }
    int s = ((Integer) skinSpin.getValue()).intValue() - 1;
    if (s != oldSkin) {
      faces.skin.setValue(player, s);
    }
    int h = ((Integer) hairSpin.getValue()).intValue();
    if (h != oldHair) {
      faces.hair.setValue(player, h);
    }
  }
}
