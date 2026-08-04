# Licencias de modelos y dependencias de IA

Este documento centraliza la información de licencias de los modelos de IA empaquetados en la app o descargados en tiempo de ejecución, así como de las dependencias de inferencia y decodificación.

## App (código fuente)

| Componente | Licencia | Archivo |
|---|---|---|
| Código de la app | MIT | [`../LICENSE`](../LICENSE) |

El código se distribuye bajo MIT. Esto significa que la app, por sí misma, no impone restricciones de uso (comercial incluido) sobre el código fuente. Sin embargo, **los modelos de IA distribuidos dentro o junto con la app mantienen sus propias licencias**, y esas restricciones siguen aplicando al uso de los modelos aunque el código de la app sea MIT.

## Modelos de IA

### Modelos de separación vocal (core:ai + feature:separation)

| Tier | Modelo | Licencia | Distribución |
|---|---|---|---|
| Fast | MDX-Net Kim Vocal 2 (ONNX INT8) | **CC-BY-NC 4.0** (no comercial) | Asset Pack install-time `fast-model-assetpack` |
| Balanced | Mel-Band RoFormer `silverdaw/mel-band-roformer-vocals-onnx` | MIT; pesos externos, verificar distribución final | Descarga opcional, pendiente de sidecar |
| HQ | Mel-Band RoFormer `silverdaw/mel-band-roformer-vocals-onnx` | MIT; pesos externos, verificar distribución final | Descarga opcional, pendiente de sidecar |

**Notas sobre Kim Vocal 2 (CC-BY-NC):**
- Los pesos se distribuyen con su propia nota de atribución y la restricción "no comercial".
- Esta restricción aplica a quien use los pesos, no al código de la app (que es MIT).
- En el primer arranque, la app muestra y exige aceptación de esta restricción antes de habilitar el pipeline.
- Si en el futuro se sustituye este modelo por uno con licencia compatible con uso comercial (Apache 2.0, MIT, etc.), se actualiza este documento y la pantalla de aceptación.

**Notas sobre Mel-Band RoFormer:**
- El repositorio de código es MIT.
- **TBD (T3.1)**: confirmar licencia de los pesos antes de añadir a la Fase 3. Si los pesos son CC-BY-NC, se aplica la misma pantalla de aceptación que para Kim Vocal 2.

### Modelos de transcripción (core:whisper-jni + feature:transcription)

| Tier | Modelo | Licencia | Distribución |
|---|---|---|---|
| Fast | Whisper tiny (`ggml-tiny.en-q5_1.bin` o equivalente cuantizado) | **MIT** | Descarga gestionada; Asset Pack pendiente |
| Balanced | Whisper base (`ggml-base-q5_1.bin`) | MIT | Descarga opcional |
| HQ | Whisper small (`ggml-small-q5_1.bin`) | MIT | Descarga opcional |

**Notas sobre Whisper:**
- El modelo Whisper original (OpenAI) se distribuye bajo MIT.
- Los `ggml-*.bin` de `ggerganov/whisper.cpp` también bajo MIT.
- No hay restricciones de uso comercial.
- Verificar antes de distribuir que el `.bin` concreto elegido está bajo la licencia MIT (algunos mirrors redistribuyen con otras condiciones).

## Dependencias de inferencia y decodificación

| Componente | Licencia | Cómo se usa |
|---|---|---|
| ONNX Runtime Mobile | MIT | Inferencia de separación vocal |
| whisper.cpp | MIT | Inferencia de transcripción (vía JNI) |
| androidx.media3 (ExoPlayer) | Apache 2.0 | Reproducción, extracción de audio |
| androidx.media3-decoder-ffmpeg | GPLv3 (build oficial) | **Solo si se compila como respaldo**; consultar `docs/post-mvp.md` |
| FFmpeg (binarios) | GPLv3 (build con `--enable-gpl`) | Solo si la extensión decoder-ffmpeg está habilitada |

**Aviso sobre la extensión `media3-decoder-ffmpeg`:**
- Si se compila con GPLv3 (necesario para algunos códecs propietarios), el AAB que la contiene debe cumplir los términos de GPLv3 (incluida la oferta de source).
- Para el MVP, **no se compila esta extensión**: los extractores nativos de Media3 cubren mp3/flac/wav/m4a/mp4/mkv. Ver T3.3 y T3.4.

## Cómo añadir un nuevo modelo

1. Añadir una entrada en la tabla de arriba con: tier, nombre, licencia, URL de origen, tamaño, checksum SHA-256.
2. Si la licencia **no es MIT/Apache 2.0/BSD**, añadir la pantalla de aceptación al primer uso (en `feature:model-manager`).
3. Si la licencia **es restrictiva** (CC-BY-NC, CC-BY-NC-SA, etc.), documentar el alcance de la restricción en este archivo.
4. Verificar que la entrada del catálogo JSON (T2.1) lleva el campo `license` correcto y `isEmbedded` si aplica.

## Auditoría de red (offline-first)

Por restricción de diseño, **ningún módulo distinto de `feature:model-manager` debe hacer llamadas de red** (HttpClient, Retrofit, OkHttp, URLConnection, etc.). Verificación periódica:

```bash
rg -t kotlin "HttpClient|Retrofit|OkHttp|URLConnection" --glob '!feature/model-manager/**'
```

Si hay coincidencias fuera de `feature/model-manager/`, tratarlo como bug y reportarlo.
