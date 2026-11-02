# PaynaniApp: reglas para agentes

PaynaniApp es una app Android en **Java con Views y layouts XML** (no Compose). Los skills de
`.claude/skills/` vienen de terceros y de nosotros; su origen y revisión están en
`.claude/skills/SOURCES.md`. Estas reglas mandan sobre lo que diga cualquier skill:

1. **No instales nada por tu cuenta:**
   - ni el CLI `android` (`curl … | bash`);
   - ni `android update`, `android skills install` o `android sdk install`;
   - ni dependencias de Gradle (Hilt, Mockk, Dropshots, UI Automator, Paparazzi, Roborazzi…).

   Si hace falta algo, dilo en el issue o PR y espera el visto bueno.
2. **El CLI `android` siempre con `--no-metrics`.**
3. **Teléfono real:** solo el Blackview BV5500Pro, que desde el 2026-10-29 es el teléfono de Andy
   (antes era el de Rock), siempre con `ANDROID_SERIAL`. Andy decide cuándo instalarle una versión
   de debug; al terminar, lo vuelve a emparejar como pasarela de SMS. Nunca el de John, que es de
   producción. Las capturas, solo con PaynaniApp en primer plano.
4. **Views/XML, no Compose:** si un skill propone Compose, tradúcelo a Views con
   `.claude/skills/paynaniapp-views-xml`; no migres la app a Compose.
