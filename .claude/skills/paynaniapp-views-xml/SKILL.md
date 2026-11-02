---
name: paynaniapp-views-xml
description: Cómo hacer o revisar pantallas de PaynaniApp (Java, Android Views y layouts XML, Material Components) con la marca Paynani, Material 3 y accesibilidad. Úsalo al cambiar layouts, temas, colores, textos o la pantalla Estado, o al traducir a Views un ejemplo de Compose de otro skill.
---

# PaynaniApp: pantallas con Views y XML

PaynaniApp es **Java con Views y layouts XML**, no Compose. Usa `com.google.android.material`
(Material Components) y `androidx.appcompat`. Este skill traduce a Views las reglas de
`android-design-guidelines` y fija la marca.

## Reglas que mandan sobre cualquier skill (también en `CLAUDE.md`)

1. **No instales nada por tu cuenta:** ni el CLI `android` (`curl … | bash`), ni `android update`,
   `android skills install` o `android sdk install`, ni dependencias de Gradle. Si hace falta
   algo, dilo y espera el visto bueno.
2. **El CLI `android` siempre con `--no-metrics`.**
3. **Teléfono real:** solo el de Rock, con `ANDROID_SERIAL`; nunca el de John.
4. **Views/XML, no Compose.**

El detalle y la revisión de cada skill están en `../SOURCES.md`.

## Marca y tema

- Colores en `app/src/main/res/values/colors.xml`:
  - `paynani_grana` `#8f2d2b`: primario, barra superior y acentos;
  - `paynani_tinta` `#1c1c1a`: texto;
  - `paynani_papel` `#f7f7f5`: fondo;
  - `paynani_gris` `#66655f`: texto secundario.
- Estados, con color **e** ícono (nunca solo color):
  - bien: `#2e7d32` sobre `#e8f3e9`;
  - aviso: `#b26a00` sobre `#fdf1dc`;
  - error: `#c62828`.
- El tema base pasa de `Theme.AppCompat.Light.NoActionBar` a `Theme.Material3.Light.NoActionBar`,
  con los roles de color mapeados a nuestros colores:
  - `colorPrimary`: grana;
  - `colorOnPrimary`: blanco;
  - `colorSurface`: blanco;
  - `colorOnSurface`: tinta;
  - `android:colorBackground`: papel.
- En los layouts, los colores van por atributo (`?attr/colorOnSurface`, `?attr/colorPrimary`) o
  por `@color/…`, **nunca** con hex en el XML ni en Java.

## Componentes (equivalentes en Views)

| Compose / M3 | Views (lo que usamos) |
|---|---|
| `Card` | `com.google.android.material.card.MaterialCardView` (`app:cardCornerRadius="12dp"`, `app:strokeWidth="1dp"`, `app:cardElevation="0dp"`) |
| `AssistChip` / estado | `com.google.android.material.chip.Chip` (`style="@style/Widget.Material3.Chip.Assist"`), no clicable si solo informa |
| `LinearProgressIndicator` | `com.google.android.material.progressindicator.LinearProgressIndicator` (`app:trackCornerRadius`, `setIndicatorColor`) |
| `TopAppBar` | `com.google.android.material.appbar.MaterialToolbar` |
| `Icon` | `ImageView` con vector de `res/drawable`, `app:tint="?attr/colorPrimary"` |

- Tipografía: `android:textAppearance="?attr/textAppearanceTitleMedium"` y los roles
  equivalentes, no `textSize` suelto. El texto siempre en `sp`, nunca en `dp`.
- Espacios: múltiplos de 4 dp. Márgenes de pantalla de 16 dp, y de 16 dp dentro de las tarjetas.

## Accesibilidad (obligatorio)

- Toque mínimo de **48 × 48 dp** (`android:minHeight="48dp"`, y `minWidth` si hace falta).
- Cada ícono que comunica algo lleva `android:contentDescription` con texto de `strings.xml`. Los
  decorativos llevan `android:importantForAccessibility="no"`.
- Contraste de 4.5:1 como mínimo para el texto normal y de 3:1 para el texto grande y los
  íconos. Revisar los pares de color de estado de arriba si se cambian.
- Un renglón con título y valor se lee como uno: `android:screenReaderFocusable="true"` en el
  contenedor, o `contentDescription` armado en Java.
- Las tarjetas con título llevan `android:accessibilityHeading="true"` en el título.
- Probar con letra grande: `adb shell settings put system font_scale 1.3` y luego regresar a 1.0.

## Textos

- Todo en `res/values/strings.xml` (inglés) **y** `res/values-es/strings.xml`. Nada de cadenas
  en el código.
- En español, el tono de la app: corto y directo, de «tú».

## Cómo comprobar un cambio de UI

1. `./gradlew assembleDebug` y `./gradlew testDebugUnitTest`.
2. Instalar en el emulador (`ANDROID_SERIAL=emulator-5554`) y tomar capturas **antes y después**
   (`adb exec-out screencap -p > antes.png`) de cada pantalla tocada:
   - en inglés y en español (`adb shell cmd locale set-app-locales com.iaaorgmx.paynani --user 0
     --locales es-MX`);
   - con letra normal y grande;
   - con cada estado relevante (por ejemplo, Estado con todo bien y con algo por revisar).
3. Si el cambio no debe mover nada en otras pantallas, comparar las capturas píxel por píxel; un
   comparador de PNG basta.
4. `android --no-metrics layout` sirve para revisar contentDescription y bounds (48 dp) sin
   uiautomator.
5. Las capturas van en la rama de evidencia del PR y se enlazan en su descripción.
