# PES5Editor para Xbox: caras y equipos

Versión de [PES5Editor](https://github.com/PeterC10/PES5Editor) (PESFan Editor 5) con funciones nuevas para el option file de **PES 5 de Xbox**. También sirve para PC y PS2.

## Descargar y abrir

1. Instala Java 8 o superior.
2. Descarga `dist/PES5Editor-Xbox.jar` y ábrelo con doble clic o con `java -jar PES5Editor-Xbox.jar`.
3. Abre tu partida de Xbox, `KONAMI-XBOX-PES5OPT.xsv`. En la consola está en `E:\UDATA\4b4e0030\5252897584A1\`. También abre el `.zip` de Action Replay MAX.

**Haz siempre una copia de la partida antes de editarla.**

## Lo que ya traía el editor

- **Team**: nombres, uniformes y colores de los equipos.
- **Emblem**: cargar escudos desde PNG o GIF.
- **Logo**: cargar logos desde PNG.
- **League**: nombres de las ligas.
- **Stadium**: nombres de los estadios.
- **OF2 Import**: con *File > Open OF2* abres otro option file y copias jugadores, plantillas, formaciones, uniformes, escudos, logos o nombres. El segundo option file puede ser de PC (`KONAMI-WIN32PES5OPT`), PS2 (`.xps` de SharkPort / X-Port) o Xbox.
- Al guardar, la firma de la partida de Xbox se genera de nuevo.

## Lo nuevo

### Cara y pelo de cada jugador

En la pestaña *Transfer*, haz doble clic en un jugador. El recuadro **Cara y pelo** tiene estos campos:

| Campo | Valores |
|---|---|
| Tipo de cara | Creada en el editor / Especial / Normal (predefinida) |
| Número de cara | 1–512, como en el juego |
| Color de piel | 1–4 |
| ID de pelo | 0–2047 |

**Relinkear** a un jugador es ponerle *Normal* con el número de la cara que quieres. Sin la marca de especial, el juego no se la quita al editarlo.

Solo se escriben los campos que cambies.

### Menú "Caras y equipos"

- **Exportar caras a CSV**: ID, nombre, tipo, número de cara, piel y pelo de los 5183 jugadores. El separador es `;` y el archivo abre en Excel.
- **Importar caras desde CSV**: aplica las columnas `ID`, `TIPO CARA` (0 creada, 1 especial, 2 normal), `NUMERO CARA`, `PIEL` y `PELO`. Las celdas vacías no cambian nada, así que puedes relinkear muchos jugadores a la vez.
- **Quitar caras especiales**: pasa a cara normal a todos los jugadores con cara especial, o solo a los de un equipo.
  - El número de cara nuevo es el actual más un *desplazamiento*, que por defecto es 0.
  - Por defecto **guarda el resultado en un option file aparte** y deja sin tocar el que tienes abierto.
  - Guarda un informe CSV con el tipo y número de cara anteriores de cada jugador. Ese informe se puede usar para deshacer los cambios con *Importar caras desde CSV*.
- **Exportar equipos con formación**: elige uno o varios equipos. Se guarda un archivo `.pes5eq` por equipo con la plantilla, los dorsales, la formación (posiciones, estrategia, capitán y lanzadores), los datos de sus jugadores y, en los clubes, el nombre y la abreviatura.
- **Importar equipo**: carga un `.pes5eq` en el equipo que elijas.
  - Una selección solo se puede importar en otra selección, y un club en otro club.
  - Si importas también los datos de los jugadores, se sobrescriben los jugadores con esos mismos IDs.

Las plantillas sin formación propia (clásicos, Master League, tienda) no se pueden exportar.

## Posiciones de los campos de cara

Las posiciones de los campos de cara salen de [PES5_WE9_LE_OF_Team_Editor](https://github.com/moth1995/PES5_WE9_LE_OF_Team_Editor), porque el editor original no los manejaba. Son posiciones dentro de los datos de cada jugador:

| Campo | Posición, desplazamiento de bits, máscara |
|---|---|
| Tipo de cara | 60, 2, 3 |
| Número de cara | 53, 3, 511 |
| Piel | 43, 1, 3 |
| Pelo | 45, 0, 2047 |

Todavía no se han comprobado en una partida de Xbox. Para comprobarlas:

1. Guarda una copia de la partida.
2. En el juego, cambia solo la cara de un jugador y guarda.
3. Abre las dos partidas y compara lo que muestra el editor para ese jugador.

Si alguna posición no coincide, crea un archivo `caras.properties` en la carpeta desde la que abres el editor y cámbiala sin recompilar:

```
tipoCara=60,2,3
numeroCara=53,3,511
piel=43,1,3
pelo=45,0,2047
```

## Compilar

```
./build_jar.sh
```

Esto crea `dist/PES5Editor-Xbox.jar`.
