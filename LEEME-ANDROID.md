# WinampYT para Android

Es el mismo reproductor (mismo diseño, ecualizador, temas, listas y atajos), empaquetado como app de Android.

## Cómo obtener el APK

### Opción A: Android Studio (la más simple)
1. Instala Android Studio y abre esta carpeta (`File > Open`).
2. Espera a que termine "Gradle sync" (descarga todo solo la primera vez).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`. Pásalo al teléfono e instálalo
   (hay que permitir "instalar apps desconocidas").
   O conecta el teléfono con depuración USB y pulsa ▶ Run.

### Opción B: sin instalar nada (GitHub)
1. Sube esta carpeta a un repositorio nuevo de GitHub.
2. Pestaña **Actions > Build APK > Run workflow**.
3. Al terminar, descarga `WinampYT-debug-apk` en la sección *Artifacts*.

## Qué cambió respecto a Windows
| Windows | Android |
|---|---|
| `servidor.ps1` + `yt-dlp.exe` + `deno.exe` | Servidor interno en la app (NanoHTTPD) + NewPipeExtractor |
| Ventana con WebView2 (`host.cs`) | `WebView` en pantalla completa |
| Modo fantasma, siempre visible, icono de bandeja, barra de tareas | Notificación con ⏮ ⏯ ⏭, pantalla de bloqueo y botones de audífonos |
| Guardar MP3 (ffmpeg) | Guarda el audio original (M4A) en `Música/WinampYT` |
| Carpeta `cache` | Caché interna (máx. 400 MB, se limpia sola) |

- Atrás manda la app al fondo y la música sigue. Para cerrarla del todo usa **Cerrar** en la notificación.
- Las listas y el estado se guardan dentro de la app.

## Si deja de buscar o reproducir
YouTube cambia seguido. Sube la versión de NewPipeExtractor en `app/build.gradle.kts`
(última: https://github.com/TeamNewPipe/NewPipeExtractor/releases) y vuelve a compilar.

Uso personal: respeta los términos de YouTube y los derechos de autor de la música.
