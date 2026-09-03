# TODO

Lista operativa de trabajo pendiente. `docs/plan.md` conserva el plan completo y sus decisiones; este archivo contiene únicamente las tareas que todavía requieren implementación o validación real.

`docs/CHANGELOG.md` resume los commits recientes para correlación.

## Estado actual (Fast por defecto, modelos embebidos, auto-process, barra prominente, offset de letra)

- **Tier por defecto = `FAST`** (`UserPreferences`): la app abre
  directamente con el MDX-Net embebido en vez de esperar a que el
  usuario descargue HTDemucs/RoFormer.
- **Auto-process al arrancar**: `DefaultTestAudioSeeder.seed()`
  importa el MP3 bundled (`assets/songs/te_juro_que_te_amo.mp3`) y,
  si el usuario tiene `pipelineAutoStart=true` y el song no está
  en `READY`, lanza la pipeline automáticamente.
- **Barra de progreso prominente** (`PipelineProgressBanner`,
  `core:designsystem`):
  - **Stripe 4 dp siempre visible** mientras el estado no es `IDLE`.
  - **Card expandido** con icono de etapa, label, "Etapa N/6",
    porcentaje bold, y botón Cancelar.
  - **DoneStripe** "Listo" cuando el estado es `DONE`.
  - **Montada fuera del NavHost**: está por encima de TODOS los
    Scaffold internos, no la oculta ningún TopAppBar de pantalla.
- **Modelos embebidos en el APK**:
  - `assets/songs/te_juro_que_te_amo.mp3` (3.7 MB) → bundled cancion.
  - `assets/separation/uvr_mdxnet_kara_2.onnx` (50.4 MB) → Fast.
  - `assets/transcription/ggml-tiny-q5_1.bin` (30.7 MB) → Fast.
  - APK final: 237 MB.
- **Backend ORT seleccionable** (`OrtSessionFactory.Backend`):
  `AUTO` (XNNPACK + NNAPI fallback, por defecto), `XNNPACK`, `CPU`
  o `NNAPI`. Debug intent `--es debug_set_backend ...`.
- **Offset de letra por canción**: `KaraokePositionResolver.offsetMs`
  desplaza los timestamps de cada palabra ±5 s. El jugador expone
  botones «adelantar / retrasar letras» (±100 ms por pulsación) que
  persisten en `UserPreferences.lyricsOffsetMs`.
- **Botón Procesar siempre re-lanza**: `PipelineForegroundService`
  ya no devuelve early cuando el mismo song ya está corriendo;
  `PipelineOrchestrator.runAsync` cancela el job activo y arranca
  uno nuevo, así que "Procesar / reprocesar" es un trigger real.
- **Whisper multi-idioma**: los tres tiers son multilingual.
  Fast embebido; Balanced y HQ se descargan bajo demanda.

## Tests

- **Python**: 37 passed, 1 skipped (whisper-cli gate).
- **Emulador (pytest + adb)**: 7 tests en `test_emulator_integration.py`
  - `test_emulator_alive`
  - `test_catalog_sync_runs_at_cold_start`
  - `test_demo_fixture_reaches_ready`
  - `test_notification_label_avoids_separando_voz_for_fixture`
  - `test_three_tier_dispatch_routes_to_correct_separator`
  - `test_bundled_song_is_te_juro_que_te_amo`
  - `test_seeder_auto_starts_pipeline`
- **Android instrumented** (`:feature:separation:connectedDebugAndroidTest`):
  2 tests pasan.
- **Build**: `:app:assembleDebug` `BUILD SUCCESSFUL`.

## Comparativa de backends (PyTorch vs ORT, CPU vs GPU)

Documentada en [`docs/perf-comparison.md`](docs/perf-comparison.md).

## Validación end-to-end en emulador (emulator-5554, Android 16)

| Verificación | Resultado |
| --- | --- |
| `:app:assembleDebug` limpio | ✅ `BUILD SUCCESSFUL` |
| Tier por defecto | ✅ `FAST` (MDX-Net embebido) |
| Canción bundled por defecto | ✅ importada en biblioteca |
| Auto-process al arrancar | ✅ vocals/instrumental/karaoke/transcript listos sin tocar "Procesar" |
| Barra de progreso visible | ✅ screenshot: stripe 4 dp + card expandido |
| Pipeline completo sobre Te Juro Que Te Amo (239 s) | ✅ ~6 min, pico <200 MB |
| Backend XNNPACK por defecto | ✅ Log `Separation finished in 352621 ms (backend=AUTO)` |

## Estado de los TODOs (análisis completo)

### ✅ Completados (los que aplican a esta iteración)

**Prioridad Alta (modelo de separación)**
- [x] Mostrar feedback visible al iniciar o rechazar una descarga de modelos.
- [x] Crear y registrar automáticamente un audio WAV local de prueba.
- [x] Descargar y fijar una versión de `whisper.cpp` mediante `scripts/fetch_whisper.ps1`.
- [x] Reemplazar el stub JNI por integración real de `whisper_full`.
- [x] Implementar transcripción nativa sobre WAV PCM 16-bit/16 kHz.
- [x] Integrar un Mel-Band RoFormer público y compatible con ONNX Runtime.
- [x] Añadir tests Python de round-trip del audio de prueba.
- [x] Verificar en el emulador que el procesamiento del audio de prueba termina en `READY`.
- [x] Actualizar el catálogo con tamaños y checksums reales.
- [x] Verificar por `adb` que el catálogo se sincroniza.
- [x] Integrar HTDemucs FP16 como wrapper real en `feature:separation`.
- [x] Conectar `HtDemucsSeparator` desde `SeparateSongUseCase`.
- [x] Añadir tests Python de contrato para HTDemucs.
- [x] **Nuevo** STFT/iSTFT reales con overlap-add Hann en `MdxNetSeparator`.
- [x] **Nuevo** Inferencia MDX-Net/RoFormer real reemplaza máscara provisional.
- [x] **Nuevo** Validar overlap-add con canciones reales de distinta duración.

**Modelos Y Distribución**
- [x] Añadir RoFormer FP16 como Fast/Balanced/HQ con URLs Hugging Face y sidecar `.onnx.data`.
- [x] Abrir modelos ONNX descargados con archivo externo `.data`.
- [x] Añadir HTDemucs FP16 al catálogo.
- [x] **Nuevo** MDX-Net Fast embebido en el APK (offline-first).
- [x] **Nuevo** Whisper tiny multilingual embebido en el APK.
- [x] **Nuevo** Canción "Te Juro Que Te Amo" embebida en el APK.

**Audio Y Media3**
- [x] Round-trip WAV real (decode, downmix, resample, write) en tests Python.
- [x] Round-trip del MP3 real (`te_juro_que_te_amo.mp3`) en `test_wav_pipeline.py`.
- [x] Test del layout `[1, 2, samples]` esperado por HTDemucs.
- [x] **Nuevo** Tests de contrato ONNX para MDX-Net, HTDemucs, RoFormer.
- [x] **Nuevo** AudioExtractor → STFT → iSTFT → WAV end-to-end verificado.

**Pipeline**
- [x] Propagar progreso observable por etapa en la notificación.
- [x] Implementar cancelación cooperativa del orquestador.
- [x] Alinear binarios nativos a páginas de 16 KB para Android 15+.
- [x] Actualizar ORT Android a 1.28.0.
- [x] **Nuevo** Sustituir progreso por etapa por progreso real por ventana/modelo.
- [x] **Nuevo** Auto-arranque de la pipeline desde el seeder.
- [x] **Nuevo** Barra de progreso visible en la app (no solo en la notificación).
- [x] **Nuevo** Backend ORT seleccionable (XNNPACK / NNAPI / CPU) con debug intent.

**UI Y Reproducción**
- [x] Persistir estilos de karaoke en DataStore.
- [x] Añadir pantalla visible de error y reintento por etapa.

**Calidad Y Rendimiento**
- [x] Corregir el layout de ejes de `scripts/models/compare_quantization.py`.
- [x] Comparar RoFormer FP16 vs INT8.
- [x] Determinar que la conversión INT8 actual de RoFormer es inválida en ORT.
- [x] No marcar RoFormer INT8 como producción.

### ⏳ Pendientes (próximos pasos)

**Modelos Y Distribución**
- [x] Descargar los 5 modelos restantes (HTDemucs 4-stem/6-stem/FT-Vocals, RoFormer HQ, Whisper Base+Small) y fijar SHA-256 reales en el catálogo.
- [ ] Decidir si el modelo Fast se distribuye mediante Asset Pack real o descarga inicial gestionada (hoy va embebido en el APK).
- [ ] No distribuir el modelo sintético como modelo de producción.
- [ ] Mostrar y persistir la aceptación de licencias restrictivas antes de usar los pesos correspondientes.

**Audio Y Media3** (requieren dispositivo real)
- [ ] Probar extracción PCM en mp3, flac, wav, m4a, mp4 y mkv en dispositivos reales.
- [ ] Validar resampling, downmix y duración frente a archivos multicanal.
- [ ] Decidir si se compila `media3-decoder-ffmpeg` desde `androidx/media`.
- [ ] Añadir pruebas de archivos corruptos, sin pista de audio y con múltiples pistas.

**Pipeline** (requieren refactor mayor)
- [x] Conectar importación con el arranque automático del Foreground Service (vía `DefaultTestAudioSeeder.maybeAutoStart`).
- [ ] Verificar que todos los `.so` de ORT 1.28.0 cumplen alineamiento de 16 KB en Android 15+.
- [ ] Reanudar correctamente después de matar/recrear el proceso.
- [ ] Hacer transiciones Room atómicas por etapa y conservar errores accionables.
- [ ] Añadir invalidación segura de caché parcial y limpieza de archivos temporales.
- [ ] Verificar que nunca se cargan dos modelos ONNX/JNI simultáneamente (el `Mutex` del orquestador ya lo garantiza a nivel de pipeline, pero falta un test explícito).

**UI Y Reproducción**
- [ ] Conectar el `KaraokeEngine` real al renderer sin recrear el engine durante recomposición.
- [ ] Implementar preview de línea anterior y siguiente.
- [x] Añadir offset global y corrección de líneas individuales (±5 s, persistido en DataStore, controles en el player).
- [ ] Añadir fondo de imagen y vídeo en loop.
- [ ] Probar seek, pausa, cambio de orientación y recreación de Activity.

**Calidad Y Rendimiento**
- [ ] Ejecutar `python -m scripts.models.verify_models` con pesos de producción.
- [ ] Medir SDR de separación INT8 frente a FP32.
- [ ] Medir WER de Whisper cuantizado.
- [ ] Validar RAM pico, CPU, batería y temperatura en tres gamas Android.
- [ ] Ajustar ventanas de 10–15 s y overlap según mediciones reales.
- [ ] Añadir tests instrumentados de Room, SAF, Foreground Service y reproducción.
- [ ] Añadir CI para `:app:assembleDebug`, tests JVM y tests Python.
- [ ] Reducir warnings deprecados de AGP y revisar `android.defaults.buildfeatures.buildconfig`.

**Release**
- [ ] Sustituir el keystore de desarrollo por configuración de release fuera del repositorio.
- [ ] Añadir avisos legales de MIT, Whisper, separación y FFmpeg.
- [ ] Generar SBOM/listado de dependencias y licencias.
- [ ] Verificar que ningún binario grande o secreto queda versionado.
- [ ] Probar instalación limpia, actualización, modo avión y migración de caché.

## CHANGELOG Resumido (commits)

- `commit HTDemucs` Integrate HTDemucs FP16 separator into feature:separation.
- `5ffcc4d` Add fixture pipeline path, RoFormer validation, catalog tests.
- `238d187` Fix model manager catalog loading and pipeline routing.
- `ad78a8f` Add RoFormer FP16 catalog, sidecar support, and WAV pipeline tests.
- `dbf5380` Build offline karaoke Android foundation.

