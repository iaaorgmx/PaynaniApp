# Skills de este repositorio: origen, revisión y reglas

Los skills de terceros que hay aquí son **copias fijas**: no se instalan con `/plugin install`
ni con `npx skills add`. No se actualizan solos. Si hace falta actualizarlos, se baja otra vez el
commit nuevo, se revisa línea por línea y se anota en esta tabla (PaynaniApp#113).

| Carpeta | Origen | Commit | Licencia | Copiado |
|---|---|---|---|---|
| `android-design-guidelines/` | [ehmo/platform-design-skills](https://github.com/ehmo/platform-design-skills), `skills/android/` | `dc2be825d8b439caea78e9eaa8fb3ac23b0ff3e9` | MIT (`LICENSE`) | 2026-10-19 |
| `android-cli/` | [android/skills](https://github.com/android/skills), `devtools/android-cli/` | `5bda39f154fb859a08d45e2392f3568e049f83fb` | Apache-2.0 (`LICENSE.txt`) | 2026-10-19 |
| `testing-setup/` | [android/skills](https://github.com/android/skills), `testing/testing-setup/` | `5bda39f154fb859a08d45e2392f3568e049f83fb` | Apache-2.0 (`LICENSE.txt`) | 2026-10-19 |
| `paynaniapp-views-xml/` | Propio (PaynaniApp#113) | — | La del repositorio | 2026-10-19 |

Los archivos copiados no se modifican. Lo que hay que corregir de ellos va en las reglas de abajo,
que mandan sobre lo que digan los skills.

## Revisión (2026-10-19, Andy)

- **android-design-guidelines:** solo markdown y un `metadata.json`, sin scripts. No ejecuta
  nada, no hace red y no instala. Casi todos sus ejemplos son de Compose. Para Views, las reglas
  y el checklist valen igual; los ejemplos de Views/XML están en `paynaniapp-views-xml`.
- **android-cli:** solo markdown. Describe el CLI `android`. Dos cosas a vigilar:
  1. «Installation» dice que si no está el CLI se instale con `curl -fsSL
     https://dl.google.com/android/cli/.../install.sh | bash`.
  2. Documenta `android update`, `android skills install` y `android sdk install`.
- **testing-setup:** solo markdown. Dice que se instalen dependencias por iniciativa propia:
  Hilt, Mockk, Dropshots o UI Automator «if requested». Sus referencias son de Compose y Hilt;
  PaynaniApp no usa ni uno ni otro.

## Reglas para el agente

1. **No instalar nada por tu cuenta:** ni el CLI `android` (`curl | bash`), ni `android update`,
   ni `android skills install`, ni `android sdk install`, ni dependencias de Gradle (Hilt, Mockk,
   Dropshots, UI Automator, Paparazzi, Roborazzi). Si hace falta algo, dilo y espera el visto
   bueno en el issue o PR.
2. **El CLI `android` siempre con `--no-metrics`.** En el host de desarrollo ya está en
   `~/.local/bin/android`.
3. **Teléfono real:** solo el de Rock (BV5500Pro), y siempre con `ANDROID_SERIAL`. Nunca el de
   John, que es de producción. Las capturas, solo con PaynaniApp en primer plano.
4. **Views/XML, no Compose:** PaynaniApp es Java con Views. Si un skill propone Compose,
   tradúcelo a Views con `paynaniapp-views-xml`; no migres a Compose.
