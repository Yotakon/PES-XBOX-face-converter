package com.yotakon.pesport;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.yotakon.pesport.core.ConversionException;
import com.yotakon.pesport.core.Converter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla principal: el usuario añade .bin de caras/pelos de PES 2010 PS2, elige el color de
 * cada pelo y la carpeta de salida, y la app guarda los .bin convertidos para PES 5 Xbox.
 */
public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final int REQ_FOLDER = 2;
    private static final int MAX_BIN_SIZE = 8 * 1024 * 1024;
    private static final int DEFAULT_HAIR = 0x281E18;
    private static final String PREFS = "pesport";

    /** Un archivo de la lista. */
    private static final class Item {
        Uri uri;
        String name;
        byte[] data;
        Converter.Info info;
        String error;
        int rgb = DEFAULT_HAIR;
        double brightness = 1.0;
        String status = "";
        boolean ok;
    }

    private final List<Item> items = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView folderText;
    private Button convertButton;
    private Uri outputTree;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String tree = prefs.getString("outputTree", null);
        if (tree != null) {
            outputTree = Uri.parse(tree);
        }

        int pad = ColorDialog.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Caras y pelos: PES 2010 PS2 → PES 5 Xbox");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("1. Añade los .bin de cara o pelo extraídos del 0_text.afs de PES 2010.\n"
                + "2. Elige el color de cada pelo.\n"
                + "3. Elige la carpeta de salida y pulsa Convertir.\n"
                + "Después inserta los .bin convertidos en el 0_text.afs de PES 5 con tu herramienta AFS.");
        help.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(help);

        Button add = new Button(this);
        add.setText("Añadir archivos .bin");
        add.setOnClickListener(v -> openFiles());
        root.addView(add);

        Button folder = new Button(this);
        folder.setText("Elegir carpeta de salida");
        folder.setOnClickListener(v -> chooseFolder());
        root.addView(folder);

        folderText = new TextView(this);
        root.addView(folderText);

        convertButton = new Button(this);
        convertButton.setText("Convertir");
        convertButton.setOnClickListener(v -> convertAll());
        root.addView(convertButton);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------ seleccion de archivos

    private void openFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_OPEN);
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) {
            return;
        }
        if (req == REQ_FOLDER && data.getData() != null) {
            outputTree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(outputTree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // sin permiso persistente: sigue valiendo durante esta sesion
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("outputTree", outputTree.toString()).apply();
            refresh();
        } else if (req == REQ_OPEN) {
            final List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                    uris.add(data.getClipData().getItemAt(k).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            worker.execute(() -> loadFiles(uris));
        }
    }

    private void loadFiles(List<Uri> uris) {
        final List<Item> loaded = new ArrayList<>();
        for (Uri u : uris) {
            Item it = new Item();
            it.uri = u;
            it.name = displayName(u);
            try {
                it.data = readAll(u);
                it.info = Converter.inspect(it.data);
                it.status = it.info.kind == Converter.Kind.FACE ? "Cara" : "Pelo";
            } catch (ConversionException e) {
                it.error = e.getMessage();
            } catch (IOException e) {
                it.error = "No se pudo leer: " + e.getMessage();
            }
            loaded.add(it);
        }
        runOnUiThread(() -> {
            items.addAll(loaded);
            refresh();
        });
    }

    private String displayName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // algunos proveedores no dan nombre
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String s = u.getLastPathSegment();
        return s != null ? s : "archivo.bin";
    }

    private byte[] readAll(Uri u) throws IOException {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) {
            throw new IOException("no se pudo abrir");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BIN_SIZE) {
                    throw new IOException("el archivo es demasiado grande para ser una cara o un pelo");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------ conversion

    private void convertAll() {
        if (busy) {
            return;
        }
        if (outputTree == null) {
            new AlertDialog.Builder(this).setMessage("Primero elige la carpeta de salida.")
                    .setPositiveButton("Aceptar", null).show();
            return;
        }
        final List<Item> todo = new ArrayList<>();
        for (Item it : items) {
            if (it.error == null && !it.ok) {
                todo.add(it);
            }
        }
        if (todo.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No hay archivos pendientes de convertir.")
                    .setPositiveButton("Aceptar", null).show();
            return;
        }
        busy = true;
        refresh();
        final Uri tree = outputTree;
        worker.execute(() -> {
            int ok = 0, fail = 0;
            for (final Item it : todo) {
                try {
                    Converter.Result r = Converter.convert(it.data, it.info.needsColor ? it.rgb : null, it.brightness);
                    String outName = outputName(it.name);
                    String saved = save(tree, outName, r.data);
                    it.ok = true;
                    it.status = (r.kind == Converter.Kind.FACE ? "Cara" : "Pelo") + " convertida → " + saved;
                    ok++;
                } catch (ConversionException e) {
                    it.status = "Error: " + e.getMessage();
                    fail++;
                } catch (IOException | RuntimeException e) {
                    it.status = "Error al guardar: " + e.getMessage();
                    fail++;
                }
                runOnUiThread(this::refresh);
            }
            final String msg = ok + " convertidos" + (fail > 0 ? ", " + fail + " con error" : "") + ".";
            runOnUiThread(() -> {
                busy = false;
                refresh();
                new AlertDialog.Builder(this).setTitle("Terminado").setMessage(msg)
                        .setPositiveButton("Aceptar", null).show();
            });
        });
    }

    static String outputName(String in) {
        String base = in.toLowerCase().endsWith(".bin") ? in.substring(0, in.length() - 4) : in;
        return base + " (PES5 Xbox).bin";
    }

    private String save(Uri tree, String name, byte[] data) throws IOException {
        ContentResolver cr = getContentResolver();
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri doc;
        try {
            doc = DocumentsContract.createDocument(cr, dir, "application/octet-stream", name);
        } catch (Exception e) {
            throw new IOException("no se pudo crear el archivo en la carpeta (" + e.getMessage() + ")");
        }
        if (doc == null) {
            throw new IOException("no se pudo crear el archivo en la carpeta");
        }
        OutputStream out = cr.openOutputStream(doc, "w");
        if (out == null) {
            throw new IOException("no se pudo escribir");
        }
        try {
            out.write(data);
        } finally {
            out.close();
        }
        String saved = displayName(doc);
        return saved != null ? saved : name;
    }

    // ------------------------------------------------------------ interfaz

    private void refresh() {
        folderText.setText(outputTree == null ? "Carpeta de salida: (sin elegir)"
                : "Carpeta de salida: " + folderLabel(outputTree));
        convertButton.setEnabled(!busy);
        convertButton.setText(busy ? "Convirtiendo…" : "Convertir");
        list.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("\nNo hay archivos. Pulsa «Añadir archivos .bin».");
            list.addView(empty);
        }
        for (final Item it : items) {
            list.addView(row(it));
        }
    }

    private String folderLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) + " (" + id.substring(0, colon) + ")" : id;
    }

    private View row(final Item it) {
        int pad = ColorDialog.dp(this, 8);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        box.setLayoutParams(lp);
        box.setBackgroundColor(it.error != null ? 0xFFFDE0E0 : it.ok ? 0xFFE0F5E0 : 0xFFF2F2F2);

        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(name);

        TextView status = new TextView(this);
        status.setText(it.error != null ? "No válido: " + it.error : it.status);
        if (it.error != null) {
            status.setTextColor(Color.rgb(160, 0, 0));
        }
        box.addView(status);

        LinearLayout actions = new LinearLayout(this);
        if (it.error == null && it.info != null && it.info.needsColor) {
            ImageView sw = new ImageView(this);
            sw.setBackgroundColor(0xFF000000 | it.rgb);
            int s = ColorDialog.dp(this, 32);
            actions.addView(sw, new LinearLayout.LayoutParams(s, s));
            Button color = new Button(this);
            color.setText(String.format("Color #%06X · brillo %.2f", it.rgb, it.brightness));
            color.setAllCaps(false);
            color.setEnabled(!busy);
            color.setOnClickListener(v -> ColorDialog.show(this, it.data, it.rgb, it.brightness, (rgb, b) -> {
                it.rgb = rgb;
                it.brightness = b;
                it.ok = false;
                it.status = "Pelo";
                refresh();
            }));
            actions.addView(color);
        }
        Button remove = new Button(this);
        remove.setText("Quitar");
        remove.setEnabled(!busy);
        remove.setOnClickListener(v -> {
            items.remove(it);
            refresh();
        });
        actions.addView(remove);
        box.addView(actions);
        return box;
    }
}
