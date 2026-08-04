# KaraokIAndroid

Aplicación Android de karaoke asistido por IA. Importa audio o vídeo local, prepara una pista instrumental, transcribe la letra y la muestra sincronizada palabra por palabra.

El procesamiento está diseñado para ejecutarse en el dispositivo. La red se limita al gestor de modelos; reproducción, extracción, inferencia, transcripción y renderizado no deben realizar llamadas de red.

## Estado Actual

El proyecto ya tiene un esqueleto Android compilable y un pipeline de código para:

- Importación mediante Storage Access Framework.
- Persistencia Room y preferencias DataStore.
- Reproducción con Media3/ExoPlayer.
- Caché por canción usando SHA-256.
- Inferencia ONNX Runtime con XNNPACK y fallback NNAPI.
- Transcripción mediante la interfaz JNI de whisper.cpp.
- Motor de líneas, palabras, progreso y renderizado Compose Canvas.
- Foreground Service para procesamiento largo.
- Audio WAV de prueba local creado automáticamente para validar rendimiento.
- Scripts Python para descargar, convertir, cuantizar y verificar modelos.

`core:whisper-jni` ya enlaza una versión fijada de whisper.cpp cuando se ejecuta `scripts/fetch_whisper.ps1`. El contrato exacto del modelo de separación todavía debe validarse con pesos de producción.

## Requisitos

- Android Studio instalado.
- Android SDK con API 35.
- JBR de Android Studio.
- Python 3.11+ para los scripts de modelos.
- Un entorno Python con las dependencias de [`scripts/requirements.txt`](scripts/requirements.txt).

En este equipo se usa:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\migue\AppData\Local\Android\Sdk"
```

## Build Android

Desde la raíz del repositorio:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --no-daemon
```

El APK se genera en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para regenerar el wrapper:

```powershell
.\gradlew.bat wrapper --gradle-version 8.10.2 --distribution-type bin
```

## Tests Python

```powershell
$env:PYTHONPATH = "."
$env:KMP_DUPLICATE_LIB_OK = "TRUE"
python -m pytest scripts/tests -v
```

Si el Python del sistema no tiene `pytest`, usa un entorno con [`scripts/requirements.txt`](scripts/requirements.txt). El test de transcripción se omite si `whisper-cli` no está en `PATH`.

## Modelos

El gestor Python está en [`scripts/models`](scripts/models):

```powershell
# Descargar Whisper y preparar separación
python -m scripts.models.download_models

# Verificar salida FP32 frente a INT8
python -m scripts.models.verify_models

# Crear un modelo estructural sintético para pruebas
python -m scripts.models.build_synthetic_kim
```

Los artefactos descargados y generados no deben commitearse:

- `scripts/download_cache/`
- `scripts/build_output/`
- Binarios dentro de `fast-model-assetpack/src/main/assets/`

El modelo sintético sirve únicamente para probar conversión, cuantización y compatibilidad ONNX; no tiene calidad musical.

## Arquitectura

```text
app
├── core:common          Tipos compartidos y utilidades JVM
├── core:data            Room, DataStore, repositorios y caché
├── core:media           Media3, extracción PCM y ExoPlayer
├── core:ai              ONNX Runtime y carga de modelos
├── core:whisper-jni     Puente JNI para whisper.cpp
├── core:designsystem    Tema y componentes Compose
└── feature:*
    import               Importación SAF
    library              Biblioteca y estados
    model-manager        Catálogo, descargas y checksums
    separation           Separación vocal
    transcription        Whisper y transcript.json
    karaoke-engine       Lógica JVM pura de líneas y palabras
    karaoke-player       Renderer y reproducción
    pipeline             Orquestación y Foreground Service
```

Reglas importantes:

- `core:common` no depende de Android.
- `feature:karaoke-engine` contiene lógica pura; Room y servicios Android pertenecen a `feature:pipeline`.
- El paquete Kotlin de `feature/import` es `com.karaokei.feature.importer` porque `import` es palabra reservada.
- No añadir `media3-decoder-ffmpeg` desde Maven: no está publicado como dependencia utilizable; la ruta actual usa extractores nativos de Media3.
- Las clases con constructor `@Inject` son proporcionadas automáticamente por Hilt; no crear providers redundantes que generen ciclos.

## Documentación

- [`AGENTS.md`](AGENTS.md): instrucciones para agentes y comandos verificados.
- [`TODO.md`](TODO.md): tareas pendientes de implementación y validación.
- [`docs/plan.md`](docs/plan.md): backlog y decisiones técnicas.
- [`docs/licenses.md`](docs/licenses.md): licencias de modelos y dependencias.
- [`docs/post-mvp.md`](docs/post-mvp.md): funcionalidades diferidas y riesgos conocidos.

## Licencia

El código de la aplicación se distribuye bajo [MIT License](LICENSE). Los pesos y modelos de IA conservan sus propias licencias; deben revisarse en [`docs/licenses.md`](docs/licenses.md) antes de redistribuirlos.
