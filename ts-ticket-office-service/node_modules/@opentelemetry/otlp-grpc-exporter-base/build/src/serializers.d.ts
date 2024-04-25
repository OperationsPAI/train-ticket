import { IExportLogsServiceRequest, IExportLogsServiceResponse, IExportMetricsServiceRequest, IExportMetricsServiceResponse, IExportTraceServiceRequest, IExportTraceServiceResponse } from '@opentelemetry/otlp-transformer';
/**
 * Serializes and deserializes the OTLP request/response to and from {@link Uint8Array}
 */
export interface ISerializer<Request, Response> {
    serializeRequest(request: Request): Uint8Array | undefined;
    deserializeResponse(data: Uint8Array): Response;
}
export declare const LogsSerializer: ISerializer<IExportLogsServiceRequest, IExportLogsServiceResponse>;
export declare const TraceSerializer: ISerializer<IExportTraceServiceRequest, IExportTraceServiceResponse>;
export declare const MetricsSerializer: ISerializer<IExportMetricsServiceRequest, IExportMetricsServiceResponse>;
//# sourceMappingURL=serializers.d.ts.map