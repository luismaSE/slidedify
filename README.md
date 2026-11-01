# Slidedify

App Android para ordenar canciones de Spotify en varias playlists con gestos tipo Tinder:
una canción por pantalla, se reproduce sola, y cada gesto la manda a la playlist configurada.

- Swipe ← omitir · ↑ ↗ → ↘ ↓ una playlist cada uno · doble tap ♥ Me gusta · tap play/pausa
- **Mantener presionado** abre una rueda tipo joystick donde está el dedo: empujar y volver al centro
  marca varias playlists; soltar las confirma; soltar en la mitad izquierda cancela.

📄 Propuesta de diseño, stack y arquitectura: [docs/PROPUESTA.md](docs/PROPUESTA.md)

## Estado

| Parte | Estado |
|---|---|
| Lógica de gestos (swipe, rueda, doble tap) | ✅ `core:domain`, con tests |
| Sesión de ordenado con deshacer | ✅ `core:domain`, con tests |
| Pantalla de swipe + rueda (Compose) | ✅ con datos y reproductor de prueba |
| Login Spotify, Web API, reproducción real | ⏳ siguiente paso |
| Letras (LRCLIB), cola offline (Room + WorkManager) | ⏳ |

## Módulos

```
app/            UI Compose, ViewModel, DI (Hilt), datos de prueba
core/model/     Modelos puros (Track, Playlist, Slot, GestureMap)
core/domain/    Gestos (GestureReducer, SectorLayout), SortSession, interfaces de repositorio y Player
```

`core:model` y `core:domain` son Kotlin puro: se testean sin emulador.

## Cómo correrlo

Requisitos: JDK 21 y Android SDK (API 37).

```bash
./gradlew :core:domain:test            # tests de gestos y sesión
./gradlew :app:testDebugUnitTest       # tests del ViewModel
./gradlew :app:installDebug            # instala en un dispositivo conectado
```

La CI (GitHub Actions) corre tests, Lint y genera el APK de debug como artefacto descargable.
