# Slidedify

App Android para ordenar canciones de Spotify en varias playlists con gestos tipo Tinder:
una canción por pantalla, se reproduce sola, y cada gesto la manda a la playlist configurada.

- Swipe hacia un destino lo agrega a esa playlist. El círculo se reparte en partes iguales entre las
  playlists y una zona de cancelar (1 playlist: mitades, 2: tercios, 3: cuartos… máximo 8).
- **✕ Omitir** abajo a la izquierda, **+ Playlist** abajo a la derecha (agregar o crear destinos).
- **Mantener presionado** abre una rueda tipo joystick donde está el dedo: empujar y volver al centro
  marca varias playlists; soltar las confirma; soltar en ✕ cancela.
- Doble tap ♥ Me gusta · tap play/pausa · ↶ deshacer.

📄 Propuesta de diseño, stack y arquitectura: [docs/PROPUESTA.md](docs/PROPUESTA.md)

## Estado

| Parte | Estado |
|---|---|
| Lógica de gestos (swipe, rueda, doble tap) | ✅ `core:domain`, con tests |
| Sesión de ordenado con deshacer | ✅ `core:domain`, con tests |
| Pantalla de swipe + rueda (Compose) | ✅ con datos y reproductor de prueba |
| Arquitectura multi-proveedor (`MusicProvider` + registro) | ✅ |
| Proveedor Spotify (login PKCE, Web API, reproducción) | 🧪 implementado, falta probar con una cuenta real |
| Letras (LRCLIB), cola offline (Room + WorkManager) | ⏳ |

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

## Cómo correrlo

Requisitos: JDK 21 y Android SDK (API 37).

```bash
./gradlew :core:domain:test            # tests de gestos y sesión
./gradlew :app:testDebugUnitTest       # tests del ViewModel
./gradlew :app:installDebug            # instala en un dispositivo conectado
```

La CI (GitHub Actions) corre tests, Lint y genera el APK de debug como artefacto descargable.
