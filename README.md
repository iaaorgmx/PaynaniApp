# PaynaniApp

La app de Android para platicar con tu agente de IA de [paynani](https://github.com/iaaorgmx/paynani)
desde el teléfono: la misma conversación de Webchat que ves en PaynaniWeb, con fotos y archivos
de ida y vuelta, «escribiendo…» y el borrador en vivo.

- **Emparejar:** pídele a tu agente un código y escanea el QR, o escribe la dirección y el código.
- **Permisos:** internet, la cámara (sólo para el QR) y las notificaciones (el aviso de que tu
  agente contestó). Nada de SMS, llamadas ni contactos.
- **Conexión:** mientras la app está abierta.

Necesita paynani 0.21.0 o más nuevo. Desde la versión que sigue a la 0.21.0 (paynani #671),
paynani sabe que este teléfono no hace SMS: no le manda envíos y no avisa que se desconectó cuando
cierras la app. Con la 0.21.0, la app rechaza cada envío en el acto. Los APK firmados están en
[Releases](https://github.com/iaaorgmx/PaynaniApp/releases/latest).

La versión completa, de uso interno, con la pasarela de SMS y llamadas, WhatsApp y Telegram, está
en [iaaorgmx/PaynaniApp-Agentic](https://github.com/iaaorgmx/PaynaniApp-Agentic).

## Versión de Android

- `targetSdkVersion` y `compileSdkVersion` 36 (Android 16), lo que pide Google Play; `minSdkVersion` 26
  (Android 8). Para compilar: Android Gradle Plugin 8.9.1 y Gradle 8.11.1, y la plataforma
  `android-36` del SDK (#3).
- Con la API 36, Android 16 dibuja la app de borde a borde siempre, sin opción de apagarlo: los
  layouts usan `fitsSystemWindows` para que la barra de arriba y la caja de Webchat no queden bajo
  las barras del sistema.
- En tablets (600 dp de ancho o más), Android 16 ignora `screenOrientation="portrait"`: ahí la app
  puede girar. En teléfonos se queda vertical.
