use std::env;
use std::sync::Arc;

use opentelemetry::{KeyValue, global};
use opentelemetry_otlp::WithExportConfig;
use opentelemetry_sdk::{Resource, trace as sdktrace};

#[derive(Clone)]
pub struct OtelGuard {
    provider: Option<Arc<sdktrace::SdkTracerProvider>>,
}

impl OtelGuard {
    pub fn noop() -> Self {
        Self { provider: None }
    }

    pub fn shutdown(&self) -> Result<(), opentelemetry_sdk::error::OTelSdkError> {
        if let Some(provider) = &self.provider {
            provider.shutdown()
        } else {
            Ok(())
        }
    }

    pub fn force_flush(&self) -> Result<(), opentelemetry_sdk::error::OTelSdkError> {
        if let Some(provider) = &self.provider {
            provider.force_flush()
        } else {
            Ok(())
        }
    }
}

impl Drop for OtelGuard {
    fn drop(&mut self) {
        let _ = self.shutdown();
    }
}

pub fn init_from_env(
    service_name: &str,
) -> Result<OtelGuard, opentelemetry_otlp::ExporterBuildError> {
    if !tracing_enabled() || !otlp_endpoint_configured() {
        return Ok(OtelGuard::noop());
    }
    let mut builder = opentelemetry_otlp::SpanExporter::builder().with_tonic();
    // The traces-specific endpoint takes precedence over the generic one,
    // matching the OTel SDK env-var specification; gating accepts either,
    // so the exporter must honor either too.
    let endpoint = env::var("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| {
            env::var("OTEL_EXPORTER_OTLP_ENDPOINT")
                .ok()
                .filter(|value| !value.trim().is_empty())
        });
    if let Some(endpoint) = endpoint {
        builder = builder.with_endpoint(endpoint);
    }
    let exporter = builder.build()?;
    // Batch export runs on a dedicated background thread. A simple
    // (synchronous) exporter performs one blocking gRPC export per span end
    // on the ending thread, which stalls the tokio runtime under event load
    // until liveness probes kill the pod (observed live 2026-07-08).
    let provider = sdktrace::SdkTracerProvider::builder()
        .with_batch_exporter(exporter)
        .with_resource(service_resource(service_name))
        .build();
    Ok(install_provider(provider))
}

/// Test-oriented init: the simple (synchronous) processor makes spans visible
/// to in-memory exporters immediately. Production init goes through
/// [`init_from_env`], which batches.
pub fn init_with_exporter<E>(service_name: &str, exporter: E) -> OtelGuard
where
    E: sdktrace::SpanExporter + 'static,
{
    let provider = sdktrace::SdkTracerProvider::builder()
        .with_simple_exporter(exporter)
        .with_resource(service_resource(service_name))
        .build();
    install_provider(provider)
}

fn service_resource(service_name: &str) -> Resource {
    let service_name = env::var("OTEL_SERVICE_NAME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| service_name.to_string());
    Resource::builder_empty()
        .with_attribute(KeyValue::new("service.name", service_name))
        .build()
}

fn install_provider(provider: sdktrace::SdkTracerProvider) -> OtelGuard {
    global::set_tracer_provider(provider.clone());
    OtelGuard {
        provider: Some(Arc::new(provider)),
    }
}

pub fn tracing_enabled() -> bool {
    let exporter = env::var("OTEL_TRACES_EXPORTER")
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    !exporter.is_empty() && exporter != "none"
}

fn otlp_endpoint_configured() -> bool {
    env::var("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .is_some()
        || env::var("OTEL_EXPORTER_OTLP_ENDPOINT")
            .ok()
            .filter(|value| !value.trim().is_empty())
            .is_some()
}

/// Serializes tests that touch the process-global tracer provider or
/// OTEL_* environment; parallel test threads otherwise race and spans land
/// in another test's exporter.
#[cfg(test)]
pub(crate) fn test_serial() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    LOCK.lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::trace::Tracer;
    use opentelemetry_sdk::trace::InMemorySpanExporterBuilder;

    #[test]
    fn init_from_env_is_noop_without_otel_traces_exporter() {
        let _serial = super::test_serial();
        unsafe {
            env::remove_var("OTEL_TRACES_EXPORTER");
            env::remove_var("OTEL_EXPORTER_OTLP_ENDPOINT");
            env::remove_var("OTEL_SERVICE_NAME");
        }

        let guard = init_from_env("rust-kit-test").expect("noop init succeeds");
        assert!(guard.provider.is_none());
    }

    #[test]
    fn in_memory_exporter_receives_span() {
        let _serial = super::test_serial();
        unsafe {
            env::set_var("OTEL_SERVICE_NAME", "rust-kit-test");
        }
        let exporter = InMemorySpanExporterBuilder::new().build();
        let exported = exporter.clone();
        let guard = init_with_exporter("rust-kit-test", exporter);

        let tracer = global::tracer("rust-kit-test");
        let span = tracer.start("test-span");
        drop(span);
        guard.force_flush().expect("flush spans");

        let spans = exported.get_finished_spans().expect("finished spans");
        assert_eq!(spans.len(), 1);
        assert_eq!(spans[0].name, "test-span");
    }
}
