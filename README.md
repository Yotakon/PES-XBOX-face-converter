# PES-XBOX-face-converter

Convierte FACES y HAIRS de **PES 2010 PS2** al formato de **PES 5 Xbox**.

- **App Android** (`android/`, en Kotlin): tres pestañas. **Convertir** pasa los `.bin` de
  PES 2010 a PES 5 Xbox; **Importar 0_TEXT** busca y extrae FACES y HAIRS del 0_TEXT.AFS de
  PES 2010; **AFS** extrae un AFS a una carpeta y crea un AFS de PES 5 a partir de una carpeta.
- **Conversor de referencia en Python** (`tools/pesport/`): misma conversión desde la línea de
  comandos. Es la versión que se validó en el juego; la app produce exactamente los mismos bytes.
- **Formatos** (`docs/FORMATOS.md`): estructura de los archivos de ambos juegos y reglas de
  conversión.

## Uso de la app

Pestaña **Convertir**:

1. Extrae del `0_text.afs` de PES 2010 PS2 los `.bin` del FACE y el HAIR del jugador
   (pestaña **Importar 0_TEXT**).
2. **Añadir .bin** → elige uno o varios.
3. En cada HAIR, pulsa **Color** y elige el color y el brillo (PES 2010 guarda el HAIR en gris
   y el juego lo tiñe; PES 5 necesita la textura ya coloreada).
4. **Elegir carpeta de salida** → **Convertir**. Se crean archivos `… (PES5 Xbox).bin`.
5. Mete esos `.bin` en el `0_text.afs` de PES 5 Xbox en los slots del FACE y el HAIR del
   jugador (pestaña **AFS**, abajo).

Descarga del APK de prueba (última compilación de la rama `app-android`):
<https://github.com/Yotakon/PES-XBOX-face-converter/releases/download/ultima/PESFaceConverter.apk>

## Pestaña Importar 0_TEXT (caras y pelos del 0_TEXT.AFS)

- **Subpestaña 0_TEXT.AFS:** **Buscar** el AFS. El registro muestra cuántas FACES y HAIRS tiene
  (en PES 2010 PS2: 1431 FACES en los archivos 1527–2957 y 1147 HAIRS en 4468–5614). La lista
  muestra FACE (con el jugador) | HAIR | Previa | Previa; toca una fila para cambiar su HAIR.
  **Extraer y renombrar** guarda en la carpeta elegida `1701 FACE - RONALDINHO.bin`,
  `1701 HAIR (slot 4593) - RONALDINHO.bin` y los HAIRS sin FACE como `HAIR 4600 (sin FACE).bin`.
- **Subpestaña Carpeta:** elige una carpeta con .bin extraídos; la lista (sin previas) empareja cada
  FACE con su HAIR por el nombre y, al tocar una fila, muestra arriba la previa de los dos.

FACES que no están en la base de datos (por ejemplo Bergkamp) salen como «No Implementado» y sin
HAIR: la app no deduce nada por el orden de los archivos. Los grupos de piel de las FACES tienen
el tamaño que indica el archivo 505 del AFS (800, 341, 126 y 164 en PES 2010 PS2), en el orden 3, 2, 0, 1.

## Pestaña AFS (extraer y crear AFS de PES 5)

Sirve para meter FACES y HAIRS más grandes que los originales: en vez de sobrescribir dentro del
AFS (donde cada archivo tiene un hueco fijo), se crea un AFS nuevo con todos los archivos.

- **Extraer AFS:** **Buscar** el AFS (por ejemplo el `0_text.afs` de PES 5) y elegir carpeta.
  Se crea una carpeta con el nombre del AFS y dentro cada archivo como `00000.bin`,
  `00001.bin`… (el número es su posición en el AFS) y `AFS_INFO.txt` con el número de
  archivos, el tamaño de la cabecera y los tamaños originales. No borres `AFS_INFO.txt`.
- **Crear AFS:** elige la carpeta (después de extraer ya queda elegida). La lista muestra cada
  archivo con su tamaño y marca los que cambiaron. **Toca un archivo para reemplazarlo** por
  otro `.bin` (por ejemplo `1701 FACE - RONALDINHO (PES5 Xbox).bin`): se copia encima con el
  nombre del índice. También puedes reemplazarlos con un explorador de archivos, siempre que
  el nuevo tenga el nombre del índice. **Crear AFS** pide dónde guardar el AFS nuevo.

Solo entran en el AFS los archivos llamados `00012.bin`, `12.bin` o `unnamed_12.bin`; los demás
se ignoran (los nombres como `1701 FACE - ….bin` llevan el número de PES 2010, que no es el
slot de PES 5). La app no crea el AFS si falta algún archivo, si hay dos para el mismo índice o
si sobra alguno respecto al original.

Formato que escribe: `AFS\0`, número de archivos, tabla (offset, tamaño), sin tabla de nombres
(como los AFS de PES), cabecera del mismo tamaño que la del original y cada archivo alineado a
2048 bytes. Si no cambias nada, el AFS creado es idéntico byte a byte al que se extrajo
(si este seguía el mismo formato). Guarda una copia del AFS original antes de usar el nuevo.

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
