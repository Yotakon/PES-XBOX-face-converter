package com.yotakon.pesport;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.yotakon.pesport.core.ConversionException;
import com.yotakon.pesport.core.Converter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla principal: el usuario añade .bin de caras/pelos de PES 2010 PS2, elige el color de
 * cada pelo y la carpeta de salida, y la app guarda los .bin convertidos para PES 5 Xbox.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_OPEN = 1;
    private static final int REQ_FOLDER = 2;
    private static final int MAX_BIN_SIZE = 8 * 1024 * 1024;
    private static final int DEFAULT_HAIR = 0x281E18;
    private static final String PREFS = "pesport";

    /** Un archivo de la lista. */
    private static final class Item {
        String name;
        byte[] data;
        Converter.Info info;
        String error;
        int rgb = DEFAULT_HAIR;
        double brightness = 1.0;
        String status = "";
        boolean ok;
        boolean failed;
    }

    private final List<Item> items = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView empty;
    private TextView folderText;
    private ExtendedFloatingActionButton convertButton;
    private ExtendedFloatingActionButton addButton;
    private LinearProgressIndicator progress;
    private Uri outputTree;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        folderText = findViewById(R.id.folder);
        convertButton = findViewById(R.id.convert);
        addButton = findViewById(R.id.add);
        progress = findViewById(R.id.progress);

        String tree = getSharedPreferences(PREFS, MODE_PRIVATE).getString("outputTree", null);
        if (tree != null) {
            outputTree = Uri.parse(tree);
        }

        addButton.setOnClickListener(v -> openFiles());
        convertButton.setOnClickListener(v -> convertAll());
        findViewById(R.id.choose_folder).setOnClickListener(v -> chooseFolder());
        findViewById(R.id.open_faces).setOnClickListener(v -> startActivity(new Intent(this, FacesActivity.class)));
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
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString("outputTree", outputTree.toString()).apply();
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
            it.name = displayName(u);
            try {
                it.data = readAll(u);
                it.info = Converter.inspect(it.data);
                it.status = getString(it.info.kind == Converter.Kind.FACE ? R.string.kind_face : R.string.kind_hair)
                        + " de PES 2010 PS2";
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
                    throw new IOException("es demasiado grande para ser un FACE o un HAIR");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------ conversion

    private void message(int res) {
        new MaterialAlertDialogBuilder(this).setMessage(res).setPositiveButton(R.string.ok, null).show();
    }

    private void convertAll() {
        if (busy) {
            return;
        }
        if (outputTree == null) {
            message(R.string.need_folder);
            return;
        }
        final List<Item> todo = new ArrayList<>();
        for (Item it : items) {
            if (it.error == null && !it.ok) {
                todo.add(it);
            }
        }
        if (todo.isEmpty()) {
            message(R.string.nothing_to_do);
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
                    String saved = save(tree, outputName(it.name), r.data);
                    it.ok = true;
                    it.failed = false;
                    it.status = "Convertido → " + saved;
                    ok++;
                } catch (ConversionException e) {
                    it.failed = true;
                    it.status = "Error: " + e.getMessage();
                    fail++;
                } catch (IOException | RuntimeException e) {
                    it.failed = true;
                    it.status = "Error al guardar: " + e.getMessage();
                    fail++;
                }
                runOnUiThread(this::refresh);
            }
            final String msg = ok + (ok == 1 ? " archivo convertido" : " archivos convertidos")
                    + (fail > 0 ? ", " + fail + " con error" : "");
            runOnUiThread(() -> {
                busy = false;
                refresh();
                Snackbar.make(convertButton, msg, Snackbar.LENGTH_LONG).show();
            });
        });
    }

    static String outputName(String in) {
        String base = in.toLowerCase(Locale.ROOT).endsWith(".bin") ? in.substring(0, in.length() - 4) : in;
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
        return displayName(doc);
    }

    // ------------------------------------------------------------ interfaz

    private void refresh() {
        folderText.setText(outputTree == null ? getString(R.string.output_none) : folderLabel(outputTree));
        convertButton.setEnabled(!busy);
        addButton.setEnabled(!busy);
        convertButton.setText(busy ? R.string.converting : R.string.convert);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (Item it : items) {
            list.addView(row(inf, it));
        }
    }

    private String folderLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 0) {
            return id;
        }
        String vol = id.substring(0, colon);
        String path = id.substring(colon + 1);
        return (vol.equals("primary") ? "Almacenamiento interno" : vol) + "/" + path;
    }

    private View row(LayoutInflater inf, final Item it) {
        View v = inf.inflate(R.layout.item_file, list, false);
        MaterialCardView card = v.findViewById(R.id.card);
        ImageView icon = v.findViewById(R.id.kind_icon);
        TextView name = v.findViewById(R.id.name);
        TextView status = v.findViewById(R.id.status);
        MaterialButton remove = v.findViewById(R.id.remove);
        View colorRow = v.findViewById(R.id.color_row);

        boolean hair = it.info != null && it.info.kind == Converter.Kind.HAIR;
        int containerAttr, onContainerAttr;
        if (it.error != null || it.failed) {
            containerAttr = com.google.android.material.R.attr.colorErrorContainer;
            onContainerAttr = com.google.android.material.R.attr.colorOnErrorContainer;
        } else if (it.ok) {
            containerAttr = com.google.android.material.R.attr.colorPrimaryContainer;
            onContainerAttr = com.google.android.material.R.attr.colorOnPrimaryContainer;
        } else {
            containerAttr = hair ? com.google.android.material.R.attr.colorSecondaryContainer
                    : com.google.android.material.R.attr.colorTertiaryContainer;
            onContainerAttr = hair ? com.google.android.material.R.attr.colorOnSecondaryContainer
                    : com.google.android.material.R.attr.colorOnTertiaryContainer;
        }
        icon.setImageResource(hair ? R.drawable.ic_palette : R.drawable.ic_face);
        icon.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(icon, containerAttr)));
        icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(icon, onContainerAttr)));
        if (it.ok) {
            card.setStrokeColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary));
        }

        name.setText(it.name);
        status.setText(it.error != null ? "No válido: " + it.error : it.status);
        if (it.error != null || it.failed) {
            status.setTextColor(MaterialColors.getColor(status, com.google.android.material.R.attr.colorError));
        }

        boolean needsColor = it.error == null && it.info != null && it.info.needsColor;
        colorRow.setVisibility(needsColor ? View.VISIBLE : View.GONE);
        if (needsColor) {
            View swatch = v.findViewById(R.id.swatch);
            swatch.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | it.rgb));
            MaterialButton color = v.findViewById(R.id.color);
            color.setText(String.format(Locale.getDefault(), "%s · brillo %.2f", ColorDialog.hex(it.rgb), it.brightness));
            color.setEnabled(!busy);
            color.setOnClickListener(b -> ColorDialog.show(this, it.data, it.rgb, it.brightness, (rgb, br) -> {
                it.rgb = rgb;
                it.brightness = br;
                it.ok = false;
                it.failed = false;
                it.status = getString(R.string.kind_hair) + " de PES 2010 PS2";
                refresh();
            }));
        }
        remove.setEnabled(!busy);
        remove.setOnClickListener(b -> {
            items.remove(it);
            refresh();
        });
        return v;
    }
}
