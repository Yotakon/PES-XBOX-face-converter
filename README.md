# PES-XBOX-face-converter

Convierte FACES y HAIRS de **PES 2010 PS2** al formato de **PES 5 Xbox**.

- **App Android** (`android/`): eliges los `.bin` de PES 2010, el color de cada HAIR y una
  carpeta de salida; la app guarda los `.bin` listos para PES 5 Xbox.
- **Conversor de referencia en Python** (`tools/pesport/`): misma conversión desde la línea de
  comandos. Es la versión que se validó en el juego; la app produce exactamente los mismos bytes.
- **Formatos** (`docs/FORMATOS.md`): estructura de los archivos de ambos juegos y reglas de
  conversión.

## Uso de la app

1. Extrae del `0_text.afs` de PES 2010 PS2 los `.bin` del FACE y el HAIR del jugador.
2. En la app: **Añadir archivos .bin** → elige uno o varios.
3. En cada HAIR, pulsa **Color** y elige el color y el brillo (PES 2010 guarda el HAIR en gris
   y el juego lo tiñe; PES 5 necesita la textura ya coloreada).
4. **Elegir carpeta de salida** → **Convertir**. Se crean archivos `… (PES5 Xbox).bin`.
5. Inserta esos `.bin` en el `0_text.afs` de PES 5 Xbox con tu herramienta AFS habitual,
   en los slots del FACE y el HAIR del jugador.

Descarga del APK de prueba (última compilación de la rama `app-android`):
<https://github.com/Yotakon/PES-XBOX-face-converter/releases/download/ultima/PESFaceConverter.apk>

## Qué hace la conversión

- Modelos: de paquetes VIF de PS2 (enteros de 16 bits) a vértices float de Xbox, con las
  15 expresiones faciales (morphs).
- Quita el molde de barba de PES 2010 (en PES 5 la barba va pintada en la textura).
- Ajusta el borde del FACE a la cabeza del cuerpo de PES 5 y los UV de los ojos a los
  valores exactos de PES 5 (sin esto los ojos no siguen el movimiento).
- Texturas: se copian tal cual (mismo formato); el HAIR se tiñe con el color elegido.

Probado en el juego con Ronaldinho (FACE y HAIR). Las reglas de huesos se dedujeron de ese
jugador; si otro jugador falla, el conversor muestra un error en lugar de generar un archivo roto.

## Línea de comandos (Python 3, sin dependencias)

```
cd tools
python3 -m pesport "face_ps2.bin" face_xbox.bin
python3 -m pesport "hair_ps2.bin" hair_xbox.bin --color 281e18 [--brillo 1.0]
```

## Desarrollo

- `python3 tools/gen_referencias.py` regenera `tests/referencia/` con el conversor Python.
- Las pruebas de la app (`android/app/src/test`) comparan su salida con esas referencias.
- GitHub Actions compila el APK y lo publica en la release `ultima` en cada push a
  `app-android`. Para compilar en local hace falta Android Studio (o Gradle 8.9 + Android SDK 34):
  `gradle -p android assembleDebug`.
