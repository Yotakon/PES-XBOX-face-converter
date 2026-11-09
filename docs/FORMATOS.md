# Formatos de caras y pelos: PES 2010 PS2 y PES 5 Xbox

Análisis hecho con los archivos de Ronaldinho de ambos juegos.
"Confirmado" = verificado con los datos; "Hipótesis" = encaja con los datos pero falta prueba.

## 1. Contenedor `.bin` (igual en ambos juegos) — Confirmado

| Offset | Tipo | Contenido |
|---|---|---|
| 0x00 | u32 | `00 06 01 00` |
| 0x04 | u32 | tamaño comprimido |
| 0x08 | u32 | tamaño descomprimido |
| 0x0C–0x1F | — | ceros |
| 0x20 | — | stream zlib |

Descomprimido:

| Offset | Tipo | Contenido |
|---|---|---|
| 0x00 | u32 | número de secciones (3) |
| 0x04 | u32 | 8 |
| 0x08 | u32[n] | offsets de cada sección |

- Cara: sección 0 = modelo principal, 1 = segundo modelo (menos detalle, Hipótesis: LOD), 2 = textura.
- Pelo: sección 0 = modelo, 1 = textura 128×64, 2 = textura 64×32 (mipmap/LOD, misma paleta).

## 2. Texturas (magic `94 72 85 29`) — Confirmado

Mismo formato en ambos juegos. Cabeceras idénticas byte a byte.

| Offset | Tipo | Contenido |
|---|---|---|
| 0x08 | u32 | tamaño de la sección |
| 0x10 | u16 | offset de píxeles (0x480) |
| 0x12 | u16 | offset de paleta (0x80) |
| 0x14 | u16 | ancho |
| 0x16 | u16 | alto |
| 0x18 | u8[4] | `02 13 log2(ancho) log2(alto)` (0x13 = PSMT8) |

- 8 bpp, paleta RGBA de 256 colores con orden CLUT de PS2 (bloques 8–15 ↔ 16–23), alfa 0–128.
- Los píxeles están en orden lineal; las caras incluyen un mipmap a mitad de tamaño.
- **Diferencia en el pelo:** PES 2010 guarda el pelo en escala de grises (RGB máx. 73) porque el
  juego lo tiñe con el color de pelo del jugador. PES 5 espera la textura ya coloreada.
  Hay que teñirla al convertir.

## 3. Modelo PS2 (magic `03 00 FF FF`) — Confirmado salvo donde se indica

Cabecera (u32):

| Offset | Contenido |
|---|---|
| 0x20 | número de chunks |
| 0x24 | offset del primer chunk |
| 0x2C | tamaño total |
| 0x38 / 0x3C | número de huesos / offset de la lista de IDs de hueso (u32 cada uno) |
| 0x44 / 0x48 | número de vértices base de morph / offset (int16 ×3) |
| 0x4C / 0x50 | número de morphs (15 en caras) / offset de la tabla (count, offset) |
| 0x80 | matriz 4×4 (identidad) |

Morphs (expresiones faciales): cada entrada es `int16 dx, dy, dz, índice_vértice_base`.

Chunks: cabecera de 0xB0 bytes seguida de paquetes VIF.

| Campo | Contenido |
|---|---|
| u32[0] | tamaño del chunk |
| u32[4] | número de índices de la tira |
| u32[5] | 1 = el chunk tiene vértices con morph |
| u32[13] | índice del hueso en la lista de huesos del modelo (equivale al tag 2 de Xbox) |
| u32[16] | 2 = rígido, 3 = con piel |
| +0x48 | 2 floats: (373, −118) o (1019.6, −764.6); deciden el grupo de piel en Xbox |
| +0x90 | u16 inicio en la tabla base de morph, u16 dirección VU del primer vértice con morph, u16 cantidad |

Los vértices con morph son un rango contiguo dentro del chunk: el vértice local
`(dirección − 4) / 4` corresponde al índice base `inicio`, y así sucesivamente.

| UNPACK | Contenido | Escala |
|---|---|---|
| V3-16, addr 4, ciclo 4 | posición | ÷16 = unidades Xbox |
| V3-16, addr 5 | normal | × −8/32767 = normal Xbox (signo invertido) |
| V2-16, addr 7 | UV | ÷4096 |
| V4-16 | tira de triángulos: `(addr_qword − 4) / 4` = vértice, bit 15 = no dibujar | — |

Orientación: el triángulo que termina en la posición `i` de la tira es `(i−2, i−1, i)` si `i` es
par y `(i−1, i−2, i)` si es impar (paridad global, sin reiniciar). Confirmado: con esta regla
el 100 % de los triángulos queda orientado igual que en Xbox.

## 4. Modelo Xbox (magic `20 05 04 20`) — Confirmado salvo donde se indica

Cabecera: `u32 h[0..9]`

| Campo | Contenido |
|---|---|
| h[1] | u32 0 |
| h[2] | grupos de piel: u32 count + entradas de 24 bytes |
| h[3] | lista de IDs de hueso: u16 count + u16[], alineado a 4 |
| h[4] | bloque de vértices: `u32 1, u32 8, u16 nverts, u16 stride(0x20), u32 offset_morphs (relativo a +8, 0 = sin morphs)` + vértices + bloque de morphs |
| h[5] | índices: u16 count + u16[] (tiras), alineado a 4 |
| h[6] | comandos en 0x48: pares `u16 tag, u16 valor`; tag `0x0507` = dibujar `(u16 nidx, u16 vstart, u16 vcount, u32 ntris)`; termina con u32 0 |
| h[7] | u32 número de morphs |
| h[8] | u32 2 + 24 bytes a cero |
| h[9] | u32 1 + u32 0; el archivo se rellena hasta múltiplo de 16 |

Confirmado: el lector/escritor reproduce byte a byte los 3 modelos Xbox de Ronaldinho.

Vértice (32 bytes): `float pos[3], float normal[3] (longitud 8.0), float uv[2]`.

Tags de estado: `3`=0, `1`=índice del grupo de piel, `2`=material, `0x0D`=7, `4`=0,
`0x0A`=1 solo en pelos (Hipótesis: transparencia). Se emiten solo cuando cambian, en ese orden.

Grupos de piel (24 bytes): `u16 n, u16 x, 12×FF, float f, u32 0`:
- rígido: n=1, x=0, f=0.0
- piel A: n=2, x=0x7F, f=2.0
- piel B: n=2, x=0x7F, f=8.0

Morphs: `u32 count`, entradas `u16 id, u16 count, u32 off_indices, u32 off_deltas`
(relativos al bloque), ordenadas por count descendente. Primero todos los deltas (float ×3),
luego los índices u16; las listas de índices idénticas se comparten. Alineado a 4.
Los IDs de morph coinciden con el orden de los morphs de PS2 (magnitudes comparables).

Triángulos: tira con paridad `(i, i+1, i+2)` en posición par, `(i+1, i, i+2)` en impar,
saltando degenerados; `ntris` = triángulos no degenerados.

## 5. Correspondencia PS2 → Xbox (Ronaldinho)

| PS2 chunk | tris | Xbox parte | tris |
|---|---|---|---|
| 0 | 12 | 0 | 12 |
| 1 | 8 | 1 | 8 |
| 2 | 52 | 2 | 52 |
| 3 + 4 + 5 | 265 | 3 | 264 |

- Las UV coinciden (diferencia < 0,01) y las posiciones encajan con escala ÷16: misma malla base.
- IDs de hueso: cara PS2 `100,102,103,115,118` / Xbox `100,102,115,118`;
  pelo PS2 `101,111` / Xbox `101,114`. El conversor elimina 103 y cambia 111 → 114.
  Hipótesis basada en un solo jugador: hay que probarlo en el juego.

## 6. Reglas de conversión (implementadas en `pesport/convert.py`)

- **Tag 2 = hueso:** índice del hueso del chunk en la lista de huesos Xbox.
  Confirmado: 102 = ojos, 115 y 118 = piezas de ojos/boca, 100 = cabeza; en PES 2010,
  103 = molde de barba.
- **Cara:** cada chunk rígido (tipo 2) → una parte Xbox con grupo rígido. Los chunks con piel
  → una parte por hueso, grupo piel A. Mismo procedimiento para el modelo LOD (sección 1).
- **Molde de barba (hueso 103):** se descarta. PES 5 no lo usa y la barba se pinta en la
  textura de la cara. Confirmado en el juego: si se incluye, aparece la textura de la cara
  sobre el molde.
- **Pelo:** chunks agrupados por (grupo de piel según floats +0x48, hueso); tag `0x0A` = 1.
- **Borde de la cara:** la unión con la cabeza del cuerpo son 20 vértices fijos (iguales en
  los dos LOD de PES 5). PES 2010 los tiene con error de cuantización de hasta 0,57 unidades;
  el conversor los ajusta a las posiciones exactas de PES 5 (tolerancia 1,0).
- **Ojos:** la pieza del hueso 102 (fondo del ojo, textura de piel) debe tener los UV exactos de
  PES 5: U ∈ {0.73438, 0.76172, 0.81641, 0.87109, 0.89844}, V ∈ {0.78125, 0.875, 0.99219}.
  PES 2010 guarda los mismos puntos redondeados a 1/4096 y con esa diferencia (< 0,001) los
  ojos no siguen el movimiento. Confirmado en el juego (prueba v4a). El conversor ajusta cada
  UV al valor de la tabla y da error si alguno se aleja más de 2/256.
- Vértices duplicados dentro de una parte se fusionan. Morphs: índice base → vértice Xbox,
  deltas ÷16.
- Texturas: copia directa. Pelo en gris: se tiñe la paleta con el color elegido
  (gris normalizado × color × brillo); alfa y píxeles no cambian.

Resultado con Ronaldinho: grupos, huesos, tags de cada parte e IDs de morph idénticos a los
del archivo Xbox original. Pendiente: prueba en el juego.
