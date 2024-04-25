"use strict";
/*
 * Copyright The OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.MetricsSerializer = exports.TraceSerializer = exports.LogsSerializer = void 0;
const root = require("./generated/root");
const logsResponseType = root.opentelemetry.proto.collector.logs.v1
    .ExportLogsServiceResponse;
const logsRequestType = root.opentelemetry.proto.collector.logs.v1
    .ExportLogsServiceRequest;
const metricsResponseType = root.opentelemetry.proto.collector.metrics.v1
    .ExportMetricsServiceResponse;
const metricsRequestType = root.opentelemetry.proto.collector.metrics.v1
    .ExportMetricsServiceRequest;
const traceResponseType = root.opentelemetry.proto.collector.trace.v1
    .ExportTraceServiceResponse;
const traceRequestType = root.opentelemetry.proto.collector.trace.v1
    .ExportTraceServiceRequest;
exports.LogsSerializer = {
    serializeRequest: (arg) => {
        return Buffer.from(logsRequestType.encode(arg).finish());
    },
    deserializeResponse: (arg) => {
        return logsResponseType.decode(arg);
    },
};
exports.TraceSerializer = {
    serializeRequest: (arg) => {
        return Buffer.from(traceRequestType.encode(arg).finish());
    },
    deserializeResponse: (arg) => {
        return traceResponseType.decode(arg);
    },
};
exports.MetricsSerializer = {
    serializeRequest: (arg) => {
        return Buffer.from(metricsRequestType.encode(arg).finish());
    },
    deserializeResponse: (arg) => {
        return metricsResponseType.decode(arg);
    },
};
//# sourceMappingURL=serializers.js.map