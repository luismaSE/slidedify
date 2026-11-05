# Activar TIDAL y YouTube Music

El código de los dos servicios ya está en la app (login, playlists, agregar/quitar, búsqueda para
transferencias, con tests). Están **apagados** porque cada servicio exige registrar la app con una
cuenta de desarrollador propia; eso no se puede hacer desde el código. Mientras tanto aparecen en
*Conexiones → Próximamente*.

Cuando cargues la clave, la próxima compilación los enciende sola: aparecen en Conexiones, en el
selector de servicio de Inicio y en Transferir.

## Dónde se carga cada clave

| Dónde compilás | Cómo |
|---|---|
| GitHub Actions (el APK de `debug-latest`) | Repo → Settings → Secrets and variables → Actions → *New repository secret* |
| Tu PC | En `local.properties` (no se sube al repo) |

## TIDAL

1. Entrá a <https://developer.tidal.com> con tu cuenta de TIDAL y creá una app (*Dashboard → Create app*).
2. En *Redirect URIs* agregá exactamente: `slidedify://tidal-callback`
3. Marcá los permisos (*scopes*): `user.read`, `collection.read`, `collection.write`,
   `playlists.read`, `playlists.write`, `search.read`.
4. Copiá el **Client ID** (el *Client secret* no hace falta: el login usa PKCE).
5. Cargalo como `TIDAL_CLIENT_ID` (secreto de GitHub, o `TIDAL_CLIENT_ID=...` en `local.properties`).

Notas:
- TIDAL no da vistas previas propias; se usan las de Deezer, como con Spotify.
- Si al conectar aparece "invalid redirect", revisá que el Redirect URI sea idéntico.

## YouTube Music

YouTube no tiene una API de "YouTube Music"; se usa la **YouTube Data API v3**, que maneja las
mismas playlists y "Me gusta".

1. Entrá a <https://console.cloud.google.com>, creá un proyecto y habilitá **YouTube Data API v3**
   (*APIs y servicios → Biblioteca*).
2. *Pantalla de consentimiento OAuth*: tipo **Externo**, en modo *Prueba*, y agregá tu cuenta de
   Google en **Usuarios de prueba**.
3. *Credenciales → Crear credenciales → ID de cliente de OAuth → Android*:
   - Nombre del paquete: `com.slidedify.app`
   - SHA-1: `50:C8:C4:FB:D0:38:D3:1A:C9:1C:3B:2D:63:30:D9:8F:47:10:20:16`
     (es la de `debug.keystore`, la que firma los APK de prueba).
4. No hay clave que copiar: Google reconoce la app por paquete + SHA-1. Solo encendé el servicio con
   `YOUTUBE_ENABLED` = `true` (secreto de GitHub, o `YOUTUBE_ENABLED=true` en `local.properties`).

Notas:
- **Cuota diaria**: 10.000 unidades por proyecto. Leer es barato, pero buscar una canción cuesta 100
  y agregarla a una playlist 50. Alcanza para ordenar ~150 canciones por día, o transferir menos.
- Para que la use otra gente hay que pasar la verificación de Google (mientras está en *Prueba*,
  solo entran los usuarios de prueba, hasta 100).

## Spotify (referencia)

Ya está configurado. Para que la use gente fuera de la lista de *User Management* hay que pedir
*Extended quota* en el panel de Spotify; desde 2025 Spotify la otorga sobre todo a organizaciones. Ver la sección "Publicar" del
[README](../README.md).
