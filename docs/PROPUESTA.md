# Slidedify — Propuesta de diseño

> App Android para ordenar canciones de Spotify en varias playlists con gestos tipo Tinder.
> Estado: propuesta v1 · Fecha: 2026-11-01

---

## 0. Resumen

| Tema | Decisión |
|---|---|
| Plataforma | Android nativo (minSdk 26, targetSdk 36) |
| Lenguaje / UI | Kotlin 2.x + Jetpack Compose + Material 3 |
| Arquitectura | Clean Architecture ligera + MVI (flujo de datos unidireccional), multi-módulo |
| Reproducción | **Spotify App Remote SDK** (controla la app de Spotify instalada) |
| Datos | **Spotify Web API** (Retrofit + kotlinx.serialization) |
| Auth | OAuth 2.0 **Authorization Code + PKCE** (sin client secret en la app) |
| Persistencia | Room (cola de acciones, sesiones) + DataStore (configuración) |
| Sincronización | Cola local "outbox" + WorkManager, escritura en lotes |
| Letras | **LRCLIB** (gratis, letras sincronizadas) — Spotify no expone letras |
| Recomendaciones | Fuentes propias (top tracks, recientes, Last.fm similares) — `/recommendations` ya no está disponible para apps nuevas |
| DI | Hilt |
| Calidad | ktlint + detekt, tests unitarios, tests de UI, screenshot tests, CI en GitHub Actions |

---

## 1. Restricciones de Spotify (leer primero)

Estas condiciones definen qué se puede construir y no son negociables:

1. **Endpoints eliminados para apps nuevas (nov. 2024):** `/recommendations`, `related-artists`, `audio-features`, `audio-analysis`, `preview_url` y playlists editoriales/algorítmicas de Spotify (Discover Weekly, etc.). → Las "canciones recomendadas" se arman con fuentes propias (ver §5.2).
2. **Modo desarrollo (feb. 2026):** el dueño de la app necesita **Spotify Premium** y el cupo es de **5 usuarios de prueba**. También se quitaron endpoints como *new releases*, *artist top tracks* y algunos *batch gets*. Para publicar en Play Store hace falta **Extended Quota**, que hoy se otorga muy selectivamente. → Planificar la app como herramienta personal/beta cerrada; diseñar la capa de datos aislada para poder adaptarse a cambios.
3. **Reproducción:** solo vía App Remote SDK (requiere la app de Spotify instalada). En cuentas Free, Spotify limita la reproducción on-demand en mobile (modo aleatorio), así que **la experiencia completa requiere Premium**. La app debe detectarlo y mostrar un aviso claro.
4. **Letras:** la Web API no tiene endpoint de letras.
5. **Branding:** mostrar logo/atribución de Spotify, no alterar la portada (no recortar, no aplicar filtros encima de la carátula en sí), incluir enlace "Abrir en Spotify".

> Revisar el changelog oficial antes de cada release: https://developer.spotify.com/documentation/web-api/references/changes

---

## 2. Producto

### 2.1 Flujo principal

```
Login Spotify ─▶ Elegir fuente ─▶ Configurar gestos ─▶ Sesión de swipe ─▶ Resumen
                 · Playlist propia     · hasta 3 (o 5)       · 1 canción por vez   · cuántas a cada
                 · Me gusta            playlists              · autoplay             playlist
                 · Recomendadas        · combos                                      · deshacer
```

### 2.2 Fuentes de canciones

- **Playlist existente** (propia o colaborativa): se recorre en orden o mezclada. Opción "quitar de la playlist original al clasificar" (útil para vaciar una playlist "bandeja de entrada").
- **Me gusta (Liked Songs):** caso de uso fuerte: ordenar una biblioteca enorme en sub-playlists.
- **Recomendadas:** mezcla generada por la app (§5.2).
- La sesión **filtra canciones ya clasificadas** (historial local) para no repetirlas.

---

## 3. Diseño de interacción (gestos)

### 3.1 Mapa por defecto

| Gesto | Acción |
|---|---|
| Doble tap | ❤️ Agregar a Me gusta (la tarjeta **no** se descarta) |
| Swipe ← izquierda | Omitir |
| Swipe ↑ arriba | Playlist A |
| Swipe ↗ arriba-derecha | Playlist B |
| Swipe → derecha | Playlist C |
| Swipe ↘ abajo-derecha | Playlist D |
| Swipe ↓ abajo | Playlist E |
| Arrastrar sin soltar sobre los indicadores del costado derecho | Varias playlists a la vez (§3.3) |
| Tap | Play / pausa |
| Botón ↶ o sacudir | Deshacer última acción |

Todo es configurable: el usuario puede reasignar cualquier dirección a cualquier playlist, a "omitir" o a un **combo** (§3.3). Se puede usar con menos de 5 playlists (las direcciones sin asignar quedan deshabilitadas).

### 3.2 ¿Diagonales? — Evaluación

Sí son viables, pero **como modo avanzado opcional**, no por defecto.

**Cómo se detectan:** al soltar el dedo se calcula el ángulo del desplazamiento (`atan2(dy, dx)`) y se asigna a un sector.

```
            ↑ A
     ↖ —         ↗ D
       \   ·   /          Modo 4 direcciones: sectores de 90°
 ← skip  ( ● )   → B      Modo 6 direcciones: ↑ → ↓ ← + ↗ ↘ (sectores de 60°)
       /       \          Modo 8 direcciones: sectores de 45° (no recomendado)
     ↙ —         ↘ E
            ↓ C
```

**Riesgos:** con 8 sectores de 45° la tasa de error sube mucho en uso con una mano (el pulgar hace arcos, no rectas). Las diagonales del lado izquierdo quedan pegadas a "omitir", y un error ahí hace perder la canción.

**Propuesta:**
- **5 playlists por defecto:** ↑ ↗ → ↘ ↓. Solo se usan diagonales del **lado derecho** (lejos de "omitir"). Sectores de 60° + **zona muerta** de ±7° en cada frontera: si el ángulo cae ahí, la tarjeta vuelve al centro en vez de adivinar.
- Si el swipe en diagonal resulta impreciso, el **riel de indicadores** (§3.3) es la alternativa exacta: llevar el dedo hasta el indicador elegido y soltar.
- **Feedback mientras arrastra** (clave para que funcionen las diagonales): a medida que se arrastra, se resalta en el riel del costado derecho (§3.3) el indicador de la playlist destino con su color e ícono, + vibración háptica corta al cambiar de sector. Así el usuario corrige antes de soltar.
- Umbral de distancia ≥ 30 % del ancho **o** velocidad (fling) ≥ umbral; si no, vuelve al centro.
- Métrica para decidir con datos: registrar (localmente) los "deshacer" inmediatos por dirección. Si las diagonales tienen >2× la tasa de deshacer de las cardinales, la UI sugiere usar el riel de indicadores en lugar del swipe en diagonal.

**Para más de 5 playlists:** se usan combos (§3.3) o se cambia el set de playlists entre sesiones. Mantener presionado queda reservado para el riel, así que no hay menú radial.

### 3.3 Asignar a varias playlists a la vez — Riel de indicadores

**Idea:** en el costado derecho hay un **riel vertical con 5 indicadores**, uno por playlist, ordenados igual que las direcciones (↑ arriba, ↗, →, ↘, ↓ abajo). Mientras el dedo **no se levanta**, el usuario va tocando 2 o más indicadores; **al levantar el dedo** la canción se agrega a todas las playlists marcadas. Mientras mantiene el dedo, aparece un botón **Cancelar** por si se confundió.

```
┌──────────────────────────────────┐
│                           ┌────┐ │
│                           │ ↑  │ │  A · Gym      ✓ (marcado)
│      ┌────────┐           │ ↗  │ │  B · Chill
│      │ mini   │  ✓Gym     │ →  │ │  C · Focus    ✓ (marcado)
│      │ portada│  ✓Focus   │ ↘  │ │  D · Party
│      └────────┘     ●←dedo│ ↓  │ │  E · Running
│                           └────┘ │
│ ┌──────────┐                     │
│ │✕ Cancelar│  ← aparece solo     │
│ └──────────┘    mientras se mantiene el dedo
└──────────────────────────────────┘
```

**Reglas del gesto:**

1. **Arrastre normal:** la tarjeta sigue al dedo. Si el usuario suelta sin haber marcado ningún indicador, se comporta como swipe simple (§3.2): se calcula la dirección, o vuelve al centro.
2. **Marcar un indicador:** el dedo tiene que **quedarse ~150 ms** sobre el indicador. Se llena un anillo de progreso, hay vibración corta y aparece un ✓. Esta pequeña espera es clave: para ir de ↑ a ↓ el dedo pasa por encima de ↗ → ↘. Sin la espera, esos se marcarían por error. Por la misma razón, un swipe rápido hacia la derecha que cruza el riel no marca nada.
3. **Desmarcar:** quedarse otra vez ~150 ms sobre un indicador ya marcado lo desmarca.
4. **Mientras hay marcas:** la tarjeta se achica a una mini portada que sigue al dedo, con las playlists marcadas apiladas como etiquetas ("✓ Gym · ✓ Focus").
5. **Levantar el dedo** en cualquier lugar (salvo sobre Cancelar) → se agrega a **todas** las marcadas. Con una sola marcada, se agrega solo a esa: sirve como alternativa precisa a los swipes en diagonal.
6. **Cancelar:** el botón **✕ Cancelar** aparece abajo a la izquierda (lejos del riel) en cuanto se marca el primer indicador, o al mantener el dedo quieto ≥ 400 ms. Si se suelta sobre él, se descarta todo y la tarjeta vuelve al centro. Es grande (≥ 88 dp) y vibra al entrar. Los swipes rápidos nunca lo ven, así que no interfiere con "omitir".
7. **Zonas táctiles:** cada indicador mide ≥ 56 dp de alto, con 8 dp de separación. Para que cuente como "adentro", el dedo tiene que entrar al menos 8 dp (histéresis), así el temblor en el borde no marca y desmarca solo.

**Estados (máquina de estados, función pura y testeable):**

```
Idle ──down──▶ Pressing ──move > slop──▶ Dragging
                  │ hold ≥400ms               │ dwell 150ms en indicador
                  ▼                           ▼
              Holding(cancel visible) ◀──▶ MultiSelect(selected: Set, cancel visible)
                                              │
   up sin marcas → clasificar swipe (§3.2)    │ up sobre Cancelar → Idle (sin cambios)
                                              │ up en otro lugar → Commit(selected) → Idle
```

**Detalles:**
- **Zurdos:** un ajuste refleja la pantalla (riel a la izquierda, omitir con swipe a la derecha, Cancelar abajo a la derecha).
- **Descubrimiento:** el riel está siempre visible pero discreto (íconos de 50 % de opacidad). Mientras se arrastra se agranda y muestra los nombres. La primera vez, un tutorial interactivo de 3 pasos.
- **Combos (complemento):** en la configuración una dirección también puede apuntar a un grupo, p. ej. ↑ = "Gym + Running", para las combinaciones que se repiten mucho.
- **Accesibilidad:** en el "modo botones" el riel se vuelve una lista de casillas que se tocan y un botón "Agregar".

**Alternativas evaluadas y descartadas:** botones de playlist abajo de la tarjeta (un toque extra por playlist y ocupan espacio), swipe con 2 dedos (difícil de descubrir) y menú radial multi-selección (lento).

### 3.4 Tras cada acción
- Animación de salida hacia la dirección + toast breve "Agregada a **Gym**" con botón **Deshacer** (5 s).
- La siguiente tarjeta ya está precargada (portada en caché, letra pedida), y el autoplay arranca en < 300 ms.
- Accesibilidad: todos los gestos tienen equivalente en botones (modo "botones" para usuarios con TalkBack o motricidad reducida). Acciones personalizadas de accesibilidad (`semantics { customActions }`) en la tarjeta.

---

## 4. Diseño visual

### 4.1 Pantalla principal (swipe)

```
┌──────────────────────────────────┐
│ ←  Ordenando: "Liked Songs"  12/430│  barra superior + progreso
│                        ↶   ⚙      │
│      ┌──────────────┐      ┌──┐ │
│      │              │      │↑ │ │  riel de indicadores (§3.3):
│      │   PORTADA    │      │↗ │ │  color + ícono de cada playlist.
│ skip │   (album)    │      │→ │ │  Se ilumina el destino al
│      │              │      │↘ │ │  arrastrar; se agranda y muestra
│      └──────────────┘      │↓ │ │  nombres mientras el dedo está abajo
│                            └──┘ │
│   Título de la canción     ❤      │
│   Artista · Álbum · 2019          │
│   ▶ ━━━━━━━━━○───── 1:12 / 3:40   │  progreso (seek)
│ ┌──────────────────────────────┐  │
│ │ ...the night is young,       │  │  letra sincronizada (3 líneas,
│ │ ▶ and we are running free    │  │  la actual resaltada).
│ │ under neon skies...          │  │  Tap = pantalla completa
│ └──────────────────────────────┘  │
│                      ♫ Spotify    │  atribución
└──────────────────────────────────┘
```

- **Hoja inferior expandible** (swipe sobre la hoja, no sobre la tarjeta, para no chocar con gestos): letra completa, info del artista (géneros, seguidores, imagen), álbum (año, sello, lista de temas), botón "Abrir en Spotify".
- **Color dinámico:** el fondo toma la paleta de la portada (androidx.palette) con gradiente; la portada en sí no se modifica.
- **Tema oscuro por defecto** (contexto musical), soporte de tema claro y Material You.
- Tipografía: escala M3; títulos largos con marquee.

### 4.2 Otras pantallas
1. **Onboarding / Login:** explica qué hace la app, pide permisos de Spotify, verifica que la app de Spotify esté instalada y si la cuenta es Premium.
2. **Elegir fuente:** tabs Playlists / Me gusta / Recomendadas.
3. **Configurar gestos:** diagrama interactivo tipo brújula; tocar una dirección → elegir playlist existente, crear nueva, u "omitir", o combo. Elegir combos y modo zurdo. Vista previa para practicar el riel y los swipes.
4. **Resumen de sesión:** conteos por playlist, lista de acciones, deshacer individual.
5. **Ajustes:** autoplay on/off, empezar canción desde el estribillo (saltar a 30 %, opcional), háptica, quitar de origen, modo botones.

---

## 5. Arquitectura técnica

### 5.1 Módulos

```
:app                      → navegación, DI raíz, MainActivity
:core:model               → modelos de dominio puros (Track, Playlist, SwipeAction…)
:core:domain              → casos de uso + interfaces de repositorio (Kotlin puro, sin Android)
:core:data                → implementaciones de repositorio, mapeo DTO → dominio
:core:network             → Retrofit, cliente Spotify, cliente LRCLIB, cliente Last.fm, auth interceptor
:core:database            → Room (outbox, historial, caché)
:core:datastore           → preferencias + config de gestos (DataStore + Proto/serialization)
:core:player              → wrapper de Spotify App Remote detrás de una interfaz `Player`
:core:auth                → PKCE, refresh de tokens, almacenamiento cifrado
:core:designsystem        → tema, componentes, íconos
:core:testing             → fakes, reglas, datos de prueba
:feature:onboarding
:feature:source           → elegir playlist / fuente
:feature:gestureconfig
:feature:swipe            → pantalla principal
:feature:summary
:feature:settings
build-logic/              → convention plugins de Gradle
```

Regla de dependencias: `feature → domain ← data`. Las features nunca dependen de `data` ni del SDK de Spotify directamente.

### 5.2 Fuente de "recomendadas" (sin `/recommendations`)
`RecommendationSource` combina y deduplica:
1. `GET /me/top/tracks` (short/medium term) y `GET /me/player/recently-played`.
2. **Last.fm `track.getSimilar` / `artist.getSimilar`** (API gratuita) a partir de los tops → se buscan en Spotify con `GET /search` para obtener el URI.
3. Excluir lo que ya está en Me gusta o ya fue clasificado.
Queda detrás de una interfaz: si Spotify habilita algo nuevo, se cambia la implementación sin tocar la UI.

### 5.3 Reproducción
- `interface Player { connect(); play(uri); pause(); seek(ms); state: StateFlow<PlayerState> }`.
- Implementación `SpotifyAppRemotePlayer`: conecta en `onStart`, desconecta en `onStop` (lifecycle-aware), expone `PlayerState` (posición, pausa, track actual) como `Flow`.
- Autoplay: cuando la tarjeta superior cambia, el ViewModel llama `play(uri)`. Debounce de ~150 ms para swipes rápidos.
- Errores manejados: Spotify no instalado → link a Play Store; cuenta Free → aviso; desconexión → reintento con backoff.

### 5.4 Escrituras: patrón Outbox
Cada swipe **no** llama a la API directamente:
1. Se guarda una `PendingAction(trackUri, playlistIds, type, createdAt)` en Room → la UI es instantánea y funciona sin red.
2. Un `SyncWorker` (WorkManager, restricción de red) agrupa por playlist y envía **lotes de hasta 100 URIs** por request.
3. Respeta `429` + `Retry-After` (y el nuevo `reason: QUOTA_EXCEEDED`) con backoff exponencial.
4. "Deshacer" antes de sincronizar = borrar de la outbox; después = request de eliminación.
5. Se evita duplicar: antes de agregar se consulta un índice local de contenido de cada playlist destino.

### 5.5 Autenticación
- Authorization Code + **PKCE** con Custom Tabs (AppAuth o la librería de auth de Spotify). Redirect URI con App Link verificado o esquema propio.
- Scopes mínimos: `user-library-read user-library-modify playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-top-read user-read-recently-played app-remote-control user-read-private`.
- Tokens cifrados con **Android Keystore + Tink** guardados en DataStore (EncryptedSharedPreferences está deprecado).
- `Authenticator` de OkHttp para refrescar el token ante 401 con un mutex (un solo refresh concurrente).

### 5.6 Letras
- `LyricsRepository` → LRCLIB (`/api/get?track_name&artist_name&album_name&duration`), parseo de LRC a `List<LyricLine(timeMs, text)>`.
- Sincronización con `PlayerState.position`. Caché en Room. Si no hay letra: "Letra no disponible".
- **Licencias:** LRCLIB es comunitario; para una publicación comercial evaluar un proveedor licenciado (p. ej. Musixmatch).

### 5.7 Estado de UI (MVI)
```kotlin
data class SwipeUiState(
    val current: TrackCard?,
    val upcoming: List<TrackCard>,       // 2–3 precargadas
    val gestureMap: GestureMap,
    val drag: DragState,                 // Idle | Dragging | Holding | MultiSelect(selected, cancelVisible)
    val player: PlayerState,
    val lyrics: LyricsState,
    val pendingSync: Int,
)
sealed interface SwipeIntent {
    data class Swiped(val direction: Direction) : SwipeIntent
    data object DoubleTapped : SwipeIntent
    data class MultiSelectCommitted(val playlists: Set<PlaylistId>) : SwipeIntent
    data object Undo : SwipeIntent
}
```
El ViewModel expone `StateFlow<SwipeUiState>` y recibe `SwipeIntent`; la detección de gestos (Compose `pointerInput` + `Animatable`) traduce eventos crudos a `Direction` mediante una función pura `classify(dx, dy, velocity, mode): Direction?`. El riel de multi-selección es un reductor puro `reduce(DragState, PointerEvent(x, y, t, type)): DragState` que no depende de Compose; la UI solo le pasa los eventos del puntero y las posiciones de los indicadores. Ambos se testean sin emulador.

---

## 6. Stack (versiones al momento de iniciar)

| Área | Librería |
|---|---|
| Build | Gradle (Kotlin DSL) + Version Catalog + convention plugins, AGP actual |
| UI | Compose BOM, Material 3, Navigation Compose (rutas type-safe), Coil 3 |
| Color | androidx.palette |
| Async | Coroutines + Flow |
| DI | Hilt (+ hilt-navigation-compose, hilt-work) |
| Red | Retrofit + OkHttp + kotlinx.serialization |
| DB | Room (KSP) |
| Prefs | DataStore |
| Background | WorkManager |
| Spotify | Spotify App Remote SDK (+ Auth) |
| Seguridad | Tink |
| Logs | Timber (sin datos personales en release) |
| Crash | Firebase Crashlytics o Sentry (opcional) |
| Tests | JUnit 5, MockK, Turbine, kotlinx-coroutines-test, Compose UI Test, Roborazzi (screenshots), MockWebServer |
| Calidad | ktlint, detekt, Android Lint, Dependabot/Renovate |
| CI | GitHub Actions: lint → tests → build → (release firmado manual) |

---

## 7. Buenas prácticas

- **Dominio puro y testeable:** `core:domain` sin dependencias de Android.
- **Inyección por interfaces** (`Player`, `MusicRepository`, `LyricsRepository`) → fakes en tests y UI previews.
- **Errores tipados:** `sealed interface AppError` (Network, Auth, RateLimited, SpotifyNotInstalled, PremiumRequired…). Nada de excepciones crudas hasta la UI.
- **Sin secretos en el repo:** client ID en `local.properties`/secrets de CI; PKCE elimina el client secret.
- **Offline-first** con outbox; la UI nunca espera a la red para avanzar.
- **Accesibilidad:** content descriptions, alternativas a gestos, contraste AA, tamaños táctiles ≥ 48dp.
- **Rendimiento:** precarga de 2–3 tarjetas, imágenes con tamaño exacto, Baseline Profiles, R8 en release.
- **Privacidad:** el historial de acciones se guarda solo en el dispositivo; política de privacidad clara (requisito de Spotify y Play Store).
- **Git:** trunk-based, PRs chicos, Conventional Commits, CI obligatoria para mergear.
- **Tests prioritarios:** `classify()` de gestos (tabla de ángulos), reductor del riel (pasar por encima sin marcar, espera, desmarcar, cancelar, soltar), outbox/sync (lotes, 429, deshacer), mapeo de DTOs, ViewModel del swipe.

---

## 8. Roadmap

| Fase | Contenido |
|---|---|
| **0 · Setup** (1 sem) | Proyecto multi-módulo, CI, design system base, registro app en Spotify Dashboard |
| **1 · MVP** (3–4 sem) | Login PKCE, elegir playlist/Me gusta, swipe 5 direcciones + doble tap, autoplay, outbox + sync, deshacer |
| **2 · Riqueza** (2 sem) | Letras sincronizadas, hoja de detalle de artista/álbum, color dinámico, resumen de sesión |
| **3 · Avanzado** (2 sem) | Riel de multi-selección con Cancelar, combos, modo zurdo, recomendadas |
| **4 · Pulido** | Accesibilidad, Baseline Profiles, screenshot tests, beta cerrada (≤ 5 usuarios) |

---

## 9. Riesgos y preguntas abiertas

1. **Cupo de 5 usuarios** en modo desarrollo: ¿es una app personal o se apunta a publicarla? Publicarla depende de que Spotify otorgue Extended Quota.
2. **Premium:** sin Premium la reproducción on-demand no funciona bien → ¿se acepta como requisito?
3. **Letras:** LRCLIB alcanza para uso personal; para uso comercial hay que licenciar.
4. **API cambiante:** Spotify cambió endpoints en 2024 y 2026; la capa `data` aislada mitiga esto, pero hay que monitorear el changelog.
5. **Conflicto de gestos:** doble tap vs. tap (play/pausa) introduce ~250 ms de espera en el tap simple. Alternativa: play/pausa solo con botón.
