package com.yotakon.pesport;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.yotakon.pesport.core.Afs;
import com.yotakon.pesport.core.Converter;
import com.yotakon.pesport.core.FaceHair;
import com.yotakon.pesport.core.Pairs;
import com.yotakon.pesport.core.PlayerDb;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Caras y pelos del 0_TEXT.AFS de PES 2010 PS2.
 * Pestaña 1: busca el AFS, cuenta FACES y HAIRS, empareja cada FACE con su HAIR y los extrae
 * renombrados. Pestaña 2: abre una carpeta de .bin extraídos y muestra las parejas con previa.
 */
public class FacesActivity extends AppCompatActivity {
    private static final int REQ_AFS = 10;
    private static final int REQ_EXTRACT = 11;
    private static final int REQ_BINS = 12;
    private static final int MAX_BIN_SIZE = 8 * 1024 * 1024;
    /** Color con el que se tiñe la previa del HAIR (en el AFS está en gris). */
    private static final int HAIR_PREVIEW = 0x7A5030;
    private static final String PREFS = "pesport";
    private static final String OUT_DIR = "FACES y HAIRS";

    /** Fila de la tabla Cara | Pelo | Previa | Previa. */
    private static final class Row {
        int faceSlot = Pairs.NONE;
        String faceLabel;
        String hairLabel;
        String faceKey, hairKey;
        Thumbs.Source face, hair;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Thumbs thumbs = new Thumbs();

    // pestaña 1
    private TextView afsName, afsLog, extractFolder;
    private ScrollView logScroll;
    private MaterialButton extractButton;
    private final List<Row> afsRows = new ArrayList<>();
    private PairAdapter afsAdapter;
    private ParcelFileDescriptor afsPfd;
    private Afs afs;
    private List<Integer> faces = new ArrayList<>();
    private List<Integer> hairs = new ArrayList<>();
    private Pairs pairs = new Pairs();
    private TreeMap<Integer, Integer> overrides = new TreeMap<>();
    private String overridesKey;
    private Uri extractTree;

    // pestaña 2
    private TextView binsFolder, binsSummary, prevFaceLabel, prevHairLabel;
    private ImageView prevFace, prevHair;
    private final List<Row> binRows = new ArrayList<>();
    private PairAdapter binsAdapter;
    private Uri binsTree;

    private LinearProgressIndicator progress;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_faces);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);

        final View tabAfs = findViewById(R.id.tab_afs);
        final View tabFolder = findViewById(R.id.tab_folder);
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                tabAfs.setVisibility(tab.getPosition() == 0 ? View.VISIBLE : View.GONE);
                tabFolder.setVisibility(tab.getPosition() == 1 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        afsName = findViewById(R.id.afs_name);
        afsLog = findViewById(R.id.afs_log);
        logScroll = findViewById(R.id.log_scroll);
        extractFolder = findViewById(R.id.extract_folder);
        extractButton = findViewById(R.id.extract);
        findViewById(R.id.pick_afs).setOnClickListener(v -> pickAfs());
        findViewById(R.id.pick_extract).setOnClickListener(v -> pickTree(REQ_EXTRACT));
        extractButton.setOnClickListener(v -> extractAll());
        afsAdapter = new PairAdapter(afsRows, this::pickHairFor);
        setupList(findViewById(R.id.afs_list), afsAdapter);

        binsFolder = findViewById(R.id.bins_folder);
        binsSummary = findViewById(R.id.bins_summary);
        prevFace = findViewById(R.id.prev_face);
        prevHair = findViewById(R.id.prev_hair);
        prevFaceLabel = findViewById(R.id.prev_face_label);
        prevHairLabel = findViewById(R.id.prev_hair_label);
        findViewById(R.id.pick_bins).setOnClickListener(v -> pickTree(REQ_BINS));
        binsAdapter = new PairAdapter(binRows, this::showPreview);
        setupList(findViewById(R.id.bins_list), binsAdapter);

        SharedPreferences p = prefs();
        extractTree = parseUri(p.getString("extractTree", null));
        binsTree = parseUri(p.getString("binsTree", null));
        refreshFolders();
        if (binsTree != null) {
            loadBins(binsTree);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        thumbs.shutdown();
        closeAfs(afsPfd);
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static Uri parseUri(String s) {
        return s == null ? null : Uri.parse(s);
    }

    private void setupList(RecyclerView rv, PairAdapter adapter) {
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rv.setAdapter(adapter);
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        extractButton.setEnabled(!b);
        extractButton.setText(b ? R.string.extracting : R.string.extract);
    }

    // ------------------------------------------------------------ selección de archivos

    private void pickAfs() {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_AFS);
    }

    private void pickTree(int req) {
        if (busy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri u = data.getData();
        if (req == REQ_AFS) {
            openAfs(u);
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // sin permiso persistente: sigue valiendo durante esta sesión
        }
        if (req == REQ_EXTRACT) {
            extractTree = u;
            prefs().edit().putString("extractTree", u.toString()).apply();
        } else if (req == REQ_BINS) {
            binsTree = u;
            prefs().edit().putString("binsTree", u.toString()).apply();
            loadBins(u);
        }
        refreshFolders();
    }

    private void refreshFolders() {
        extractFolder.setText(extractTree == null ? getString(R.string.none_chosen) : folderLabel(extractTree));
        binsFolder.setText(binsTree == null ? getString(R.string.none_chosen) : folderLabel(binsTree));
    }

    static String folderLabel(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 0) {
            return id;
        }
        String vol = id.substring(0, colon);
        return (vol.equals("primary") ? "Almacenamiento interno" : vol) + "/" + id.substring(colon + 1);
    }

    // ------------------------------------------------------------ registro

    private void logClear(String text) {
        afsLog.setText(text);
    }

    private void log(final String line) {
        runOnUiThread(() -> {
            CharSequence cur = afsLog.getText();
            afsLog.setText(cur.length() == 0 ? line : cur + "\n" + line);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ------------------------------------------------------------ pestaña 1: 0_TEXT.AFS

    private void openAfs(final Uri uri) {
        setBusy(true);
        final String name = displayName(uri);
        afsName.setText(name);
        logClear("Leyendo " + name + "…");
        worker.execute(() -> {
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    throw new IOException("no se pudo abrir");
                }
                FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                final long size = in.getChannel().size();
                final Afs a = Afs.open(in.getChannel());
                final List<Integer> f = new ArrayList<>(), h = new ArrayList<>();
                for (int i = 0; i < a.count(); i++) {
                    if (a.size(i) < 32) {
                        continue;
                    }
                    FaceHair.Kind k = FaceHair.classify(a.readHead(i, FaceHair.HEAD_BYTES));
                    if (k == FaceHair.Kind.FACE) {
                        f.add(i);
                    } else if (k == FaceHair.Kind.HAIR) {
                        h.add(i);
                    }
                    if ((i + 1) % 2000 == 0) {
                        log("Revisados " + (i + 1) + " de " + a.count() + "…");
                    }
                }
                final String key = "overrides_" + a.count() + "_" + size;
                List<PlayerDb.Player> players = new ArrayList<>();
                int dbEntry = Pairs.NONE;
                for (int e : PlayerDb.AFS_ENTRIES) {
                    if (e < a.count() && a.size(e) > 0 && a.size(e) < MAX_BIN_SIZE) {
                        players = PlayerDb.read(a.read(e));
                        if (!players.isEmpty()) {
                            dbEntry = e;
                            break;
                        }
                    }
                }
                final Pairs pr = f.isEmpty() || h.isEmpty() ? new Pairs()
                        : Pairs.fromDb(players, f.get(0), h.get(0), new HashSet<>(f), new HashSet<>(h));
                final TreeMap<Integer, Integer> ov = loadOverrides(key, f, h);
                pr.apply(ov);
                final String dbLine = dbEntry == Pairs.NONE
                        ? "Base de datos de jugadores: no encontrada (todas las caras quedan Sin HAIR)"
                        : String.format(Locale.getDefault(), "Base de datos: %d jugadores (archivo %d)", players.size(), dbEntry);
                final ParcelFileDescriptor opened = pfd;
                runOnUiThread(() -> {
                    ParcelFileDescriptor old = afsPfd;
                    afsPfd = opened;
                    afs = a;
                    faces = f;
                    hairs = h;
                    pairs = pr;
                    overrides = ov;
                    overridesKey = key;
                    thumbs.clear();
                    closeAfs(old);
                    buildAfsRows();
                    logClear(String.format(Locale.getDefault(), "Archivos en el AFS: %d\n%s\n%s\n%s\n%s%s",
                            a.count(), range("FACES", f), range("HAIRS", h), dbLine, pairSummary(),
                            ov.isEmpty() ? "" : "\nCambios a mano: " + ov.size()));
                    setBusy(false);
                });
            } catch (Exception e) {
                closeAfs(pfd);
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    logClear("Error: " + msg);
                    setBusy(false);
                });
            }
        });
    }

    private static String range(String what, List<Integer> l) {
        if (l.isEmpty()) {
            return what + ": 0";
        }
        return String.format(Locale.getDefault(), "%s: %d (archivos %d–%d)", what, l.size(), l.get(0), l.get(l.size() - 1));
    }

    private String pairSummary() {
        int lone = 0;
        for (int hr : hairs) {
            if (!pairs.isHairUsed(hr)) {
                lone++;
            }
        }
        return String.format(Locale.getDefault(), "Con HAIR: %d · Sin HAIR: %d · HAIRS sin FACE: %d",
                pairs.size(), faces.size() - pairs.size(), lone);
    }

    /** Cambios hechos a mano para este AFS (solo los que siguen siendo FACE/HAIR válidos). */
    private TreeMap<Integer, Integer> loadOverrides(String key, List<Integer> f, List<Integer> h) {
        Set<Integer> fs = new HashSet<>(f), hs = new HashSet<>(h);
        TreeMap<Integer, Integer> out = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : Pairs.parseOverrides(prefs().getString(key, null)).entrySet()) {
            if (fs.contains(e.getKey()) && (e.getValue() == Pairs.NONE || hs.contains(e.getValue()))) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private void saveOverrides() {
        if (overridesKey != null) {
            prefs().edit().putString(overridesKey, Pairs.serializeOverrides(overrides)).apply();
        }
    }

    private static void closeAfs(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
                // nada que hacer
            }
        }
    }

    private Thumbs.Source afsFace(final Afs a, final int slot) {
        return () -> Converter.previewTexture(a.read(slot), null, 1.0);
    }

    private Thumbs.Source afsHair(final Afs a, final int slot) {
        return () -> Converter.previewTexture(a.read(slot), HAIR_PREVIEW, 1.0);
    }

    private void buildAfsRows() {
        afsRows.clear();
        for (int face : faces) {
            Row r = new Row();
            r.faceSlot = face;
            fillAfsRow(r);
            afsRows.add(r);
        }
        afsAdapter.notifyDataSetChanged();
    }

    private void fillAfsRow(Row r) {
        int hair = pairs.hairFor(r.faceSlot);
        String who = pairs.label(r.faceSlot);
        r.faceLabel = who.isEmpty() ? String.valueOf(r.faceSlot) : r.faceSlot + "\n" + who;
        r.faceKey = "afs:f:" + r.faceSlot;
        r.face = afsFace(afs, r.faceSlot);
        if (hair == Pairs.NONE) {
            r.hairLabel = getString(R.string.no_hair);
            r.hairKey = null;
            r.hair = null;
        } else {
            r.hairLabel = String.valueOf(hair);
            r.hairKey = "afs:h:" + hair;
            r.hair = afsHair(afs, hair);
        }
    }

    /** Selector de HAIR para una FACE: cuadrícula con las previas de todos los HAIRS. */
    private void pickHairFor(final int position) {
        if (busy || afs == null) {
            return;
        }
        final Row row = afsRows.get(position);
        final int current = pairs.hairFor(row.faceSlot);
        final Afs a = afs;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ColorDialog.dp(this, 16);
        root.setPadding(pad, ColorDialog.dp(this, 8), pad, 0);

        ImageView face = new ImageView(this);
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(face, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ColorDialog.dp(this, 96)));
        thumbs.load(face, row.faceKey, row.face);

        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ColorDialog.dp(this, 360));
        lp.topMargin = ColorDialog.dp(this, 8);
        root.addView(grid, lp);

        final AlertDialog[] dialog = new AlertDialog[1];
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hair_cell, parent, false);
                return new RecyclerView.ViewHolder(v) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                final int hair = pos == 0 ? Pairs.NONE : hairs.get(pos - 1);
                ImageView iv = h.itemView.findViewById(R.id.prev);
                TextView label = h.itemView.findViewById(R.id.label);
                label.setText((hair == current ? "✓ " : "") + (hair == Pairs.NONE ? getString(R.string.no_hair) : String.valueOf(hair)));
                label.setTypeface(null, hair == current ? Typeface.BOLD : Typeface.NORMAL);
                if (hair == Pairs.NONE) {
                    thumbs.load(iv, null, null);
                } else {
                    thumbs.load(iv, "afs:h:" + hair, afsHair(a, hair));
                }
                h.itemView.setOnClickListener(v -> {
                    pairs.set(row.faceSlot, hair);
                    overrides.put(row.faceSlot, hair);
                    saveOverrides();
                    fillAfsRow(row);
                    afsAdapter.notifyItemChanged(position);
                    log("Cara " + row.faceSlot + " → " + (hair == Pairs.NONE ? getString(R.string.no_hair) : "HAIR " + hair));
                    log(pairSummary());
                    if (dialog[0] != null) {
                        dialog[0].dismiss();
                    }
                });
            }

            @Override
            public int getItemCount() {
                return hairs.size() + 1;
            }
        });
        int start = current == Pairs.NONE ? 0 : Collections.binarySearch(hairs, current) + 1;
        grid.scrollToPosition(Math.max(0, start));

        dialog[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pick_hair, row.faceLabel.replace('\n', ' ')))
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Extrae todas las FACES y HAIRS renombrados según su pareja. */
    private void extractAll() {
        if (busy) {
            return;
        }
        if (afs == null) {
            message(R.string.need_afs);
            return;
        }
        if (extractTree == null) {
            message(R.string.need_extract_folder);
            return;
        }
        setBusy(true);
        final Afs a = afs;
        final Uri tree = extractTree;
        final List<Integer> f = new ArrayList<>(faces), h = new ArrayList<>(hairs);
        final Pairs pr = pairs.copy();
        log("Extrayendo en " + folderLabel(tree) + "/" + OUT_DIR + "…");
        worker.execute(() -> {
            int nf = 0, nh = 0, lone = 0;
            String error = null;
            try {
                ContentResolver cr = getContentResolver();
                Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                Uri dir = DocumentsContract.createDocument(cr, root, DocumentsContract.Document.MIME_TYPE_DIR, OUT_DIR);
                if (dir == null) {
                    throw new IOException("no se pudo crear la carpeta " + OUT_DIR);
                }
                final String dirName = displayName(dir);
                log("Carpeta: " + dirName);
                int done = 0, total = f.size() + h.size();
                for (int face : f) {
                    List<String> who = pr.names(face);
                    String player = who.isEmpty() ? null : who.get(0);
                    write(cr, dir, Pairs.faceName(face, player), a.read(face));
                    nf++;
                    int hair = pr.hairFor(face);
                    if (hair != Pairs.NONE) {
                        write(cr, dir, Pairs.hairName(face, hair, player), a.read(hair));
                        nh++;
                    }
                    if (++done % 200 == 0) {
                        log("  " + done + " de " + total + "…");
                    }
                }
                for (int hair : h) {
                    if (!pr.isHairUsed(hair)) {
                        write(cr, dir, Pairs.loneHairName(hair), a.read(hair));
                        lone++;
                    }
                    if (++done % 200 == 0) {
                        log("  " + done + " de " + total + "…");
                    }
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String msg = String.format(Locale.getDefault(),
                    "%s FACES: %d · HAIRS con su FACE: %d · HAIRS sin FACE: %d",
                    error == null ? "Listo." : "Error: " + error + ".", nf, nh, lone);
            log(msg);
            runOnUiThread(() -> setBusy(false));
        });
    }

    private static void write(ContentResolver cr, Uri dir, String name, byte[] data) throws IOException {
        Uri doc = DocumentsContract.createDocument(cr, dir, "application/octet-stream", name);
        if (doc == null) {
            throw new IOException("no se pudo crear " + name);
        }
        OutputStream out = cr.openOutputStream(doc, "w");
        if (out == null) {
            throw new IOException("no se pudo escribir " + name);
        }
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private void message(int res) {
        new MaterialAlertDialogBuilder(this).setMessage(res).setPositiveButton(R.string.ok, null).show();
    }

    // ------------------------------------------------------------ pestaña 2: carpeta de .bin

    private static final class BinFile {
        String name;
        Uri uri;
        Pairs.Name parsed;
        FaceHair.Kind kind;
        boolean used;
    }

    private void loadBins(final Uri tree) {
        setBusy(true);
        binsSummary.setText("Leyendo la carpeta…");
        // Parejas del AFS abierto en la pestaña 1, para .bin con nombres de otras herramientas.
        final Pairs known = pairs.copy();
        worker.execute(() -> {
            final List<Row> rows = new ArrayList<>();
            String summary;
            try {
                List<BinFile> files = listBins(tree);
                Map<Integer, BinFile> hairOfFace = new HashMap<>();
                Map<Integer, BinFile> hairBySlot = new HashMap<>();
                List<BinFile> faceFiles = new ArrayList<>(), hairFiles = new ArrayList<>();
                for (BinFile b : files) {
                    if (b.kind == FaceHair.Kind.FACE) {
                        faceFiles.add(b);
                    } else {
                        hairFiles.add(b);
                        if (b.parsed.ours && b.parsed.face != Pairs.NONE) {
                            hairOfFace.put(b.parsed.face, b);
                        }
                        int slot = b.parsed.ours ? b.parsed.hair : b.parsed.slot;
                        if (slot != Pairs.NONE && !hairBySlot.containsKey(slot)) {
                            hairBySlot.put(slot, b);
                        }
                    }
                }
                Collections.sort(faceFiles, (x, y) -> Integer.compare(faceId(x), faceId(y)));
                int paired = 0;
                for (BinFile fb : faceFiles) {
                    int id = faceId(fb);
                    BinFile hb = id == Pairs.NONE ? null : hairOfFace.get(id);
                    if (hb == null && id != Pairs.NONE && known.hairFor(id) != Pairs.NONE) {
                        hb = hairBySlot.get(known.hairFor(id));
                    }
                    Row r = new Row();
                    String who = !fb.parsed.player.isEmpty() ? fb.parsed.player
                            : id != Pairs.NONE ? known.label(id) : "";
                    r.faceLabel = id == Pairs.NONE ? fb.name : who.isEmpty() ? String.valueOf(id) : id + "\n" + who;
                    r.faceKey = "uri:" + fb.uri;
                    r.face = uriSource(fb.uri, null);
                    if (hb != null) {
                        hb.used = true;
                        paired++;
                        int hs = hb.parsed.ours ? hb.parsed.hair : hb.parsed.slot;
                        r.hairLabel = hs != Pairs.NONE ? String.valueOf(hs) : hb.name;
                        r.hairKey = "uri:" + hb.uri;
                        r.hair = uriSource(hb.uri, HAIR_PREVIEW);
                    } else {
                        r.hairLabel = getString(R.string.no_hair);
                    }
                    rows.add(r);
                }
                int lone = 0;
                for (BinFile hb : hairFiles) {
                    if (hb.used) {
                        continue;
                    }
                    lone++;
                    int hs = hb.parsed.ours ? hb.parsed.hair : hb.parsed.slot;
                    Row r = new Row();
                    r.faceLabel = getString(R.string.no_face);
                    r.hairLabel = hs != Pairs.NONE ? String.valueOf(hs) : hb.name;
                    r.hairKey = "uri:" + hb.uri;
                    r.hair = uriSource(hb.uri, HAIR_PREVIEW);
                    rows.add(r);
                }
                summary = String.format(Locale.getDefault(),
                        "FACES: %d · HAIRS: %d · Con HAIR: %d · Sin HAIR: %d · HAIRS sin FACE: %d. Toca una fila para ver su previa.",
                        faceFiles.size(), hairFiles.size(), paired, faceFiles.size() - paired, lone);
                if (files.isEmpty()) {
                    summary = "No hay FACES ni HAIRS de PES 2010 PS2 en esta carpeta.";
                }
            } catch (Exception e) {
                summary = "Error: " + e.getMessage();
            }
            final String s = summary;
            runOnUiThread(() -> {
                binRows.clear();
                binRows.addAll(rows);
                binsAdapter.notifyDataSetChanged();
                binsSummary.setText(s);
                if (!binRows.isEmpty()) {
                    showPreview(0);
                } else {
                    clearPreview();
                }
                setBusy(false);
            });
        });
    }

    /** Número de FACE que da el nombre del archivo, o NONE. */
    private static int faceId(BinFile b) {
        return b.parsed.ours ? b.parsed.face : b.parsed.slot;
    }

    private List<BinFile> listBins(Uri tree) throws IOException {
        ContentResolver cr = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<BinFile> out = new ArrayList<>();
        Cursor c = cr.query(children, cols, null, null, null);
        if (c == null) {
            throw new IOException("no se pudo leer la carpeta");
        }
        try {
            while (c.moveToNext()) {
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                        || !name.toLowerCase(Locale.ROOT).endsWith(".bin") || size > MAX_BIN_SIZE) {
                    continue;
                }
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                FaceHair.Kind kind;
                try {
                    kind = FaceHair.classify(read(doc, FaceHair.HEAD_BYTES));
                } catch (IOException e) {
                    kind = null;
                }
                if (kind == null) {
                    continue;
                }
                BinFile b = new BinFile();
                b.name = name;
                b.uri = doc;
                b.parsed = Pairs.parseName(name);
                b.kind = kind;
                out.add(b);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private Thumbs.Source uriSource(final Uri u, final Integer tint) {
        return () -> Converter.previewTexture(read(u, MAX_BIN_SIZE), tint, 1.0);
    }

    private void showPreview(int position) {
        Row r = binRows.get(position);
        prevFaceLabel.setText(getString(R.string.col_face) + " " + r.faceLabel);
        prevHairLabel.setText(getString(R.string.col_hair) + " " + r.hairLabel);
        thumbs.load(prevFace, r.faceKey, r.face);
        thumbs.load(prevHair, r.hairKey, r.hair);
        binsAdapter.select(position);
    }

    private void clearPreview() {
        prevFaceLabel.setText(R.string.col_face);
        prevHairLabel.setText(R.string.col_hair);
        thumbs.load(prevFace, null, null);
        thumbs.load(prevHair, null, null);
    }

    // ------------------------------------------------------------ utilidades

    private byte[] read(Uri u, int max) throws IOException {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) {
            throw new IOException("no se pudo abrir");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (out.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
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
        return s != null ? s : "0_TEXT.AFS";
    }

    // ------------------------------------------------------------ lista

    private interface OnRow {
        void onRow(int position);
    }

    private final class PairAdapter extends RecyclerView.Adapter<PairAdapter.Holder> {
        private final List<Row> rows;
        private final OnRow onRow;
        private int selected = RecyclerView.NO_POSITION;

        PairAdapter(List<Row> rows, OnRow onRow) {
            this.rows = rows;
            this.onRow = onRow;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView face, hair;
            final ImageView facePrev, hairPrev;

            Holder(View v) {
                super(v);
                face = v.findViewById(R.id.face);
                hair = v.findViewById(R.id.hair);
                facePrev = v.findViewById(R.id.face_prev);
                hairPrev = v.findViewById(R.id.hair_prev);
            }
        }

        void select(int position) {
            int old = selected;
            selected = position;
            if (old != RecyclerView.NO_POSITION) {
                notifyItemChanged(old);
            }
            notifyItemChanged(position);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pair, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, int position) {
            Row r = rows.get(position);
            h.face.setText(r.faceLabel);
            h.hair.setText(r.hairLabel);
            h.hair.setAlpha(r.hair == null ? 0.6f : 1f);
            h.itemView.setActivated(position == selected);
            h.itemView.setBackgroundColor(position == selected
                    ? com.google.android.material.color.MaterialColors.getColor(h.itemView,
                    com.google.android.material.R.attr.colorSecondaryContainer)
                    : 0);
            thumbs.load(h.facePrev, r.faceKey, r.face);
            thumbs.load(h.hairPrev, r.hairKey, r.hair);
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) {
                    onRow.onRow(p);
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
