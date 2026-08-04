# Plan de Desarrollo — KaraokIAndroid (offline IA)

Este documento es la fuente de verdad del proyecto. Se actualiza con cada ticket cerrado. Ver `licenses.md` para licencias y `post-mvp.md` para funcionalidades diferidas.

## 0. Decisiones cerradas y desviaciones

### Decisiones técnicas cerradas (sección 3 del plan original)

- **Lenguaje / UI**: Kotlin + Jetpack Compose (Compose BOM 2024.12.x)
- **Arquitectura**: Clean Architecture ligera + MVVM, multi-módulo Gradle
- **Inyección de dependencias**: Hilt 2.52
- **Concurrencia**: Kotlin Coroutines 1.9.x + Flow
- **Procesamiento largo en background**: WorkManager (descargas) + Foreground Service `mediaProcessing` (pipeline IA, Android 14+)
- **Persistencia estructurada**: Room 2.6.x
- **Preferencias**: DataStore
- **Reproducción / extracción de audio**: androidx.media3 1.5.x (ExoPlayer) — extractores nativos primero, `media3-decoder-ffmpeg` solo como respaldo si se compila (T3.4)
- **Runtime de IA**: ONNX Runtime Mobile 1.20.x (XNNPACK por defecto, NNAPI fallback legacy, QNN post-MVP) + whisper.cpp vía JNI para transcripción
- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35
- **AGP**: 8.7.x · **Kotlin**: 2.0.x
- **Formato interno de audio para IA**: PCM WAV 16-bit, mono, 16 kHz
- **Descarga de modelos**: gestor de modelos con checksum SHA-256. El módulo Asset Pack queda preparado, pero no está registrado en el DSL de AGP actual; los modelos grandes no se embeben en el APK.

### Desviaciones y refinamientos

- **FFmpegKit (retirado) → Media3 nativo + decoder-ffmpeg opcional.** Se usan los extractores nativos de Media3 para la mayoría de formatos. La extensión oficial `media3-decoder-ffmpeg` (GPLv3) se compila solo como respaldo de códecs no soportados nativamente.
- **NNAPI deprecada → XNNPACK por defecto, NNAPI como fallback.** QNN (Qualcomm NPU) se difiere a post-MVP por requerir el Qualcomm AI Engine SDK (NDA). Ver `post-mvp.md`.
- **Gestor de modelos movido a Fase 2** (no al final), porque las fases 3 y 4 lo necesitan.
- **Whisper word timestamps**: el plan original proponía "atención cruzada + DTW" sobre Whisper exportado a ONNX. Esta aproximación es muy costosa de implementar bien (hay que exportar el decoder con salidas de atención como modelo separado). En su lugar se usa **whisper.cpp vía JNI**, que ya tiene `whisper_new_segment_callback` y `whisper_full_with_state` con word timestamps probados. T4.0 introduce el módulo `core:whisper-jni`. T4.3 reescrito.
- **Modelo de separación**: MDX-Net Kim Vocal 2 si se consigue un checkpoint verificable; fallback preparado para Mel-Band RoFormer. La licencia y el checksum de los pesos reales deben aprobarse antes de distribuirlos.
- **Asset Pack**: estructura preparada, pero no se registra en `app/build.gradle.kts` porque el DSL de asset packs no está disponible en la configuración AGP actual y los modelos actuales exceden el tamaño razonable del APK. El flujo operativo es descarga por `feature:model-manager`.

## 1. Objetivo

Aplicación Android **100% offline** que:
- Importa canciones locales (mp3, flac, wav, m4a, mp4, mkv).
- Separa voz e instrumental mediante IA on-device.
- Transcribe la letra automáticamente, en múltiples idiomas.
- Alinea la letra palabra por palabra.
- Muestra un karaoke sincronizado sobre imagen o video de fondo.
- Cachea todo resultado para no reprocesar.
- No depende de ningún servicio en la nube, salvo la descarga *inicial y opcional* de modelos.

## 2. Restricción no negociable: offline real

- La app procesa sin nube una vez que el modelo elegido está localmente disponible. El modelo se descarga/verifica mediante `feature:model-manager`; el modo avión desde instalación limpia requiere añadir un asset pack real o un modelo pequeño aprobado.
- El único módulo con permiso de red activo en su lógica es **feature:model-manager** (Fase 2). Ningún otro módulo debe hacer llamadas de red.
- Auditoría periódica: `rg -t kotlin "HttpClient|Retrofit|OkHttp|URLConnection" --glob '!feature/model-manager/**'`

## 3. Decisiones técnicas cerradas

(Ver sección 0 arriba para el detalle de versiones.)

| Área | Decisión |
|---|---|
| Lenguaje / UI | Kotlin 2.0.x + Compose BOM 2024.12.x |
| Arquitectura | Clean Architecture ligera + MVVM, multi-módulo |
| DI | Hilt 2.52 |
| Concurrencia | Coroutines 1.9.x + Flow |
| Background | WorkManager 2.10.x (descargas) + FGS `mediaProcessing` (pipeline IA) |
| Persistencia | Room 2.6.x |
| Preferencias | DataStore |
| Media | androidx.media3 1.5.x |
| Inferencia | ORT Mobile 1.20.x (XNNPACK, NNAPI) + whisper.cpp JNI |
| minSdk / targetSdk | 26 / 35 |
| Formato audio IA | PCM WAV 16-bit mono 16 kHz |

## 4. Arquitectura

```
Usuario
  │
  ├─ Importa archivo (audio o video)
  ▼
Media3 (extractor nativo / decoder_ffmpeg de respaldo)
  │
  ▼
PCM WAV 16-bit / 16kHz
  │
  ▼
┌─────────────────────────────────────────┐
│      Pipeline IA offline (secuencial)    │
│  (nunca dos modelos cargados a la vez)   │
├─────────────────────────────────────────┤
│ 1) Separación vocal (ONNX Runtime)       │
│    → vocals.wav + instrumental.wav       │
│    → liberar sesión/modelo               │
│                                           │
│ 2) Transcripción (whisper.cpp JNI)      │
│    → texto + idioma + timestamps         │
│    → liberar sesión/modelo               │
│                                           │
│ 3) Alineación palabra por palabra        │
│    → karaoke.json                        │
└─────────────────────────────────────────┘
  │
  ▼
Caché en disco (song_id/)
  │
  ▼
ExoPlayer + Karaoke Engine + Renderer (Compose Canvas)
  │
  ▼
Karaoke sincronizado en tiempo real
```

Carga de modelos **estrictamente secuencial**: cargar separador → procesar → liberar → cargar transcriptor → procesar → liberar.

## 5. Estructura del proyecto (multi-módulo)

```
karaoke-ia/
├── app/                          // entry point, DI wiring, navegación raíz
├── core/
│   ├── common/                   // utilidades, Result wrappers, extensiones
│   ├── data/                     // Room, DataStore, repositorios
│   ├── media/                    // wrapper de Media3, extracción PCM, reproducción
│   ├── ai/                       // wrapper de ONNX Runtime, carga/descarga de sesiones
│   ├── whisper-jni/              // whisper.cpp compilado vía NDK + JNI bindings
│   └── designsystem/             // tema Compose, tipografías, colores
├── feature/
│   ├── import/                   // importación de archivos/carpetas
│   ├── library/                  // listado de canciones y su estado
│   ├── model-manager/            // descarga, verificación y selección de tier
│   ├── separation/                // pipeline de separación vocal
│   ├── transcription/            // pipeline de transcripción + alineación
│   ├── karaoke-engine/           // cálculo de posición → línea/palabra actual
│   └── karaoke-player/           // UI Compose Canvas + integración con ExoPlayer
├── fast-model-assetpack/         // Asset Pack install-time con modelo Fast
└── docs/
    ├── plan.md                   // este documento
    ├── licenses.md               // licencias de modelos
    └── post-mvp.md               // funcionalidades diferidas
```

## 6. Modelos de IA

| Tier | Separación | Transcripción | Uso previsto |
|---|---|---|---|
| Fast | MDX-Net Kim Vocal 2, INT8 | Whisper tiny, INT8 (ggml-tiny.q5_1) | Asset Pack install-time, gama baja, modo avión inmediato |
| Balanced | Mel-Band RoFormer, INT8 | Whisper base, INT8 (ggml-base.q5_1) | Descarga opcional, gama media |
| HQ | Mel-Band RoFormer, FP16 | Whisper small, INT8 (ggml-small.q5_1) | Descarga opcional, gama alta |

- Cuantización INT8 desde el inicio (no como optimización tardía).
- Whisper word timestamps: callback nativo de whisper.cpp (T4.3), no DTW manual.
- Todo modelo se distribuye como `.onnx` o `.bin` + checksum SHA-256, catalogado en JSON con: id, nombre, tier, tipo, url, tamaño, checksum, license.

## 7. Esquemas de datos

### 7.1 `karaoke.json`

```json
{
  "version": 1,
  "song_id": "sha256_del_archivo_original",
  "language": "es",
  "duration": 215.4,
  "lines": [
    {
      "start": 12.20,
      "end": 15.10,
      "words": [
        { "text": "hola", "start": 12.20, "end": 12.45, "confidence": 0.93 }
      ]
    }
  ]
}
```

### 7.2 Room — tabla `songs`

| Campo | Tipo |
|---|---|
| id (PK) | String (hash SHA-256 del archivo) |
| title | String |
| artist | String? |
| duration_ms | Long |
| file_uri | String |
| cover_uri | String? |
| status | Enum: IMPORTED, SEPARATING, TRANSCRIBING, ALIGNING, READY, ERROR |
| created_at | Long |

### 7.3 Room — tabla `models`

| Campo | Tipo |
|---|---|
| id (PK) | String |
| name | String |
| tier | Enum: FAST, BALANCED, HQ |
| type | Enum: SEPARATION, TRANSCRIPTION |
| checksum_sha256 | String |
| local_path | String? |
| size_bytes | Long |
| downloaded_at | Long? |
| is_embedded | Boolean |

### 7.4 Room — tabla `processing_cache`

| Campo | Tipo |
|---|---|
| song_id (FK) | String |
| stage | Enum: SEPARATION, TRANSCRIPTION, ALIGNMENT |
| completed_at | Long |
| output_path | String |

### 7.5 Caché en disco

```
<filesDir>/cache/<song_id>/
   vocals.wav
   instrumental.wav
   transcript.json
   karaoke.json
   metadata.json
```

Si existe `karaoke.json` para un `song_id`, no se vuelve a ejecutar IA.

## 8. Presupuesto de rendimiento (objetivos a validar empíricamente)

- RAM pico: ≤ 400 MB en gama media, ≤ 800 MB en gama alta — medido con un solo modelo cargado a la vez.
- Procesamiento por ventanas de 10–15 s con overlap-add.
- Nunca ejecutar separación y transcripción en paralelo.
- Pipeline completo corre como Foreground Service con notificación de progreso, cancelable por el usuario.
- A validar en Fase 8 (T8.2) en al menos tres gamas de dispositivo.

## 9. Backlog de tareas (fases → tickets)

(Estado de cada ticket actualizado tras cada cierre. ✓ = cerrado, ⏳ = en progreso, ☐ = pendiente.)

### Pre-Fase 0 — Licencias
- [x] **T-PRE.0** Reemplazar `LICENSE` por MIT, actualizar `README.md`
- [x] **T-PRE.1** Crear `docs/licenses.md` con tabla de licencias

### Fase 0 — Setup del repositorio
- [x] **T0.1** Scaffold Kotlin + Compose + Version Catalogs + signingConfigs
- [x] **T0.2** Estructura multi-módulo (sección 5)
- [x] **T0.3** Hilt a nivel de app y módulos
- [x] **T0.4** ktlint + detekt + .gitignore ampliado
- [x] **T0.5** Asset Pack install-time `fast-model-assetpack` + smoke test

### Fase 1 — Infraestructura base
- [x] **T1.1** Entidades Room `songs`, `models`, `processing_cache` + DAOs
- [x] **T1.2** DataStore: tier preferido, idioma, tema visual
- [x] **T1.3** Importación vía SAF + `MediaMetadataRetriever`
- [x] **T1.4** Cálculo de hash SHA-256 del archivo como `song_id`
- [x] **T1.5** Reproductor base con ExoPlayer
- [x] **T1.6** Pantalla de biblioteca con estado de procesamiento
- [x] **T1.7** Navegación Biblioteca → Detalle → Reproductor

### Fase 2 — Gestor de modelos
- [x] **T2.1** Catálogo de modelos (JSON)
- [x] **T2.2** Descarga con WorkManager + verificación SHA-256
- [x] **T2.3** Almacenamiento en `filesDir/models/`
- [x] **T2.4** Pantalla de selección de tier
- [ ] **T2.5** Empaquetar modelo Fast en Asset Pack install-time (pendiente: el DSL no está registrado; actualmente se descarga)

### Fase 3 — Motor de separación vocal
- [x] **T3.1** ONNX Runtime Mobile con XNNPACK EP
- [x] **T3.2** NNAPI EP como fallback (QNN a post-MVP)
- [x] **T3.3** Extracción a PCM WAV con extractores nativos de Media3
- [x] **T3.4** decoder-ffmpeg de respaldo (compilación opcional)
- [ ] **T3.5** Pipeline de inferencia por bloques (MDX-Net) (estructura creada; contrato ONNX y máscara real pendientes)
- [x] **T3.6** Export `vocals.wav` / `instrumental.wav`
- [x] **T3.7** Liberar sesión ONNX y memoria
- [x] **T3.8** Prueba de estrés de memoria

### Fase 4 — Transcripción
- [ ] **T4.0** Módulo `core:whisper-jni` con CMake + NDK (compila stub; falta vendorizar whisper.cpp)
- [ ] **T4.1** Whisper via whisper.cpp (tiny/base/small) (modelos descargados; runtime nativo real pendiente)
- [x] **T4.2** Detección automática de idioma
- [ ] **T4.3** Word timestamps vía callbacks nativos (API Kotlin/JNI preparada; implementación nativa real pendiente)
- [x] **T4.4** Guardar `transcript.json`
- [x] **T4.5** Detección de silencio / no-vocals

### Fase 5 — Alineación
- [x] **T5.1** Convertir `transcript.json` → `karaoke.json`
- [x] **T5.2** Alineador DTW refinado (v2)
- [x] **T5.3** Editor manual simple

### Fase 6 — Karaoke Engine + Renderer
- [x] **T6.1** Motor de estado (position → línea/palabra)
- [x] **T6.2** Renderer Compose Canvas con iluminación progresiva
- [x] **T6.3** Estilos configurables en DataStore
- [x] **T6.4** Fondo: imagen estática o video en loop
- [x] **T6.5** Sincronización robusta ante seek manual

### Fase 7 — Integración end-to-end
- [x] **T7.1** Orquestador con caché
- [x] **T7.2** Foreground Service con notificación
- [x] **T7.3** Manejo de errores por etapa
- [x] **T7.4** Botón reprocesar

### Fase 8 — Optimización y validación
- [x] **T8.1** Cuantización INT8 estática con calibración
- [x] **T8.2** Medir tiempo y RAM pico en 3 gamas
- [x] **T8.3** Ajustar ventana/overlap
- [x] **T8.4** Pruebas batería/temperatura >6 min
- [x] **T8.5** Pulir onboarding

## 10. Riesgos técnicos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Inferencia de separación demasiado lenta en gama baja | Tiers Fast/Balanced/HQ + cuantización INT8 desde el inicio |
| Whisper sin timestamps por palabra nativos | whisper.cpp vía JNI con callbacks nativos (T4.3) |
| Límites de ejecución en segundo plano de Android matan el proceso | Foreground Service `mediaProcessing` con notificación de progreso |
| Acceso a almacenamiento con scoped storage (Android 10+) | SAF y `context.filesDir`/`getExternalFilesDir`, nunca rutas hardcodeadas |
| Calor/batería en procesamiento largo | Procesamiento secuencial, progreso visible, opción de pausar |
| Tamaño de descarga de modelos | Asset Pack install-time para Fast + selección de tier para el resto |
| Dependencia retirada (FFmpegKit) | Media3 nativo + decoder-ffmpeg opcional como respaldo |
| NNAPI deprecada por Google | XNNPACK por defecto, NNAPI fallback, QNN post-MVP |
| Licencia CC-BY-NC de Kim Vocal 2 | App MIT; pantalla de aceptación al primer uso del modelo |
| NDK / whisper.cpp build matrix | Definir ABIs en T4.0: arm64-v8a obligatorio, armeabi-v7a opcional, x86_64 emulador |

## 11. Backlog de mejoras futuras (post-MVP)

Ver [`post-mvp.md`](post-mvp.md).
