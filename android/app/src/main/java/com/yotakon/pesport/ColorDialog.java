package com.yotakon.pesport;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.yotakon.pesport.core.Converter;
import com.yotakon.pesport.core.Texture;

import java.util.Locale;

/** Selector de color y brillo del pelo con vista previa de la textura teñida. */
final class ColorDialog {
    interface Listener {
        void onColor(int rgb, double brightness);
    }

    private static final int[] PRESETS = {0x1A1410, 0x281E18, 0x4A3020, 0x7A5030, 0xC8A060, 0xE6C88C,
            0x8C3818, 0x808080, 0xE0E0E0};
    private static final String[] PRESET_NAMES = {"Negro", "Castaño oscuro", "Castaño", "Castaño claro",
            "Rubio", "Rubio claro", "Pelirrojo", "Gris", "Canoso"};

    private static final float BRIGHT_MIN = 0.5f, BRIGHT_MAX = 2.0f, BRIGHT_STEP = 0.05f;

    private ColorDialog() {
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static float snapBrightness(double b) {
        double clamped = Math.max(BRIGHT_MIN, Math.min(BRIGHT_MAX, b));
        long steps = Math.round((clamped - BRIGHT_MIN) / BRIGHT_STEP);
        return BRIGHT_MIN + steps * BRIGHT_STEP;
    }

    static void show(Context ctx, final byte[] ps2Bin, int rgb, double brightness, final Listener listener) {
        final int[] color = {rgb & 0xFFFFFF};
        final double[] bright = {snapBrightness(brightness)};
        final boolean[] updating = {false};

        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_color, null);
        final ImageView preview = root.findViewById(R.id.preview);
        final View swatch = root.findViewById(R.id.swatch);
        final TextInputEditText hexField = root.findViewById(R.id.hex);
        final Slider[] bars = {root.findViewById(R.id.red), root.findViewById(R.id.green), root.findViewById(R.id.blue)};
        final Slider brightBar = root.findViewById(R.id.bright);
        final TextView brightLabel = root.findViewById(R.id.bright_label);
        ChipGroup presets = root.findViewById(R.id.presets);

        brightBar.setValueFrom(BRIGHT_MIN);
        brightBar.setValueTo(BRIGHT_MAX);
        brightBar.setStepSize(BRIGHT_STEP);

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                updating[0] = true;
                for (int i = 0; i < 3; i++) {
                    bars[i].setValue((color[0] >> (16 - 8 * i)) & 0xFF);
                }
                brightBar.setValue(snapBrightness(bright[0]));
                brightLabel.setText(String.format(Locale.getDefault(), "Brillo: %.2f", bright[0]));
                swatch.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | color[0]));
                String h = hex(color[0]);
                if (hexField.getText() == null || !hexField.getText().toString().equalsIgnoreCase(h)) {
                    hexField.setText(h);
                }
                try {
                    preview.setImageBitmap(bitmap(Converter.previewTexture(ps2Bin, color[0], bright[0])));
                } catch (RuntimeException e) {
                    preview.setImageDrawable(null);
                }
                updating[0] = false;
            }
        };

        for (int i = 0; i < PRESETS.length; i++) {
            final int c = PRESETS[i];
            Chip chip = new Chip(ctx);
            chip.setText(PRESET_NAMES[i]);
            chip.setChipIconResource(R.drawable.circle);
            chip.setChipIconTint(ColorStateList.valueOf(0xFF000000 | c));
            chip.setChipIconVisible(true);
            chip.setOnClickListener(v -> {
                color[0] = c;
                refresh.run();
            });
            presets.addView(chip);
        }

        hexField.addTextChangedListener(new TextWatcher() {
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

        Slider.OnChangeListener onBar = (slider, value, fromUser) -> {
            if (!fromUser || updating[0]) {
                return;
            }
            if (slider == brightBar) {
                bright[0] = value;
            } else {
                color[0] = ((int) bars[0].getValue() << 16) | ((int) bars[1].getValue() << 8) | (int) bars[2].getValue();
            }
            refresh.run();
        };
        for (Slider s : bars) {
            s.addOnChangeListener(onBar);
        }
        brightBar.addOnChangeListener(onBar);
        refresh.run();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.hair_color)
                .setIcon(R.drawable.ic_palette)
                .setView(root)
                .setPositiveButton(R.string.ok, (d, w) -> listener.onColor(color[0], bright[0]))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Bitmap de una textura PES, escalado x4 sin suavizado para ver los pixeles. */
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
