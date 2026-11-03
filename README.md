# Compass

> Antes llamada Slidedify (el id del paquete sigue siendo `com.slidedify.app`).

App Android para ordenar canciones de Spotify en varias playlists con gestos tipo Tinder:
una canción por pantalla, se reproduce sola, y cada gesto la manda a la playlist configurada.

- Swipe hacia un destino lo agrega a esa playlist. El círculo se reparte en partes iguales entre las
  playlists y una zona de **omitir** (1 playlist: mitades, 2: tercios, 3: cuartos… máximo 8).
  El borde de la pantalla se ilumina con el color de cada zona; al arrastrar, el nombre aparece en la zona.
- **✕ Omitir** abajo a la izquierda, **+ Playlist** abajo a la derecha (agregar o crear destinos).
- **Mantener presionado** abre una rueda tipo joystick donde está el dedo: empujar y volver al centro
  marca varias playlists; soltar las confirma. La zona izquierda se divide en Omitir (arriba) y Cancelar (abajo).
- Doble tap ♥ Me gusta · tap play/pausa · ↶ deshacer.

- **⇄ Transferir**: copiar, mover o sincronizar playlists (o Me gusta) entre servicios conectados.

📄 Análisis y propuestas de mejora: [docs/ANALISIS.md](docs/ANALISIS.md)
📄 Propuesta de diseño, stack y arquitectura: [docs/PROPUESTA.md](docs/PROPUESTA.md)

## Estado

| Parte | Estado |
|---|---|
| Lógica de gestos (swipe, rueda, doble tap) | ✅ `core:domain`, con tests |
| Sesión de ordenado con deshacer | ✅ `core:domain`, con tests |
| Pantalla de swipe + rueda (Compose) | ✅ con datos y reproductor de prueba |
| Arquitectura multi-proveedor (`MusicProvider` + registro) | ✅ |
| Proveedor Spotify (login, Web API, vistas previas) | ✅ probado con cuenta real |
| Transferencias entre servicios (copiar / mover / sincronizar) | 🧪 implementado y testeado, falta probar con dos cuentas reales |
| TIDAL y YouTube Music | 🧪 clientes testeados; se activan con su configuración (ver docs/ANALISIS.md §6) |
| Cola offline (Room + WorkManager) | ✅ |

## Módulos

```
app/                 Arma la app: DI y registro de proveedores
core/model/          Modelos puros e independientes del servicio (Track, Playlist, ProviderId, Slot)
core/domain/         Gestos, sesión con deshacer, contratos: MusicProvider, repositorios, Player, Lyrics
core/designsystem/   Tema y colores
feature/swipe/       Pantalla de swipe + rueda joystick y su ViewModel
provider/fake/       Proveedor de prueba (sin cuenta)
```

`core:model` y `core:domain` son Kotlin puro: se testean sin emulador.

### Agregar un proveedor (Spotify, YouTube Music, Tidal…)

1. Crear `provider/<nombre>/` como Android library e incluirlo en `settings.gradle.kts`.
2. Implementar `MusicProvider` (auth, canciones, playlists, biblioteca, reproductor) y declarar sus `ProviderCapabilities`.
3. Registrarlo con Hilt: `@Binds @IntoSet abstract fun bind(impl: MiProvider): MusicProvider` (ver `provider/fake`).
4. Agregar `implementation(project(":provider:<nombre>"))` en `app/build.gradle.kts`.

La pantalla de swipe y el dominio no cambian.

## Conectar Spotify

1. Crear una app en el [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
   (el dueño necesita Premium; en modo desarrollo se permiten 5 usuarios, que hay que agregar en *User Management*).
2. Agregar el Redirect URI **`slidedify://spotify-callback`** y marcar **Web API**.
3. Poner el Client ID en `local.properties` (no se sube al repo):
   ```properties
   SPOTIFY_CLIENT_ID=tu_client_id
   ```
   En CI se lee del secreto `SPOTIFY_CLIENT_ID`.

Sin Client ID la app funciona igual con el proveedor **Demo**. La reproducción con Spotify usa la
Web API sobre la app de Spotify del teléfono (Premium); algunos endpoints de 2026 están marcados
"verify" en `SpotifyEndpoints` hasta probarlos con una cuenta real.

## Sugerencias inteligentes

Al ordenar, Compass sugiere a qué playlist va cada canción ("✦ Sugerido: Relax") y explica por qué.
Nunca asigna sola: se acepta con «Enviar» o deslizando como siempre. Se apaga en Ajustes → Sugerencias.

Cómo decide (`core/domain/.../suggest`):

| Señal | De dónde sale | Peso |
|---|---|---|
| Artista ya presente en la playlist | Canciones de la playlist (hasta 400) | 0,40 |
| Energía (tranquila ↔ intensa) | Audio de la vista previa + etiquetas + BPM + volumen | 0,30 |
| Estilo (etiquetas) | Last.fm: "chill", "workout", "acoustic"… | 0,25 |
| Nombre de la playlist | Diccionario ES/EN: "gym", "relax", "estudiar"… | 0,15 |
| Tempo | Deezer (BPM) o el análisis de audio | 0,05 |

- Cada playlist destino tiene un **perfil** aprendido de una muestra de sus canciones, y aprende de
  cada canción que ordenás ahí.
- Solo sugiere si una opción gana con claridad (puntaje ≥ 0,5 y 0,1 por encima de la segunda).
- El análisis de audio (`analysis/audio`) se hace en el teléfono: decodifica la vista previa de
  30 s y mide volumen, brillo, ataques por segundo y tempo. No se sube nada.
- Los resultados quedan guardados (tabla `track_features`), así cada canción se analiza una vez.

**Clave de Last.fm (opcional, gratis):** creala en https://www.last.fm/api/account/create y
guardala como `LASTFM_API_KEY` (en `local.properties`, variable de entorno o secreto de GitHub
Actions). Sin clave, las sugerencias usan artista, audio, tempo y nombre de la playlist.

## Cómo correrlo

Requisitos: JDK 21 y Android SDK (API 37).

```bash
./gradlew :core:domain:test            # tests de gestos y sesión
./gradlew :app:testDebugUnitTest       # tests del ViewModel
./gradlew :app:installDebug            # instala en un dispositivo conectado
```

La CI (GitHub Actions) corre tests, Lint y genera el APK de debug como artefacto descargable.
