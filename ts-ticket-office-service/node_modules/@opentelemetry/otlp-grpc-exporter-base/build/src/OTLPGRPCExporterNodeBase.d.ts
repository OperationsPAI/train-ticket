import { GRPCQueueItem, OTLPGRPCExporterConfigNode } from './types';
import { CompressionAlgorithm, OTLPExporterBase, OTLPExporterError } from '@opentelemetry/otlp-exporter-base';
import { ISerializer } from './serializers';
/**
 * OTLP Exporter abstract base class
 */
export declare abstract class OTLPGRPCExporterNodeBase<ExportItem, ServiceRequest, ServiceResponse> extends OTLPExporterBase<OTLPGRPCExporterConfigNode, ExportItem, ServiceRequest> {
    grpcQueue: GRPCQueueItem<ExportItem>[];
    compression: CompressionAlgorithm;
    private _transport;
    private _serializer;
    constructor(config: OTLPGRPCExporterConfigNode | undefined, signalSpecificMetadata: Record<string, string>, grpcName: string, grpcPath: string, serializer: ISerializer<ServiceRequest, ServiceResponse>);
    onInit(): void;
    onShutdown(): void;
    send(objects: ExportItem[], onSuccess: () => void, onError: (error: OTLPExporterError) => void): void;
    abstract getUrlFromConfig(config: OTLPGRPCExporterConfigNode): string;
}
//# sourceMappingURL=OTLPGRPCExporterNodeBase.d.ts.map