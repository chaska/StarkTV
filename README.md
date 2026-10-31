# Inicio TV

Launcher ligero para Android TV (probado para la Thomson 2K, Android TV 14, 1 GB de RAM).
Sin anuncios, sin librerías: el APK de release pesa unos 60 KB.

- Reloj y fecha, botón **Ajustes** y fila de **favoritas** (OK mantenido sobre una ficha: mover / quitar; ficha **+** para añadir).
- Fondos: **imagen** (jpg, png, webp…), **GIF animado**, **vídeo** (mp4, mkv, webm; en bucle y sin sonido) y **HTML5** (canvas/WebGL: partículas, humo…).
- El fondo se destruye al salir del inicio: mientras ves Netflix o la TDT no gasta RAM ni GPU.

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
