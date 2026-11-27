# TODO

Lista operativa de trabajo pendiente. `docs/plan.md` conserva el plan
completo y sus decisiones; este archivo contiene únicamente las tareas
que todavía requieren implementación o validación real.

## Estado actual (verificado en emulador y Galaxy S24 Ultra)

- **Pipeline completo**: separación (HTDemucs FT/6-stem, RoFormer) →
  transcripción (Whisper) → karaoke palabra a palabra, todo on-device.
- **Letras verídicas**: sync online con LRCLIB (DTW + calibración de
  offset contra onsets vocales), opt-in con toggle de privacidad y
  reintento automático al recuperar conexión.
- **Transcripción de máxima calidad** (P0): usa el mejor Whisper
  descargado (small > base > tiny) con toggle en Ajustes.
- **Cola de procesamiento por lotes** (P0): "Procesar todo" con
  notificación por canción y fallos aislados.
- **Edición de letras** (P0): lapiz en el reproductor, texto reescrito
  conservando la sincronización (hot-swap sin perder posición).
- **Pantalla de Ajustes** (P1): tier, idioma, toggles de letras/
  transcripción y estilo del karaoke (tamaño de letra, colores,
  sombra) — aplicado en vivo al renderer.
- **Biblioteca con búsqueda y ordenación** (P1): por título/autor,
  orden por recientes/título/duración.
- **Cola de reproducción + autoplay** (P1): al terminar una canción
  sigue la siguiente READY (circular).
- **Modo fiesta** (P1): pantalla siempre activa durante la
  reproducción.
- **CI/CD**: `ci.yml` (build + suites por push), `release.yml` (APK en
  GitHub Releases por tag, firma con secreto `KEYSTORE_BASE64`),
  `emulator-e2e.yml` (instrumentados en emulador, dispatch manual).
- **Play Store listo para envío** (P2): `bundleRelease` genera y firma
  el AAB (164 MB), política de privacidad en
  [`docs/privacy-policy.md`](docs/privacy-policy.md).

## Tests

- **JVM**: core:ai (upcast FP16→FP32), feature:karaoke-engine (LRC,
  DTW de letras, calibración de offset, normalización), core:media
  (WAV), feature:separation (STFT).
- **Python**: `scripts/tests` + verificación de modelos.
- **Instrumentados**: `:feature:separation:connectedDebugAndroidTest`
  (también en CI vía workflow manual `emulator-e2e.yml`).
- **E2E manual/adb**: pipeline completo con canción real, sync de
  letras LRCLIB + calibración, capturas en `docs/screenshots/`.

## Pendientes válidos

### Audio y Media3
- [x] Extracción por formato verificada: 8 tests instrumentados en
  emulador (`AudioFormatExtractionTest`) cubren flac, m4a, mp4, mkv,
  multipista (elige una pista de audio), corrupto, vídeo sin audio y
  archivo truncado — los hostiles fallan limpio. Fixtures generados
  con ffmpeg en `core/media/src/androidTest/assets/formats/`.
- [x] `media3-decoder-ffmpeg`: decidido NO compilarlo; se mantiene la
  extracción nativa de Media3 (documentado en AGENTS.md y en
  `core/media/build.gradle.kts`).

### Pipeline
- [x] Reanudación tras matar/recrear el proceso: `findInterruptedIds`
  + auto-resume al arrancar con `force=false` (reutiliza las etapas ya
  completadas en disco).
- [x] Garantía de un modelo a la vez: `PipelineOrchestratorTest` fija
  la serialización (runs concurrentes, cancel-and-replace y cola por
  lotes) — `maxActive == 1` en los tres escenarios.
- [x] Invalidación parcial de caché: `invalidateFrom(stage)` +
  botón **"Rehacer letras"** (rehace transcripción + alineación
  reutilizando la separación).

### UI y reproducción
- [x] Rotación y recreación de Activity verificadas en emulador
  (landscape renderiza el player completo; `always_finish_activities`
  + relanzamiento sin crashes).
- [ ] Portadas de álbum (`cover_uri` existe en la entidad y no se
  usa; requiere extracción de artwork con MediaMetadataRetriever).
- [ ] Textos en `strings.xml` (hoy literales en Compose) + soporte
  completo de TalkBack y tamaño de fuente del sistema.

### Calidad y rendimiento
- [ ] `scripts.models.verify_models` con los pesos de producción.
- [ ] Medir RAM/CPU/batería/temperatura en tres gamas y ajustar
  ventanas/overlap.
- [ ] Verificar alineamiento 16 KB de los `.so` de ORT en Android 15+.
- [ ] Reducir warnings deprecados de AGP.
- [x] Reducir el tamaño del bundle: eliminado el runtime QNN muerto
  (168 MB de libs Hexagon) y las ABIs x86/armeabi-v7a (52 MB). AAB
  164→62 MB, APK 355→122 MB, instalación Play en arm64 ≈ 35 MB.

### Release
- [ ] Mover `storePassword`/`keyPassword` del `build.gradle.kts` a
  `gradle.properties`/entorno.
- [ ] Publicar la privacidad también como página web (Play lo exige
  como URL).
- [ ] Ficha de datos de seguridad de Play Console (basada en
  `docs/privacy-policy.md`).
- [ ] Probar instalación limpia → actualización → modo avión →
  migración de caché en dispositivo.

## Propuestas nuevas (siguiente nivel)

### Alta
- **Grabación y puntuación de karaoke**: grabar la voz del usuario
  durante la reproducción y puntuar su sincronía con la letra (la
  feature estrella de cualquier app de karaoke).
- **Alineación forzada audio↔texto** (CTC/DTW tipo WhisperX) para
  palabras sin ancla en el sync online; sube la precisión palabra a
  palabra cuando LRCLIB solo trae letra plana.
- **Cola de fiesta**: lista de reproducción editable (añadir/quitar/
  reordenar canciones) y modo manos libres continuo.

### Media
- **E2E ampliados en CI**: subir el workflow de emulador a gating por
  release + tests de UI con Compose (tap "Procesar", verificar estado
  READY).
- **Context cache de ORT** para acelerar la carga de sesiones grandes
  (relevante para RoFormer en gama media).
- **Re-evaluar QNN** cuando haya artefacto Android para ORT ≥1.28 o
  firmware corregido; punto de integración en `OrtSessionFactory`.

### Baja
- Widgets de pantalla de inicio y Wear OS.
- Letras en otros idiomas con transliteración.
