# Galaxy Auto Fire — Android (v2)

Monitor visual auxiliar en Kotlin + Jetpack Compose. Detecta luz **roja / verde / sin señal** en una región de la pantalla que el usuario comparte voluntariamente (MediaProjection).

> Es un **monitor**: no automatiza toques, gestos ni acciones dentro del juego. Los cuatro intervalos solo se guardan como configuración.

## Interfaz
- **Monitor**: orbe de señal animado (verde / rojo / azul de análisis / gris en pausa), medidores de cobertura con marca de umbral, FPS y muestras, botón de inicio/detención.
- **Región**: vista previa de teléfono con el área de análisis y dos selectores de rango (horizontal / vertical).
- **Ajustes**: sensibilidad (saturación, brillo, cobertura) e intervalos. Se guardan solos (sin botones "Guardar").
- Tema oscuro fijo, edge-to-edge (necesario con targetSdk 35), iconos dibujados con Canvas (sin librería de iconos).

## Arquitectura
| Archivo | Función |
|---|---|
| `Detection.kt` | Detector HSV puro (sin Android), lectura directa del buffer RGBA con `rowStride`/`pixelStride`, estabilizador de estado |
| `CaptureService.kt` | Servicio en primer plano `mediaProjection`; captura a media resolución, máx. ~30 análisis/s, cierre idempotente |
| `MonitorBus.kt` | `StateFlow` servicio → UI (reemplaza escrituras por fotograma en SharedPreferences) |
| `Settings.kt` | Persistencia con DataStore (valores validados con `sanitized()`) |
| `MainViewModel.kt` | Estado de UI + guardado automático con debounce |
| `ui/` | Tema, componentes y pantallas Compose |

## Compilar
1. Android Studio (Hedgehog o posterior), JDK 17, sincronizar Gradle (Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21).
2. Ejecutar en Android 8.0+ (minSdk 26). En Android 13+ se pide el permiso de notificaciones; en Android 14+ se acepta la captura en cada sesión.
3. Pruebas unitarias: `./gradlew :app:testDebugUnitTest` (o `gradle` si no hay wrapper; Android Studio genera el wrapper al sincronizar).

## Cambios respecto a v1
- La "proporción" ahora se mide sobre **todas** las muestras de la región (antes solo sobre las saturadas, lo que daba falsos positivos con pocos píxeles). Parámetro renombrado a *cobertura mínima* (2 % por defecto; con una luz pequeña en una región grande conviene reducir la región en la pestaña Región).
- Sin `Bitmap` por fotograma: se lee el buffer directamente (menos memoria y CPU).
- Ajustes migrados a DataStore (claves nuevas; no se migran los valores de v1).
- Se solicita `POST_NOTIFICATIONS`, se añade acción "Detener" en la notificación y se corrige la liberación reentrante de MediaProjection.

## Validaciones pendientes en dispositivo real (Motorola g13)
- Ajustar región y umbrales con la luz real del minijuego (los medidores del Monitor sirven para calibrar).
- Medir rendimiento, temperatura y consumo.
- Verificar notificación del servicio en Android 14.
- Verificado sin dispositivo: XML bien formado, referencias de recursos, equilibrio de llaves y lógica del detector (port ejecutado, 7 casos OK). **No verificado**: compilación Kotlin y todo lo anterior en el dispositivo real.

## APK en GitHub
`.github/workflows/android-build.yml` ejecuta las pruebas unitarias y compila `app-debug.apk` (JDK 17, Gradle 8.9). Descarga el artefacto **GalaxyAutoFire-debug** desde la pestaña Actions.
