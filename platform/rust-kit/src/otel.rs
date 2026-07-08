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
    if let Ok(endpoint) = env::var("OTEL_EXPORTER_OTLP_ENDPOINT") {
        if !endpoint.trim().is_empty() {
            builder = builder.with_endpoint(endpoint);
        }
    }
    let exporter = builder.build()?;
    Ok(init_with_exporter(service_name, exporter))
}

pub fn init_with_exporter<E>(service_name: &str, exporter: E) -> OtelGuard
where
    E: sdktrace::SpanExporter + 'static,
{
    let service_name = env::var("OTEL_SERVICE_NAME")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| service_name.to_string());
    let provider = sdktrace::SdkTracerProvider::builder()
        .with_simple_exporter(exporter)
        .with_resource(
            Resource::builder_empty()
                .with_attribute(KeyValue::new("service.name", service_name))
                .build(),
        )
        .build();
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

#[cfg(test)]
mod tests {
    use super::*;
    use opentelemetry::trace::Tracer;
    use opentelemetry_sdk::trace::InMemorySpanExporterBuilder;

    #[test]
    fn init_from_env_is_noop_without_otel_traces_exporter() {
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
