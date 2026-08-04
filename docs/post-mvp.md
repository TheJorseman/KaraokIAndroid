# Post-MVP: funcionalidades diferidas

Funcionalidades identificadas durante el diseño pero **fuera del alcance del MVP**. Se priorizan tras cerrar la Fase 8.

## Separación

- **Stems por instrumento** (batería, bajo, guitarra, piano) — modelos de 4/6 stems. Mayor coste de RAM y latencia, requiere tier HQ bien afinado.
- **Modelo de separación propio** entrenado con MUSDB18 bajo licencia libre, para eliminar la dependencia de Kim Vocal 2 (CC-BY-NC).

## Audio

- **Cambio de tono** (pitch shifting) en tiempo real sobre el instrumental.
- **Time stretching** sin alterar el tono (para practicar a velocidad reducida).
- **Reverb y ecualización** del instrumental.

## Captación de voz del usuario

- **Grabación de la voz del usuario** sobre el karaoke.
- **Puntuación de afinación** por comparación con la vocal original extraída (pitch tracking + DTW).
- **Modo "solo instrumental"** para ensayo.

## Exportación y sharing

- **Exportar video** con el karaoke ya renderizado encima.
- **Compartir** resultados en redes sociales o apps de mensajería.
- **Descarga opcional de carátulas y metadatos** (red, opt-in explícito por el usuario).

## Plataformas

- **Android Auto** (visualización en el coche, controles de voz).
- **Chromecast** (reproducir en TV con el móvil como mando).
- **Wear OS** (control remoto desde el reloj).

## Modelo y extensibilidad

- **Sistema de plugins** para añadir nuevos modelos ONNX sin actualizar la app. Implica un contrato de I/O estable (formato de audio, formato de salida) y una pantalla de carga de modelos externos.
- **Calibración en dispositivo** (quantization-aware training o fine-tuning on user data) para mejorar la calidad del modelo Fast con datos locales.

## Aceleración hardware

- **QNN Execution Provider** (Qualcomm NPU) — requiere el Qualcomm AI Engine SDK bajo NDA. Beneficio claro en gama alta Snapdragon. Decisión: implementar cuando el proyecto tenga usuarios en dispositivos Qualcomm y se justifique el esfuerzo de build bajo NDA.
- **CoreML Execution Provider** (iOS, irrelevant para Android, descartado).
- **GPU delegate** de ONNX Runtime para dispositivos compatibles.

## Modelo RoFormer disponible

Se verificó el fallback público MIT `silverdaw/mel-band-roformer-vocals-onnx`.
El grafo carga correctamente en ONNX Runtime, pero requiere un archivo externo
de pesos de aproximadamente 707 MB y trabaja con STFT host-side a 44.1 kHz,
`n_fft=2048`, `hop=441`, entrada `[1, 2050, 1101, 2]`. Antes de usarlo en Android
hay que implementar carga de archivos externos, STFT/iSTFT compatible y una
estrategia de cuantización; no debe sustituirse silenciosamente por el modelo
MDX del pipeline actual.

## Productividad y UX

- **Búsqueda en biblioteca** por título, artista, letra transcrita.
- **Listas de reproducción**.
- **Sincronización entre dispositivos** (opt-in, mediante archivo `.kpkg` portable con cache + karaoke.json).
- **Modo de bajo consumo**: tier Fast forzado, ventanas más pequeñas, sin notificación sonora al terminar.

## Editor

- **Editor completo de karaoke.json**: reorganizar líneas, fusionar, dividir, añadir estrofas omitidas por el transcriptor.

## Internacionalización

- **Traducción de la UI** a más idiomas. Whisper soporta muchos; la UI por defecto arranca en inglés y español.

## Auditoría y diagnóstico

- **Modo diagnóstico** con log de timings por etapa, uso de RAM, versión de modelos, hash de inputs.
- **Auto-reporte** opcional (opt-in) de crashes y métricas anonimizadas.
