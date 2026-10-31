# Stark TV

Launcher (pantalla de inicio) ligero para Android TV, hecho para manejarse solo con el mando:
sin anuncios, sin cuentas y sin recoger datos. Pensado para televisores con poca memoria.

## Dónde se ha probado

| | |
|---|---|
| Televisor | **Thomson 2K Android TV** (modelo `Thomson_2K_ATV2`, plataforma MediaTek) |
| Sistema | **Android TV 14** |
| Memoria | 1 GB de RAM |
| Mando | Mando Bluetooth Thomson (con jog y tecla de ajustes) |

Requisitos: Android 11 o superior (minSdk 30, compilado para Android 14). En otros modelos debería
funcionar lo básico; la ventanita de TV y el PAP dependen de cómo el fabricante exponga el sintonizador y las ventanas.

## Funciones

### Inicio y dock
- **Dock** con las apps en 6 posiciones (abajo, centro, arriba, o vertical a la izquierda, centro o derecha) y 4 tamaños de ficha (micro a grande).
- **Favoritos** primero (cuadrícula de hasta 6) y luego todas las apps; **OK mantenido** sobre una app: añadir o quitar de favoritos, **mover** (la ficha se levanta y viaja con ◀ ▶), ocultar e información.
- Fichas con brillo de cristal, sombra, **halo del color de la app** (en proporción al tamaño de la ficha), luz que recorre el borde, destello y reflejo en el suelo. Una luz del color de la app enfocada la sigue por la pantalla.
- Nombre bajo las apps, título del dock y ficha de PAP en el dock, a elegir.
- El **jog** del mando mueve una sola ficha por giro (se ignoran las repeticiones de menos de 700 ms).

### Reloj, fecha y tiempo
- Hora, fecha completa y **tiempo actual** de la ciudad que elijas, centrados en su bloque; posición automática o a un lado.
- **Salvapantallas** a los minutos que elijas: reloj grande, fecha y tiempo sobre el fondo; cualquier tecla lo quita.

### Ventanita de TV
- TV en directo dentro del inicio: **antena (TDT), HDMI 1-3 o AV**, en una esquina a elegir o automática; se aparta sola del dock.
- CH+ / CH− cambian de canal; OK mantenido abre su menú (fuente, posición, sonido).
- Sonido activable y **nombre del canal y la fuente ocultable**.

### PAP y PIP (dos cosas a la vez)
- Dos apps o la TV y una app **una al lado de otra** o una **dentro de otra**, con varios repartos de pantalla.
- Se elige qué parte suena (una, la otra o las dos).
- Se abre con la tecla de la tuerca del mando o desde el inicio; el selector deja cambiar cada parte, el sonido y el modo.
- Necesita el permiso avanzado por adb (ver «Primera instalación»).

### Fondos
- Incluidos: **Castillo** y **Santa's Day** (imágenes), **Humo** y **Partículas** (HTML5) y **Halloween - HTML5** (imagen con murciélagos que pasan por detrás del árbol, nubes, niebla, cascadas con agua en movimiento y las luces de calabazas, farolillos y castillo encendiéndose).
- Propios: **imagen** (jpg, png, webp), **GIF animado**, **vídeo** (mp4, mkv, webm, mov; en bucle y sin sonido) y **HTML5**.
- **Brillo** del fondo, **cambiar de fondo solo** cada cierto tiempo y mostrar u ocultar la versión sobre el fondo.
- El fondo se destruye al salir del inicio: mientras ves otra app no gasta memoria ni gráfica.

### Recibir fondos desde el móvil o el PC
En *Ajustes › Launcher › Fondo › Recibir fondos desde el móvil o PC* la TV enseña una dirección con un número de 6 cifras.
Abriéndola en el navegador de un aparato de la misma red se pueden **enviar** fondos (con barra de progreso),
**ponerlos** de fondo, **renombrarlos** y **borrarlos**, y se ve cuál está puesto. Ver «Seguridad» más abajo.

### Aspecto
- **Color de la interfaz** (7 colores): tiñe paneles, interruptores, reloj, luz de ambiente y selección.
- **Opacidad** y **oscuridad** de los menús, por separado.
- **11 tipografías** (la del sistema y 10 de Google Fonts).
- Efectos que se pueden apagar si la TV va justa: reflejo en el suelo, cristal difuminado y animaciones de luz.

### Ajustes
- Ajustes al estilo iPhone, por pestañas (Fondo, Dock, Reloj, Pantalla, Efectos, PAP, Sistema), todo con el mando (◀ ▶ cambian los valores).
- Acceso directo a los ajustes de la TV, apps ocultas y copia de seguridad de los ajustes.

## Seguridad

Que la TV no corra riesgo por usar el launcher:

- **Archivos recibidos**:
  - Solo se admiten las extensiones de la lista, con el nombre saneado, sin rutas.
  - Cada archivo se comprueba por su contenido, no solo por la extensión. Un archivo disfrazado se rechaza.
  - Se les **quitan los metadatos**: EXIF (también el GPS), XMP, perfiles, comentarios, etiquetas de vídeo y adjuntos.
    - En las imágenes, sin volver a comprimir. Solo una foto girada por EXIF se gira y se vuelve a guardar.
    - En los vídeos, sin mover nada.
  - Si algo no se puede comprobar o limpiar, no se guarda.
  - Imágenes de más de 8192 px de lado, HTML de más de 5 MB y archivos de más de 1 GB se rechazan.
- **.zip de HTML5**:
  - Solo se sacan los tipos permitidos (HTML, imágenes, vídeo, CSS, JS, fuentes); lo demás se descarta.
  - Como mucho 500 archivos y 300 MB descomprimidos.
  - Ningún archivo puede salir de su carpeta.
- **HTML recibidos**, encerrados:
  - Sin red.
  - Sin poder leer archivos de la TV desde JavaScript.
  - Solo cargan lo que hay en su propia carpeta.
  - No navegan a otras páginas.
- **HTML de USB, Descargas o `/sdcard`**: no se abren. De ahí solo se usan imágenes y vídeos.
- **Recepción**:
  - Solo mientras su pantalla está abierta y solo desde la red de casa.
  - Número aleatorio seguro y distinto cada vez.
  - Freno de intentos:
    - con 5 fallos, ese aparato se queda fuera;
    - con 20 fallos en total, se cierra la recepción.
  - Como mucho 4 conexiones y una subida a la vez.
  - Cabeceras de seguridad en la página.
- **Órdenes al sistema (adb)**:
  - Solo se ejecutan órdenes con caracteres permitidos.
  - Los nombres de app se validan antes de usarlos.
  - La copia de ajustes se acota al restaurarla.
- **Registro**: no se registra lo que se pulsa en el mando, salvo que se active a propósito para depurar.
- **Copias de Android**: la app no se incluye en las copias (`allowBackup=false`). La versión publicada se firma con una clave propia que no está en este repositorio.

## Dónde poner los fondos

Además de recibirlos desde el navegador, el launcher busca **imágenes y vídeos** en:

- `/sdcard/Fondos/`
- `/sdcard/Download/`
- `Fondos/` en la raíz de cualquier USB

Desde el PC:

```bash
adb push mi_fondo.mp4 /sdcard/Fondos/
```

Un **HTML5** propio se envía con «Recibir fondos»: un `.html` suelto o un `.zip` con su `index.html` y sus imágenes.

## Primera instalación

```bash
adb install -r StarkTV-0.9.31.apk
adb shell appops set com.chaska.inicio MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.chaska.inicio android.permission.WRITE_SECURE_SETTINGS
adb shell cmd package set-home-activity com.chaska.inicio/.MainActivity
```

Luego pulsar Inicio en el mando. Si sigue saliendo el inicio de Google:

```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Volver al de Google:

```bash
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

Para el **PAP** y el sonido por app, Stark TV habla con la depuración de la propia TV:
en *Ajustes › Launcher › Sistema › Permisos avanzados (adb)* la TV pide aceptar la conexión una vez.

## Compilar

Android Studio con el SDK 35, o `gradlew.bat assembleRelease`.
El APK de release sale como `StarkTV-<versión>.apk` y se firma con *Build › Generate Signed APK* y una clave propia.
La clave **nunca** va al repositorio: `.gitignore` deja fuera `Key`, `*.jks`, `*.keystore`, `app/release/` y los `.apk`.

## Autor y licencia

Desarrollado por **ChasKa**.

Stark TV es software libre: puedes redistribuirlo y modificarlo según los términos de la
**Licencia Pública General de GNU, versión 3** (GPL-3.0), publicada por la Free Software Foundation.
Se distribuye SIN NINGUNA GARANTÍA. El texto completo está en [`LICENSE`](LICENSE).

### Componentes de terceros

| Componente | Uso | Licencia |
|---|---|---|
| [dadb](https://github.com/mobile-dev-inc/dadb) (mobile.dev) | Hablar con la depuración de la propia TV (PAP, sonido por app) | Apache License 2.0 |
| Tipografías de [Google Fonts](https://fonts.google.com): DM Sans, Lexend, Manrope, Montserrat, Outfit, Plus Jakarta Sans, Poppins, Sora, Space Grotesk y Urbanist | Tipografías de la interfaz | SIL Open Font License 1.1 (textos en `app/src/main/assets/fuentes/licencias`) |
| [Open-Meteo](https://open-meteo.com) | Datos del tiempo y búsqueda de la ciudad (sin clave, por HTTPS) | Datos bajo CC BY 4.0 — *Weather data by Open-Meteo.com* |
| Android SDK de Google | Base de la app | Apache License 2.0 |

Las imágenes de los fondos *Castillo*, *Halloween* y *Santa's Day* y los iconos de la interfaz son aportados por ChasKa para este proyecto.
Android y Android TV son marcas de Google LLC; Thomson es marca de su titular. Este proyecto no está afiliado a ninguna de ellas.
