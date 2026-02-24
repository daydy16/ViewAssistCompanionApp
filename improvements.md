# Improvements & Future Enhancements

Observations noted during the migration from openWakeWord (ONNX) to microWakeWord (TFLite).

---

## 1. Audio Pipeline

### WebRTC-based Audio Processing
The current implementation uses Android system audio effects (AEC, AGC, NS) which depend on device OEM support and may vary in quality. Consider integrating [WebRTC's native audio processing module](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/audio_processing/) directly for consistent, high-quality noise suppression and echo cancellation across all devices.

### Voice Activity Detection (VAD)
Add a lightweight VAD (e.g., Silero VAD or the WebRTC VAD) upstream of wake word detection to skip processing during silence. This would reduce CPU usage and false positives, especially in noisy environments.

### Adaptive Gain Control
The current AGC uses the system `AutomaticGainControl` effect. A software-level adaptive gain normalization could ensure consistent audio levels regardless of microphone sensitivity, improving detection reliability on budget tablets.

---

## 2. Wake Word Detection

### Model Hot-Swap
Currently, changing the active wake word requires stopping and restarting detection (`restartWakeWordDetection`). A more sophisticated approach would allow adding/removing models from `WakeWordDetector` without interrupting the audio stream or resetting `MicroFrontend` state.

### Multi-Model Simultaneous Detection
The architecture already supports multiple active models. Exposing this to the user (e.g., "respond to both 'Hey Jarvis' and 'Alexa'") would be a differentiating feature. The sliding window and MicroFrontend are already shared, so the overhead per additional model is minimal.

### Confidence History & Adaptive Thresholds
Track detection confidence over time to auto-tune thresholds per environment. For example, if a model consistently triggers at 0.85 in a quiet room but only at 0.6 near an HVAC unit, the app could adapt. The `slidingWindowSize` in the JSON manifest is a starting point but is static.

### Detection Cooldown
After a successful detection, impose a configurable cooldown period (e.g., 2–3 seconds) to prevent duplicate triggers from echo or repeated utterance. The current implementation re-arms immediately.

### TFLite GPU/NNAPI Delegate
The current TFLite inference runs on CPU. For devices with capable NPUs (e.g., Google Tensor, Qualcomm Hexagon), using NNAPI or GPU delegates could reduce latency and power consumption. Check `Interpreter.Options().addDelegate(NnApiDelegate())`.

---

## 3. Model Management

### OTA Model Updates
Wake word models are currently bundled in `assets/wakeWords/`. Adding support for downloading updated/new models from a remote server (e.g., Home Assistant add-on or GitHub releases) would allow model improvements without app updates.

### Custom Model Training Pipeline
Document or provide tooling for users to train their own microWakeWord models and deploy them via the `Downloads/vaca/` custom model directory. The microWakeWord training pipeline is open-source.

### Model Versioning
The JSON manifests currently contain `probability_cutoff` and `sliding_window_size` but no version field. Adding a `version` field would help track which model revision is deployed and assist with OTA updates.

---

## 4. Architecture & Code Quality

### Coroutines in WakeWordDetector
`WakeWordDetector.detect()` is currently synchronous and called from the audio collection coroutine. For multi-model scenarios, running each `MicroWakeWord.processFeatures()` in parallel via `async`/`awaitAll` could reduce latency when many models are active.

### Resource Lifecycle
`WakeWordDetector.close()` releases TFLite interpreters and MicroFrontend, but there's no `Closeable`/`AutoCloseable` interface implemented. Adding this would enable `use {}` blocks and prevent resource leaks.

### Unit Testing
The microWakeWord pipeline has no unit tests in VACA. Consider adding:
- `TensorBuffer` quantization/dequantization round-trip tests
- `MicroWakeWord` inference tests with known audio fixtures
- `WakeWordDetector` integration tests with synthetic PCM data

### MicroFrontend Module Packaging
The `microfeatures` module currently uses package `com.example.microfeatures`. Consider renaming to `com.msp1974.vacompanion.microfeatures` or publishing it as a standalone AAR for reuse.

---

## 5. Diagnostics & Observability

### Real-Time Audio Level Meter
Expose the input audio RMS level in diagnostics to help users verify their microphone is working and at an appropriate level before troubleshooting wake word sensitivity.

### Detection Event Log
Log the last N detection events (timestamp, model, probability, threshold) and make them accessible via the diagnostic screen or exported to Home Assistant as sensor attributes.

### MicroFrontend Feature Visualization
For advanced debugging, rendering the 40-band mel spectrogram features from MicroFrontend as a real-time waterfall display would help diagnose audio quality issues.

---

## 6. Compatibility Notes

### Ava Codebase Age
The Ava reference implementation used for this migration may be older than the latest version. Key areas to check for updates:
- MicroFrontend C++ code (newer TF Lite Micro versions may have improvements)
- Model file format changes in newer microWakeWord releases
- Updated probability cutoffs in newer model releases

### LiteRT (TFLite) Version
Currently using LiteRT 2.16.1. Newer versions may include performance improvements and bug fixes. Monitor [Maven Central](https://mvnrepository.com/artifact/com.google.ai.edge.litert/litert) for updates.
