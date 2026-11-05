# Análisis de la app y propuestas

> **Documento histórico.** Describe la propuesta o el análisis en su momento; parte ya cambió.
> El estado actual está en el [README](../README.md); activar servicios, en [SERVICIOS.md](SERVICIOS.md).

Fecha: 2026-11-01 · Estado analizado: rama `claude/spotify-mobile-tinder-gestures-meog4o`.

## 1. Qué está bien hoy

- **El gesto central funciona como idea**: swipe hacia una zona + rueda joystick para varias playlists, con la misma geometría en los dos modos. Es el diferencial de la app.
- **Arquitectura sana**: dominio en Kotlin puro y testeado (gestos, sesión, cola offline, transferencias), proveedores intercambiables (Demo, Spotify, TIDAL, YouTube Music), escrituras offline con reintentos.
- **Progreso persistente**: se puede cortar y retomar; la lista ☰ deja saltar a cualquier canción.

## 2. Estética: problemas y mejoras

| # | Problema | Propuesta | Esfuerzo |
|---|---|---|---|
| E1 | Los controles usan caracteres de texto (↶ ⚙ ☰ ⟳ ▶ ✕) en vez de íconos: tamaños y pesos desparejos, y TalkBack los lee mal. | Usar íconos vectoriales consistentes (Material Symbols redondeados) con `contentDescription`. | Bajo |
| E2 | Barra superior saturada (volver, título, contador, lista, deshacer, ajustes). | Dejar: volver · título + servicio · contador. Mover lista/deshacer/ajustes a una fila de íconos abajo o a un menú. | Bajo |
| E3 | Barra inferior con botones de texto de anchos distintos. | Tres botones circulares fijos: Omitir (izq.), Play grande (centro), + Playlist (der.), con etiqueta chica debajo. | Bajo |
| E4 | La tarjeta no anticipa la siguiente canción. | Mostrar el borde de la siguiente tarjeta "debajo" (efecto pila) y animar su entrada; da sensación de ritmo. | Medio |
| E5 | Fondo gris plano y tarjeta sin relación con la portada. | Tomar el color dominante de la portada (androidx.palette) para teñir muy suave el fondo y la línea del reproductor. | Bajo |
| E6 | Las miniaturas de YouTube son 16:9 y quedan recortadas raro. | Para fuentes de video, encuadrar al centro con fondo desenfocado de la misma imagen. | Bajo |
| E7 | Sin identidad: no hay ícono de app, nombre definitivo ni pantalla de inicio. | Elegir nombre (ver propuestas de nombre y logo) y aplicar ícono adaptativo, splash y tipografía de marca para títulos. | Medio |
| E8 | Nombres largos de playlists se cortan en la rueda. | Abreviaturas automáticas + nombre completo al pasar el dedo (como en el contorno). | Bajo |
| E9 | El borde iluminado solo muestra colores; un usuario nuevo no sabe qué es cada color. | Durante el primer uso, mostrar los nombres en el contorno 2-3 segundos al abrir la canción. | Bajo |

## 3. Funcionalidad: problemas y mejoras

| # | Problema | Propuesta | Prioridad |
|---|---|---|---|
| F1 | Al agregar una canción que ya está en la playlist destino, Spotify la duplica. | Antes de escribir, consultar (con caché) el contenido de cada destino y saltear duplicados; mostrar "ya está en X" en la zona. | Alta |
| F2 | Endpoints de Spotify, TIDAL y YouTube marcados "verify" (cambios de 2026 sin documentación oficial a mano). | Probar cada operación con cuentas reales y ajustar `SpotifyEndpoints` / `TidalEndpoints`. Agregar tests de contrato por proveedor. | Alta |
| F3 | Si la sincronización falla muchas veces, los cambios quedan en la cola sin aviso (solo el contador ⟳). | Pantalla "Cambios pendientes" con estado por cambio, reintentar y descartar; reintento periódico con WorkManager. | Alta |
| F4 | Solo se pueden ordenar playlists propias o colaborativas. | Permitir como **fuente** cualquier playlist (también las que seguís); solo los destinos necesitan ser editables. | Media |
| F5 | Ordenar una "bandeja de entrada" deja las canciones también en el origen. | Opción "quitar del origen al ordenar" por fuente. | Media |
| F6 | Si una canción no tiene vista previa, no suena y no hay indicio. | Indicador mínimo (ícono tachado en el botón de play), sin texto. | Media |
| F7 | Deshacer solo cubre la sesión actual. | Historial por fuente (últimas 200 acciones) con deshacer desde la lista ☰. | Baja |
| F8 | No hay pruebas automáticas de pantalla ni de migraciones de base de datos. | Screenshot tests (Roborazzi) de swipe, rueda y ajustes; test de migración Room; versionar el esquema exportado. | Media |
| F9 | Sin análisis estático de estilo. | ktlint + detekt en CI. | Baja |
| F10 | Modo desarrollo de Spotify: máximo 5 usuarios. | Si se publica: política de privacidad, solicitar Extended Quota, y priorizar TIDAL/YouTube como alternativas. | Alta (si se publica) |

## 4. Funcionalidades nuevas propuestas

1. **"Me gusta sin hogar"**: fuente automática con las canciones de Me gusta que no están en ninguna de tus playlists. Es exactamente el caso de uso de ordenar.
2. **Limpieza de playlist**: recorrer una playlist y decidir quedarse / sacar (izquierda = quitar de esa playlist). Reusa toda la UI.
3. **Duplicados**: detectar la misma canción repetida en una playlist o entre playlists (con el emparejador de transferencias) y resolver con un gesto.
4. **Sugerencia de destino**: resaltar la zona más probable según dónde mandaste antes canciones del mismo artista o álbum (sin IA externa, solo historial local).
5. **Sincronización automática programada** entre servicios (ej.: "Gym" de Spotify ↔ "Gym" de TIDAL cada noche), usando la función de transferencias.
6. **Corrección manual en transferencias**: para cada canción "no encontrada", buscarla a mano y elegir el resultado.
7. **Resumen de sesión**: cuántas canciones por playlist, tiempo y racha; compartible.
8. **Modo una mano extremo**: rueda que aparece al tocar (sin esperar) en un modo experto.
9. **Caché de vistas previas**: precargar la siguiente para que suene al instante.
10. **Votación con amigos**: una playlist compartida donde cada uno desliza y entra lo que tiene mayoría.

## 5. Orden sugerido

| Etapa | Contenido |
|---|---|
| Ahora | F1 duplicados, F2 verificación de endpoints con cuentas reales, E1–E3 íconos y barras |
| Siguiente | F3 cambios pendientes, F6, E4–E5, función 1 ("Me gusta sin hogar") y 2 (limpieza) |
| Después | Identidad (E7), funciones 3–6, F8–F9 |
| Si se publica | F10, política de privacidad, Extended Quota |

## 6. Activar TIDAL y YouTube Music

- **TIDAL**: crear una app en developer.tidal.com, redirect `slidedify://tidal-callback`, y poner `TIDAL_CLIENT_ID=...` en `gradle.properties` o `local.properties`.
- **YouTube Music**: proyecto en Google Cloud con *YouTube Data API v3* habilitada, pantalla de consentimiento OAuth (con tu cuenta como usuario de prueba) y un cliente OAuth **Android** con paquete `com.slidedify.app` y SHA-1 `50:C8:C4:FB:D0:38:D3:1A:C9:1C:3B:2D:63:30:D9:8F:47:10:20:16`. Después `YOUTUBE_ENABLED=true`. Ojo con la cuota diaria (10.000 unidades: una búsqueda cuesta 100, agregar una canción 50).
