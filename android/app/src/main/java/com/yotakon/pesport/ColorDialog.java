package com.yotakon.pesport;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.yotakon.pesport.core.Converter;
import com.yotakon.pesport.core.Texture;

/** Selector de color y brillo del pelo con vista previa de la textura teñida. */
final class ColorDialog {
    interface Listener {
        void onColor(int rgb, double brightness);
    }

    private static final int[] PRESETS = {0x1A1410, 0x281E18, 0x4A3020, 0x7A5030, 0xC8A060, 0xE6C88C,
            0x8C3818, 0x808080, 0xE0E0E0};
    private static final String[] PRESET_NAMES = {"Negro", "Castaño oscuro", "Castaño", "Castaño claro",
            "Rubio", "Rubio claro", "Pelirrojo", "Gris", "Canoso"};

    private ColorDialog() {
    }

    static void show(final Context ctx, final byte[] ps2Bin, int rgb, double brightness, final Listener listener) {
        final int[] color = {rgb};
        final double[] bright = {brightness};
        final int pad = dp(ctx, 12);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        final ImageView preview = new ImageView(ctx);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.rgb(90, 140, 90));   // fondo tipo cesped para ver el alfa
        root.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 140)));

        final View swatch = new View(ctx);
        root.addView(swatch, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 24)));

        final EditText hex = new EditText(ctx);
        hex.setSingleLine(true);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hex.setHint("#RRGGBB");
        root.addView(hex);

        final SeekBar[] rgbBars = new SeekBar[3];
        final String[] names = {"Rojo", "Verde", "Azul"};
        for (int i = 0; i < 3; i++) {
            TextView label = new TextView(ctx);
            label.setText(names[i]);
            root.addView(label);
            rgbBars[i] = new SeekBar(ctx);
            rgbBars[i].setMax(255);
            root.addView(rgbBars[i]);
        }
        final TextView brightLabel = new TextView(ctx);
        root.addView(brightLabel);
        final SeekBar brightBar = new SeekBar(ctx);
        brightBar.setMax(150);   // 0.50 .. 2.00
        root.addView(brightBar);

        LinearLayout presets = new LinearLayout(ctx);
        presets.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < PRESETS.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(ctx);
                presets.addView(row);
            }
            Button b = new Button(ctx);
            b.setText(PRESET_NAMES[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            b.setAllCaps(false);
            final int c = PRESETS[i];
            b.setOnClickListener(v -> {
                color[0] = c;
                hex.setText(String.format("#%06X", c));
            });
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(presets);

        final boolean[] updating = {false};
        final Runnable refresh = () -> {
            updating[0] = true;
            for (int i = 0; i < 3; i++) {
                rgbBars[i].setProgress((color[0] >> (16 - 8 * i)) & 0xFF);
            }
            brightBar.setProgress((int) Math.round((bright[0] - 0.5) * 100));
            brightLabel.setText(String.format("Brillo: %.2f", bright[0]));
            swatch.setBackgroundColor(0xFF000000 | color[0]);
            String h = String.format("#%06X", color[0]);
            if (!hex.getText().toString().equalsIgnoreCase(h)) {
                hex.setText(h);
            }
            try {
                byte[] tex = Converter.previewTexture(ps2Bin, color[0], bright[0]);
                preview.setImageBitmap(bitmap(tex));
            } catch (RuntimeException e) {
                preview.setImageDrawable(null);
            }
            updating[0] = false;
        };

        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) {
                    return;
                }
                String t = s.toString().trim().replace("#", "");
                if (t.matches("[0-9a-fA-F]{6}")) {
                    color[0] = Integer.parseInt(t, 16);
                    refresh.run();
                }
            }
        });
        SeekBar.OnSeekBarChangeListener bars = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (!fromUser || updating[0]) {
                    return;
                }
                if (bar == brightBar) {
                    bright[0] = 0.5 + p / 100.0;
                } else {
                    color[0] = (rgbBars[0].getProgress() << 16) | (rgbBars[1].getProgress() << 8)
                            | rgbBars[2].getProgress();
                }
                refresh.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        };
        for (SeekBar b : rgbBars) {
            b.setOnSeekBarChangeListener(bars);
        }
        brightBar.setOnSeekBarChangeListener(bars);
        refresh.run();

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(root);
        new AlertDialog.Builder(ctx)
                .setTitle("Color del pelo")
                .setView(scroll)
                .setPositiveButton("Aceptar", (d, w) -> listener.onColor(color[0], bright[0]))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Bitmap de una textura PES (escalado x4 para que se vea nitido). */
    static Bitmap bitmap(byte[] tex) {
        int[] argb = Texture.toArgb(tex);
        int w = argb[0], h = argb[1];
        int[] px = new int[w * h];
        System.arraycopy(argb, 2, px, 0, px.length);
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(b, w * 4, h * 4, false);
    }

    static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
