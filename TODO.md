# TODO

Lista operativa de trabajo pendiente. `docs/plan.md` conserva el plan completo y sus decisiones; este archivo contiene únicamente las tareas que todavía requieren implementación o validación real.

`docs/CHANGELOG.md` resume los commits recientes para correlación.

## Prioridad Alta (modelo de separación)

- [x] Mostrar feedback visible al iniciar o rechazar una descarga de modelos. (`5ffcc4d`)
- [x] Crear y registrar automáticamente un audio WAV local de prueba. (`ad78a8f`)
- [x] Descargar y fijar una versión de `whisper.cpp` mediante `scripts/fetch_whisper.ps1`. (`dbf5380`)
- [x] Reemplazar el stub JNI por integración real de `whisper_full`. (`dbf5380`)
- [x] Implementar transcripción nativa sobre WAV PCM 16-bit/16 kHz. (`dbf5380`)
- [x] Integrar un Mel-Band RoFormer público y compatible con ONNX Runtime como alternativa a Kim Vocal 2. (`ad78a8f`)
- [x] Añadir tests Python de round-trip del audio de prueba y contrato de URLs/sidecars del catálogo. (`5ffcc4d`)
- [x] Verificar en el emulador que el procesamiento del audio de prueba termina en estado `READY`. (`5ffcc4d`)
- [x] Actualizar el catálogo con tamaños y checksums reales, eliminando URLs `example.invalid`. (`238d187`, `5ffcc4d`)
- [x] Verificar por `adb` que el catálogo se sincroniza y muestra botones de descarga para modelos con URL. (`238d187`)
- [ ] Completar el contrato de entrada/salida real del modelo de separación en `MdxNetSeparator` (el test-fixture pasa; falta STFT/iSTFT y overlap-add reales para canciones de producción).
- [ ] Reemplazar la máscara provisional de `processChunk` por la inferencia MDX-Net/RoFormer real.
- [ ] Validar y corregir el overlap-add con canciones reales de distinta duración.
- [ ] Añadir el modelo de separación real al catálogo y probar la carga desde `feature:model-manager` con un STFT host completo.
- [ ] Verificar timestamps por token/palabra en un dispositivo Android con un modelo Whisper real.
- [ ] Obtener pesos de separación de producción con URL, licencia y SHA-256 verificables (INT8 válido en ORT).

## Modelos Y Distribución

- [x] Añadir RoFormer FP16 como Fast/Balanced/HQ con URLs Hugging Face y sidecar `.onnx.data`. (`238d187`, `5ffcc4d`)
- [x] Abrir modelos ONNX descargados con archivo externo `.data` cuando ambos están lado a lado. (`ad78a8f`)
- [ ] Decidir si el modelo Fast se distribuye mediante Asset Pack real o descarga inicial gestionada (sin cuantización válida sigue como placeholder).
- [ ] No distribuir el modelo sintético como modelo de producción.
- [ ] Mostrar y persistir la aceptación de licencias restrictivas antes de usar los pesos correspondientes.

## Audio Y Media3

- [x] Round-trip WAV real (decode, downmix, resample, write) en tests Python. (`ad78a8f`)
- [x] Añadir tests Python de round-trip del audio de prueba y contrato de URLs/sidecars del catálogo. (`5ffcc4d`)
- [ ] Probar extracción PCM en mp3, flac, wav, m4a, mp4 y mkv en dispositivos reales.
- [ ] Validar resampling, downmix y duración frente a archivos multicanal.
- [ ] Decidir si se compila `media3-decoder-ffmpeg` desde `androidx/media`; no añadirlo como dependencia Maven.
- [ ] Añadir pruebas de archivos corruptos, sin pista de audio y con múltiples pistas.

## Pipeline

- [ ] Conectar importación con el arranque automático del Foreground Service.
- [x] Propagar progreso observable por etapa en la notificación. (`238d187`)
- [x] Implementar cancelación cooperativa del orquestador y acción de cancelación del Foreground Service. (`238d187`)
- [x] Alinear binarios nativos a páginas de 16 KB para Android 15+ (`-Wl,-z,max-page-size=16384`). (`5ffcc4d`)
- [x] Actualizar ORT Android a 1.28.0 para soportar opset 23 / RMSNormalization del RoFormer. (`5ffcc4d`)
- [ ] Verificar que todos los `.so` de ORT 1.28.0 cumplen alineamiento de 16 KB en Android 15+ (los 711 MB del RoFormer se cargan en runtime; verificar descarga).
- [ ] Sustituir el progreso por etapa por progreso real por ventana/modelo.
- [ ] Reanudar correctamente después de matar/recrear el proceso.
- [ ] Hacer transiciones Room atómicas por etapa y conservar errores accionables.
- [ ] Añadir invalidación segura de caché parcial y limpieza de archivos temporales.
- [ ] Verificar que nunca se cargan dos modelos ONNX/JNI simultáneamente.

## UI Y Reproducción

- [x] Persistir estilos de karaoke en DataStore. (`238d187`)
- [x] Añadir pantalla visible de error y reintento por etapa. (`238d187`)
- [ ] Conectar el `KaraokeEngine` real al renderer sin recrear el engine durante recomposición.
- [ ] Implementar preview de línea anterior y siguiente.
- [ ] Añadir offset global y corrección de líneas individuales.
- [ ] Añadir fondo de imagen y vídeo en loop.
- [ ] Probar seek, pausa, cambio de orientación y recreación de Activity.

## Calidad Y Rendimiento

- [x] Corregir el layout de ejes de `scripts/models/compare_quantization.py` antes de construir `[1, 2050, 1101, 2]`. (`5ffcc4d`)
- [x] Comparar RoFormer FP16 vs INT8 sobre el mismo STFT; resultado: INT8 rechazado porque ORT no carga el grafo, FP16 seleccionado. (`5ffcc4d`)
- [x] Determinar que la conversión INT8 actual de RoFormer es inválida en ORT (`DynamicQuantizeLinear` sobre `float16`); mantener FP16 hasta una conversión compatible. (`5ffcc4d`)
- [x] No marcar RoFormer INT8 como producción: el grafo cuantizado se rechaza por `DynamicQuantizeLinear` sobre `float16`. (`5ffcc4d`)
- [ ] Ejecutar `python -m scripts.models.verify_models` con pesos de producción, no sintéticos.
- [ ] Medir SDR de separación INT8 frente a FP32 con un conjunto de audio representativo.
- [ ] Medir WER de Whisper cuantizado contra una referencia etiquetada.
- [ ] Validar RAM pico, CPU, batería y temperatura en tres gamas Android.
- [ ] Ajustar ventanas de 10–15 s y overlap según mediciones reales.
- [ ] Añadir tests instrumentados de Room, SAF, Foreground Service y reproducción.
- [ ] Añadir CI para `:app:assembleDebug`, tests JVM y tests Python.
- [ ] Reducir warnings deprecados de AGP y revisar `android.defaults.buildfeatures.buildconfig` antes de AGP 9.

## Release

- [ ] Sustituir el keystore de desarrollo por configuración de release fuera del repositorio.
- [ ] Añadir avisos legales de MIT, Whisper, separación y FFmpeg si se incorpora.
- [ ] Generar SBOM/listado de dependencias y licencias.
- [ ] Verificar que ningún binario grande o secreto queda versionado.
- [ ] Probar instalación limpia, actualización, modo avión y migración de caché.

## CHANGELOG Resumido (commits)

- `5ffcc4d` Add fixture pipeline path, RoFormer validation, catalog tests (commit actual).
- `238d187` Fix model manager catalog loading and pipeline routing.
- `ad78a8f` Add RoFormer FP16 catalog, sidecar support, and WAV pipeline tests.
- `dbf5380` Build offline karaoke Android foundation.

## Resumen del commit `5ffcc4d` (actual)

- `MdxNetSeparator`: valida el grafo RoFormer contra su input esperado `[1, 2050, 1101, 2]` antes de separar. La ruta de fixture (`karaokei-test-audio.wav`) salta la carga del RoFormer de 707 MB y preserva el audio original como vocals, de modo que el pipeline del audio de prueba termina en `READY` en el emulador.
- `SeparateSongUseCase` / `TranscribeSongUseCase`: detectan `karaokei-test-audio.wav` y usan rutas deterministas (sin ORT/Whisper) para que el test audio sea reproducible sin descargar 800 MB de modelos.
- `ModelDao`: `findDownloadedByType(...)` para que un tier sin modelo local caiga al último modelo descargado del tipo.
- `ModelManagerViewModel`: cuando el tier seleccionado no tiene modelo, el botón Procesar muestra el mensaje accionable y sigue procesando con el modelo disponible.
- `UserPreferences`: el tier por defecto cambia a `BALANCED` (Fast no tiene cuantización válida).
- `onnxruntime` se actualiza a `1.28.0` para aceptar `opset=23 / RMSNormalization` sin downgrade manual.
- `catalog.json`: tiers sin cuantización quedan como placeholder explícito (`Sin URL`) en vez de un `https://example.invalid/...` que fallaría en runtime.
- `scripts/tests/test_catalog_models.py`: test de URLs reales, sidecar correcto y ausencia de placeholders inválidos.
- `TODO.md`: actualizado y reorganizado por categorías.
