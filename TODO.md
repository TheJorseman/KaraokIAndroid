# TODO

Lista operativa de trabajo pendiente. `docs/plan.md` conserva el plan
completo y sus decisiones; este archivo contiene únicamente las tareas
que todavía requieren implementación o validación real.

## Estado actual (verificado en emulador y Galaxy S24 Ultra)

- **Tier por defecto = `FAST`** con HTDemucs FT (descarga de 165 MB) +
  Whisper tiny embebido (30.7 MB). Balanced y HQ se descargan bajo
  demanda; el catálogo v9 tiene URLs y SHA-256 verificados.
- **Backend ORT**: `CPU` por defecto (calidad verificada byte a byte
  contra la referencia x86). NNAPI medido ~20% más rápido pero
  calidad sin validar → opt-in. Hilos intra-op adaptables
  (núcleos/2, máx. 6 en dispositivos ≥6 GB).
- **FP16→FP32 upcast en ARM64** (`OnnxFp32Upcast`, streaming, cacheado)
  para sortear la ruta FP16 corrupta del EP CPU de ORT en
  dispositivos.
- **Letras online (LRCLIB)**: texto verídico + DTW contra la
  transcripción local + calibración automática de offset contra los
  onsets vocales. Opt-in con toggle de privacidad; reintento
  automático al recuperar conexión (WorkManager).
- **Karaoke**: resaltado por palabra, líneas largas ajustadas en
  varias filas, 2+2 líneas de contexto, preview de la próxima línea
  en huecos instrumentales, normalización de texto, offset fino ±5 s.
- **Progreso**: barra determinada por etapa ("Separando voz · Etapa 1
  de 3 · 42%") con progreso real de separación y transcripción.
- **Procesar/reprocesar** fuerza el reproceso completo (invalida
  caché); estados transitivos se limpian al arrancar.
- **CI/CD**: `ci.yml` (build + suites JVM en cada push) y `release.yml`
  (APK publicado en GitHub Releases por tag `v*`, firma con secreto
  `KEYSTORE_BASE64` o fallback debug).
- **Demo song**: el MP3 con copyright no está en git; se empaqueta si
  existe localmente, si no la app genera un tono sintético.

## Tests

- **JVM**: core:ai (upcast FP16→FP32), feature:karaoke-engine (LRC,
  DTW de letras, calibración de offset, normalización de líneas),
  core:media (WAV), feature:separation (STFT).
- **Python**: `scripts/tests` + verificación de modelos.
- **Instrumentados**: `:feature:separation:connectedDebugAndroidTest`.
- **E2E manual/adb**: pipeline completo con canción real (3:59),
  sync de letras LRCLIB + calibración, capturas en
  `docs/screenshots/`.

## Pendientes válidos

### Modelos y distribución
- [ ] Decidir Asset Pack vs. descarga inicial para los modelos
  grandes (hoy: descarga desde Hugging Face con verificación).
- [ ] Buscar un sustituto Fast más ligero que HTDemucs FT (165 MB);
  evaluar MDX-Net mantenido o cuantización segura.
- [ ] Avisos legales de MIT/Whisper/modelos y SBOM de dependencias.

### Audio y Media3
- [ ] Probar extracción en dispositivos reales para flac, m4a, mp4 y
  mkv (mp3/wav ya verificados).
- [ ] Pruebas de archivos corruptos, sin pista de audio y con
  múltiples pistas de audio.
- [ ] Decidir si se compila `media3-decoder-ffmpeg` desde
  `androidx/media` (no publicado en Maven).

### Pipeline
- [ ] Reanudar correctamente después de matar/recrear el proceso.
- [ ] Test explícito de que nunca se cargan dos modelos a la vez.
- [ ] Transiciones Room atómicas por etapa.

### UI y reproducción
- [ ] Ajustes visibles: estilo de karaoke (ya persistido en
  `UserPreferences.karaokeStyle`) sin pantalla de ajustes todavía.
- [ ] Fondo de imagen/vídeo en loop para el reproductor.
- [ ] Probar rotación de pantalla y recreación de Activity durante la
  reproducción (seek/pausa ya probados).

### Calidad y rendimiento
- [ ] `scripts.models.verify_models` con los pesos de producción.
- [ ] Medir RAM pico, CPU, batería y temperatura en tres gamas
  Android; ajustar ventanas/overlap con esas mediciones.
- [ ] Verificar alineamiento 16 KB de los `.so` de ORT en Android 15+.
- [ ] Tests instrumentados de Room, SAF, Foreground Service y
  reproducción; tests de migraciones Room.
- [ ] Reducir warnings deprecados de AGP y revisar
  `android.defaults.buildfeatures.buildconfig`.

### Release
- [ ] Mover `storePassword`/`keyPassword` del `build.gradle.kts` a
  `gradle.properties`/entorno (hoy están en texto plano).
- [ ] Probar instalación limpia → actualización → modo avión →
  migración de caché en dispositivo (instalación limpia verificada).

## Propuestas nuevas (mejoras de alto impacto)

### P0 — Calidad del producto
- **Transcripción local de mayor calidad**: hoy la letra local es la
  de Whisper tiny (aproximada). Añadir opción de transcribir con
  base/small del tier descargado cuando existan, manteniendo tiny
  como fallback offline mínimo. Mejora directa cuando no hay internet
  para el sync de letras.
- **Cola de procesamiento por lotes**: importar varias canciones y
  procesarlas en cola con la misma notificación/estado; hoy una sola
  pipeline a la vez con cancelación del job anterior.
- **Edición de letras en la app**: reemplazar el texto de una línea
  (el tiempo ya es bueno; el texto ASR es lo que falla) y persistirlo
  en `karaoke.json`.

### P1 — Experiencia
- **Cola de reproducción + autoplay** en el reproductor karaoke
  (siguiente canción al terminar, lista de reproducción).
- **Pantalla de ajustes**: estilo de karaoke (colores/tamaño ya
  persistidos), idioma, toggle de letras online, tier por defecto.
- **Búsqueda y ordenación** en la biblioteca + portadas (`cover_uri`
  existe en la entidad y no se usa).
- **Modo fiesta / karaoke continuo**: keep-screen-on durante la
  reproducción, letra más grande, modo rotación horizontal.
- **Grabación y puntuación**: grabar la voz del usuario durante el
  karaoke y puntuar la sincronía con la letra (feature estrella de
  futuro).
- **Accesibilidad**: textos en `strings.xml` (hoy literales en
  Compose), content descriptions, soporte TalkBack y tamaño de
  fuente del sistema.

### P2 — Ingeniería
- **Alineación forzada audio↔texto** (CTC/DTW tipo WhisperX) para
  palabras sin ancla en el sync online; sube la precisión palabra a
  palabra cuando LRCLIB solo trae letra plana.
- **Context cache de ORT** para acelerar la carga de sesión del
  RoFormer (la primera carga de un grafo grande es costosa).
- **Re-evaluar QNN** cuando haya artefacto Android para ORT ≥1.28 o
  firmware corregido (el punto de integración está en
  `OrtSessionFactory`); el plugin 2.7.x actual falla en
  `QnnDevice_create` en el firmware de Samsung probado.
- **E2E en CI con emulador** (runner macOS + instrumented tests) para
  no depender de pruebas manuales por adb.
- **Preparación para Play Store**: AAB, esquema de versionado,
  política de privacidad para el sync de letras y ficha de datos de
  seguridad.
