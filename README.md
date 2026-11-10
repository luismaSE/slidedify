# Compass

App Android para ordenar tu música en playlists con gestos: una canción por pantalla, la escuchás
y la mandás a su playlist deslizando. Funciona con Spotify (y está preparada para TIDAL y YouTube
Music). El id del paquete sigue siendo `com.slidedify.app` (nombre original del proyecto).

📲 Última versión de prueba: [slidedify-debug.apk](https://github.com/luismaSE/slidedify/releases/download/debug-latest/slidedify-debug.apk)

## Qué hace

**Ordenar**
- Deslizar hacia una playlist la agrega ahí. El círculo se reparte entre tus destinos (hasta 8) y
  una zona gris de **omitir**; el borde de la pantalla muestra el color de cada zona.
- **Varias a la vez**: sin soltar, pasá por varias zonas (como en la rueda). Volver a pasar por una
  la quita; con algo elegido, la zona gris se divide en *Omitir* y *Cancelar*.
- **Rueda** (mantener apretado), botón **Varias** con casillas, doble toque = Me gusta, deshacer.
- **Mover en vez de copiar** (opcional): al ordenar una playlist tuya, la canción sale de ella.
- Vista previa de 30 s (Deezer, buscada por ISRC para que sea la misma grabación) y botón para
  escuchar la canción completa en Spotify.
- Lista de canciones con orden (lista, agregadas, título, artista, álbum, duración; ↑/↓), filtros
  y búsqueda. El orden elegido es el orden en que salen las canciones y se recuerda por lista.

**Sugerencias**: propone la playlist que mejor encaja con cada canción y explica por qué
(ver [Sugerencias](#sugerencias)).

**Inicio**: "Seguí donde quedaste", recientes, tus playlists y las que seguís, con progreso.
**Transferir**: copiar, mover o sincronizar playlists entre servicios. **Conexiones** y **Ajustes**
(gestos, accesibilidad, tema claro/oscuro, sugerencias, datos móviles).

Además: progreso guardado y copia de seguridad, funciona sin conexión (los cambios se envían en
tandas), nunca agrega una canción dos veces, horizontal y tablets, español e inglés, y si la app
se cierra muestra el error para copiarlo o enviarlo.

## Correrlo en tu PC

1. Instalá [Android Studio](https://developer.android.com/studio) (incluye el JDK y el SDK).
2. *File → Open* y elegí la carpeta del proyecto. Esperá a que termine el *Gradle sync*.
3. Conectá tu teléfono por USB con la *depuración USB* activada
   (Ajustes → Acerca del teléfono → tocá 7 veces "Número de compilación" → Opciones de desarrollador),
   o creá un emulador en *Device Manager*.
4. Elegí la configuración **app** y tocá ▶ *Run*.

Si ya tenés instalado el APK de GitHub, se actualiza encima sin perder datos (los dos se firman con
`debug.keystore`).

Desde la terminal (JDK 21 y Android SDK con API 37):

```bash
./gradlew test                 # todos los tests unitarios
./gradlew lintDebug            # análisis estático
./gradlew :app:installDebug    # compila e instala en el teléfono conectado
```

## Configuración

Las claves se leen, en este orden, de `gradle.properties`, `local.properties` (no se sube) o
variables de entorno; en GitHub Actions, de los secretos del repo.

| Clave | Para qué | Sin ella |
|---|---|---|
| `SPOTIFY_CLIENT_ID` | Login con Spotify | Solo el servicio Demo |
| `LASTFM_API_KEY` | Etiquetas de estilo para sugerencias | Sugiere con artista, audio, tempo y nombre |
| `TIDAL_CLIENT_ID` | Activa TIDAL | TIDAL aparece como "Próximamente" |
| `YOUTUBE_ENABLED=true` | Activa YouTube Music | YouTube aparece como "Próximamente" |

Cómo obtener cada una: Spotify abajo; TIDAL y YouTube en [docs/SERVICIOS.md](docs/SERVICIOS.md);
Last.fm en <https://www.last.fm/api/account/create> (solo el *API key*).

### Spotify

1. Creá una app en el [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Redirect URI: **`slidedify://spotify-callback`**; API: **Web API** y **Android**
   (paquete `com.slidedify.app`, SHA-1 de `debug.keystore`).
3. En modo desarrollo solo entran las cuentas que agregues en *User Management* (cupo chico).

## Sugerencias

| Señal | De dónde sale | Peso |
|---|---|---|
| Artista ya presente en la playlist | Canciones de la playlist (hasta 400) | 0,40 |
| Energía (tranquila ↔ intensa) | Audio de la vista previa + etiquetas + BPM + volumen | 0,30 |
| Estilo (etiquetas) | Last.fm: "chill", "workout", "acoustic"… | 0,25 |
| Nombre de la playlist | Diccionario ES/EN: "gym", "relax", "estudiar"… | 0,15 |
| Tempo | Deezer (BPM) o el análisis de audio | 0,05 |

Cada destino tiene un perfil aprendido de una muestra de sus canciones y de cada canción que
ordenás ahí. Solo sugiere si una opción gana con claridad (≥ 0,5 y 0,1 sobre la segunda); nunca
asigna sola. El audio se analiza en el teléfono (nada se sube), por defecto solo con Wi-Fi, y cada
canción se analiza una vez (tabla `track_features`).

## Arquitectura

Kotlin + Jetpack Compose, Hilt, Room, DataStore, WorkManager, Coil, OkHttp. Arquitectura limpia
multi-módulo: la lógica es Kotlin puro y se testea sin emulador; cada servicio es un módulo que se
enchufa sin tocar el resto.

```
app/                    Arma la app: navegación, DI, tema, reporte de errores
core/model/             Modelos (Track, Playlist, GestureMap…)                       · Kotlin puro
core/domain/            Gestos, sesión con deshacer, sugerencias, transferencias,
                        contrato MusicProvider, cola de escrituras                    · Kotlin puro
core/oauth/             Login PKCE y renovación de tokens                             · Kotlin puro
core/testing/           Fakes compartidos para tests                                  · Kotlin puro
core/data/              Room: cola de escrituras, progreso, análisis de canciones
core/datastore/         Ajustes
core/platform/          Login en WebView, tokens cifrados con el Keystore
core/media/             Reproductor de vistas previas y decodificación de audio
core/designsystem/      Tema claro/oscuro, íconos, componentes
analysis/audio/         Análisis de audio (energía, brillo, tempo)                    · Kotlin puro
preview/deezer/         Vistas previas, BPM y volumen desde Deezer                    · Kotlin puro
suggest/lastfm/         Etiquetas de estilo de Last.fm                                · Kotlin puro
feature/source/         Inicio
feature/swipe/          Pantalla de ordenar, rueda, lista de canciones, sugerencias
feature/connections/    Conexiones
feature/settings/       Ajustes y editor de destinos
feature/transfer/       Transferencias entre servicios
provider/*-api/         Clientes de Spotify, TIDAL y YouTube                          · Kotlin puro
provider/*/             Su login y registro en la app (Android)
provider/fake/          Servicio Demo, sin cuenta
```

**Agregar un servicio**: crear `provider/<nombre>-api` (implementa `MusicProvider` y declara sus
`ProviderCapabilities`) y `provider/<nombre>` (login + `@IntoSet` en Hilt), e incluirlos en
`settings.gradle.kts` y `app/build.gradle.kts`. Las pantallas no cambian.

**Textos**: todos en `res/values/strings.xml` de cada módulo (español) con su traducción en
`values-en`. Para otro idioma, agregar `values-xx` y sumarlo en `app/src/main/res/xml/locales_config.xml`.

## Calidad

En cada cambio, GitHub Actions corre los tests unitarios, Lint, arma el APK y lo recorre en
emuladores con Android 14 y 16 (`ci/smoke.sh`: bienvenida → demo → ordenar → varias → cada
pestaña → reabrir), y publica el APK en el pre-release `debug-latest`.

## Publicar (pendiente)

- **Spotify**: pedir *Extended quota* para salir del modo desarrollo (cupo chico de usuarios); desde 2025 se otorga sobre todo a organizaciones.
- Versión *release*: firma propia, número de versión automático y prueba con R8.
- Política de privacidad (la piden Google Play y Spotify) y revisar las condiciones de uso de
  las vistas previas de Deezer y de la API de Last.fm.

## Documentos

- [docs/SERVICIOS.md](docs/SERVICIOS.md): activar TIDAL y YouTube Music.
- [docs/PROPUESTA.md](docs/PROPUESTA.md) y [docs/ANALISIS.md](docs/ANALISIS.md): propuesta
  inicial y análisis intermedio (históricos; este README describe el estado actual).

## Licencia

[Apache 2.0](LICENSE) © 2026 luismaSE. Podés usar, modificar y distribuir el código, también en
productos propios, manteniendo el aviso de licencia y el archivo [NOTICE](NOTICE). Spotify, TIDAL,
YouTube, Deezer y Last.fm son marcas de sus dueños; este proyecto no está afiliado a ellos.
