# TODO

Lista operativa de trabajo pendiente. `docs/plan.md` conserva el plan completo y sus decisiones; este archivo contiene únicamente las tareas que todavía requieren implementación o validación real.

## Prioridad Alta

- [x] Mostrar feedback visible al iniciar o rechazar una descarga de modelos.
- [x] Crear y registrar automáticamente un audio WAV local de prueba.
- [x] Descargar y fijar una versión de `whisper.cpp` mediante `scripts/fetch_whisper.ps1`.
- [x] Reemplazar el stub JNI por integración real de `whisper_full`.
- [x] Implementar transcripción nativa sobre WAV PCM 16-bit/16 kHz.
- [ ] Verificar timestamps por token/palabra en un dispositivo Android con un modelo real.
- [ ] Obtener pesos de separación de producción con URL, licencia y SHA-256 verificables.
- [x] Integrar un Mel-Band RoFormer público y compatible con ONNX Runtime como alternativa a Kim Vocal 2.
- [ ] Completar el contrato de entrada/salida real del modelo de separación en `MdxNetSeparator`.
- [ ] Reemplazar la máscara provisional de `processChunk` por la inferencia MDX-Net/RoFormer real.
- [ ] Validar y corregir el overlap-add con canciones reales de distinta duración.
- [ ] Añadir el modelo de separación real al catálogo y probar la carga desde `feature:model-manager`.

## Modelos Y Distribución

- [ ] Decidir si el modelo Fast se distribuye mediante Asset Pack real o descarga inicial gestionada.
- [ ] No distribuir el modelo sintético como modelo de producción.
- [x] Añadir RoFormer FP16 como Fast/Balanced/HQ con URLs Hugging Face y sidecar `.onnx.data`.
- [x] Abrir modelos ONNX descargados con archivo externo `.data` cuando ambos están lado a lado.
- [x] Actualizar el catálogo con tamaños y checksums reales, eliminando URLs `example.invalid`.
- [ ] Mostrar y persistir la aceptación de licencias restrictivas antes de usar los pesos correspondientes.

## Audio Y Media3

- [x] Round-trip WAV real (decode, downmix, resample, write) en tests Python.
- [ ] Probar extracción PCM en mp3, flac, wav, m4a, mp4 y mkv en dispositivos reales.
- [ ] Validar resampling, downmix y duración frente a archivos multicanal.
- [ ] Decidir si se compila `media3-decoder-ffmpeg` desde `androidx/media`; no añadirlo como dependencia Maven.
- [ ] Añadir pruebas de archivos corruptos, sin pista de audio y con múltiples pistas.

## Pipeline

- [ ] Conectar importación con el arranque automático del Foreground Service.
- [x] Propagar progreso observable por etapa en la notificación.
- [ ] Sustituir el progreso por etapa por progreso real por ventana/modelo.
- [x] Alinear binarios nativos a páginas de 16 KB para Android 15+ (`-Wl,-z,max-page-size=16384`).
- [ ] Sustituir ORT 1.20 (libomp desalineado) por una versión que ya cumpla 16 KB o reemplazar `libomp.so` por un stub.
- [x] Implementar cancelación cooperativa del orquestador y acción de cancelación del Foreground Service.
- [ ] Reanudar correctamente después de matar/recrear el proceso.
- [ ] Hacer transiciones Room atómicas por etapa y conservar errores accionables.
- [ ] Añadir invalidación segura de caché parcial y limpieza de archivos temporales.
- [ ] Verificar que nunca se cargan dos modelos ONNX/JNI simultáneamente.

## UI Y Reproducción

- [ ] Conectar el `KaraokeEngine` real al renderer sin recrear el engine durante recomposición.
- [ ] Implementar preview de línea anterior y siguiente.
- [x] Persistir estilos de karaoke en DataStore.
- [ ] Añadir offset global y corrección de líneas individuales.
- [ ] Añadir fondo de imagen y vídeo en loop.
- [ ] Probar seek, pausa, cambio de orientación y recreación de Activity.
- [x] Añadir pantalla visible de error y reintento por etapa.

## Calidad Y Rendimiento

- [x] Corregir el layout de ejes de `scripts/models/compare_quantization.py` antes de construir `[1, 2050, 1101, 2]`.
- [x] Comparar RoFormer FP16 vs INT8 sobre el mismo STFT; resultado: INT8 rechazado porque ORT no carga el grafo, FP16 seleccionado.
- [x] Determinar que la conversión INT8 actual de RoFormer es inválida en ORT (`DynamicQuantizeLinear` sobre `float16`); mantener FP16 hasta una conversión compatible.
- [ ] **PAUSADO** Publicar una copia propia en HF con `hf upload`; actualmente el catálogo usa URLs públicas verificadas de `silverdaw` y `ggerganov`.
- [x] No marcar RoFormer INT8 como producción: el grafo cuantizado se rechaza por `DynamicQuantizeLinear` sobre `float16`.
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
