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
