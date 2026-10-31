# Stark TV

Launcher ligero para Android TV (probado para la Thomson 2K, Android TV 14, 1 GB de RAM).
Sin anuncios y sin librerías: el APK de release pesa 1,2 MB, casi todo por las 10 fuentes.

- Carrusel con **Favoritos** primero (cuadrícula 3×2, máximo 6) y luego todas las apps.
- **OK mantenido** sobre una app: añadir/quitar de favoritos, **mover** (la ficha se levanta y viaja con ◀ ▶; OK la suelta), ocultar e información.
- Fichas en 3D: brillo de cristal, sombra, halo del color de la app, luz que gira por el borde, destello y reflejo en el suelo. Un foco de luz del color de la app activa la sigue por la pantalla.
- Ajustes al estilo iPhone: interruptores y opciones a la vista que se cambian con ◀ ▶. Dock en 6 posiciones, 4 tamaños (micro a grande) y 10 tipografías (Google Fonts, licencia OFL en `assets/fuentes/licencias`).
- Ventanita opcional con la TDT en directo (sin sonido).
- Reloj, fecha y **tiempo** (Open-Meteo, gratis y sin clave; la ciudad se pone en ajustes).
- **Ajustes** → Ajustes generales (los de la TV) y Ajustes del launcher: fondo, brillo, reloj, tiempo, ciudad, nombres, tamaño, apps ocultas, cambiar fondo solo, salvapantallas, color de la selección, reflejo, cristal difuminado, animaciones y copia de ajustes (`/sdcard/InicioTV/copia.json`).
- Fondos: **imagen** (jpg, png, webp…), **GIF animado**, **vídeo** (mp4, mkv, webm; en bucle y sin sonido) y **HTML5** (canvas/WebGL: partículas, humo…).
- El fondo se destruye al salir del inicio: mientras ves Netflix o la TDT no gasta RAM ni GPU.
- Si la TV va justa: apagar «Reflejo en el suelo», «Cristal difuminado» y «Animaciones de luz» en Ajustes del launcher.

## Dónde poner los fondos

El launcher busca en:

- `/sdcard/Fondos/`
- `/sdcard/Download/` (donde dejan los archivos Send Files y similares)
- `Fondos/` en la raíz de cualquier USB

Un HTML con sus imágenes va en una subcarpeta con `index.html`: `Fondos/mio/index.html` + `Fondos/mio/foto.jpg`.
Los dos HTML incluidos (`app/src/main/assets/fondos/`) sirven de plantilla: tienen arriba `FOTO`, color, cantidad, etc.

Subir un fondo desde el PC:

```bash
adb push mi_fondo.mp4 /sdcard/Fondos/
```

## Primera instalación

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set com.chaska.inicio MANAGE_EXTERNAL_STORAGE allow
adb shell cmd package set-home-activity com.chaska.inicio/.MainActivity
```

Luego pulsar el botón Inicio del mando. Si sigue saliendo el inicio de Google:

```bash
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Volver al de Google:

```bash
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

## Compilar

Android Studio con el SDK 35. Desde la terminal: `gradlew.bat assembleRelease` (el release va firmado con la clave de depuración para poder instalarlo por adb).
