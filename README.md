<div align="center">
  <img src=".github/assets/logo.png" alt="Rethink Dns Fork" width="120">

  # Rethink Dns Fork

  Cliente DNS cifrado, cortafuegos y proxy WireGuard/WARP para Android.

  [![Descargar APK](https://img.shields.io/github/v/release/paulvers-ui/rethink-app-masque?label=descargar&style=for-the-badge)](https://github.com/paulvers-ui/rethink-app-masque/releases/latest)
  [![Sitio](https://img.shields.io/badge/sitio-paulvers--ui.github.io%2Frethink-1f6f5c?style=for-the-badge)](https://paulvers-ui.github.io/rethink/)
</div>

---

Este es un fork de [Rethink DNS + Firewall + VPN](https://github.com/celzero/rethink-app), con estos cambios sobre el original:

- **Package ID propio** (`com.creatore.rethinkfork`) — se instala junto al Rethink original en el mismo teléfono, sin conflicto.
- **WARP vía MASQUE**, usando [usque](https://github.com/paulvers-ui/usque) en vez del cliente WireGuard oficial de Cloudflare.
- **Un único tema** (Dark Plus) — se quitaron los demás del selector.
- **Alertas de sonido** cuando cambia el estado de la red: túnel caído, proxy sin salud, cambio de interfaz — silenciadas para no repetirse en ráfaga.
- **Sin Firebase ni Google Play Services** en la variante `fdroid`.
- **Backend de red propio**: el firestack que usa este fork viene de [paulvers-ui/firestack](https://github.com/paulvers-ui/firestack), con más de 600 commits sobre el último punto de la API compatible con esta app — la rama `n2` de upstream cambió esa API y dejó de funcionar aquí.
- Enlace de patrocinio propio y DNS de respaldo por defecto a Cloudflare en vez de Rethink.

## Instalar

- **[Obtainium](https://github.com/ImranR98/Obtainium)** (recomendado): añade este repositorio como fuente y recibe actualizaciones automáticas.
- **[Página de descarga](https://paulvers-ui.github.io/rethink/)**: APK directo, con QR para el teléfono.
- **[GitHub Releases](https://github.com/paulvers-ui/rethink-app-masque/releases)**: descarga manual.

## Licencia

Igual que el proyecto original — ver [LICENSE](LICENSE).
