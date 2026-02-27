//! Custom audio sink that passes PCM samples across JNI to Android's AudioTrack.
//!
//! For Phase 1, we use a callback-based approach: Rust decodes audio and calls
//! a JNI method to deliver PCM buffers to the Kotlin side, which feeds them to
//! AudioTrack.
//!
//! Future: Replace with oboe-rs for zero-copy native audio output.

use std::sync::{Arc, Mutex, OnceLock};

use jni::JavaVM;
use jni::objects::GlobalRef;
use librespot_playback::audio_backend::{Sink, SinkAsBytes, SinkError, SinkResult};
use librespot_playback::config::AudioFormat;
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;

/// State for the JNI audio callback.
struct JniCallbackState {
    jvm: Arc<JavaVM>,
    callback: GlobalRef,
}

// Global storage for the JVM and callback references, set from Kotlin side.
static JNI_AUDIO_STATE: OnceLock<Arc<Mutex<JniCallbackState>>> = OnceLock::new();

/// Register the JVM and audio callback from the Kotlin side.
/// Called once during initialization.
pub fn register_audio_callback(jvm: Arc<JavaVM>, callback: GlobalRef) {
    let state = Arc::new(Mutex::new(JniCallbackState { jvm, callback }));
    let _ = JNI_AUDIO_STATE.set(state);
    log::info!("JNI audio callback registered");
}

/// Audio sink that delivers PCM data to the Kotlin layer via JNI.
pub struct JniAudioSink {
    #[allow(dead_code)]
    format: AudioFormat,
    callback_state: Option<Arc<Mutex<JniCallbackState>>>,
}

impl JniAudioSink {
    pub fn new(format: AudioFormat) -> Self {
        Self {
            format,
            callback_state: JNI_AUDIO_STATE.get().cloned(),
        }
    }
}

impl Sink for JniAudioSink {
    fn start(&mut self) -> SinkResult<()> {
        log::debug!("JniAudioSink: start");
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        log::debug!("JniAudioSink: stop");
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        use zerocopy::IntoBytes;
        match packet {
            AudioPacket::Samples(samples) => {
                // Convert f64 samples to S16 (the default format) and write bytes
                let samples_s16: &[i16] = &converter.f64_to_s16(&samples);
                self.write_bytes(samples_s16.as_bytes())
            }
            AudioPacket::Raw(raw) => self.write_bytes(&raw),
        }
    }
}

impl SinkAsBytes for JniAudioSink {
    fn write_bytes(&mut self, data: &[u8]) -> SinkResult<()> {
        let state = match &self.callback_state {
            Some(s) => s,
            None => {
                log::warn!("JniAudioSink: no callback registered, dropping audio");
                return Ok(());
            }
        };

        let state = state.lock().map_err(|e| {
            SinkError::OnWrite(format!("lock poisoned: {e}"))
        })?;

        let mut env = state.jvm.attach_current_thread().map_err(|e| {
            SinkError::OnWrite(format!("JVM attach failed: {e}"))
        })?;

        // Create a Java byte array from the PCM data
        let byte_array = env.new_byte_array(data.len() as i32).map_err(|e| {
            SinkError::OnWrite(format!("new_byte_array failed: {e}"))
        })?;

        // Safety: reinterpret &[u8] as &[i8] for JNI (same memory layout)
        let jni_data: &[i8] = unsafe {
            std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len())
        };

        env.set_byte_array_region(&byte_array, 0, jni_data).map_err(|e| {
            SinkError::OnWrite(format!("set_byte_array_region failed: {e}"))
        })?;

        // Call the Kotlin callback: void onAudioData(byte[] data)
        env.call_method(
            &state.callback,
            "onAudioData",
            "([B)V",
            &[(&byte_array).into()],
        ).map_err(|e| {
            SinkError::OnWrite(format!("callback failed: {e}"))
        })?;

        Ok(())
    }
}
