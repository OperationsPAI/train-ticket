from flask import Flask, request, jsonify
import numpy as np
import urllib
import cv2
import os
import json
import base64
import traceback

from face_detect import check

from opentelemetry import trace
from opentelemetry.instrumentation.wsgi import collect_request_attributes
from opentelemetry.propagate import extract
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
    OTLPSpanExporter,
)
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.resources import Resource


app = Flask(__name__)

span_exporter = OTLPSpanExporter(
    # optional
    endpoint=os.environ.get('OTEL_EXPORTER_OTLP_ENDPOINT'),
    # credentials=ChannelCredentials(credentials),
    # headers=(("metadata", "metadata")),
)

trace.set_tracer_provider(TracerProvider(resource=Resource.create(
    {"service.name": os.environ.get(
        'OTEL_SERVICE_NAME'), 
        "k8s.pod.name": os.environ.get('OTEL_K8S_POD_NAME')},
)))

trace.set_tracer_provider(TracerProvider())
tracer = trace.get_tracer_provider().get_tracer(__name__)

trace.get_tracer_provider().add_span_processor(
    BatchSpanProcessor(span_exporter)
)

# TODO:
# ~~1. 获取图片~~
#  ~2. 检测图片是否ok
#  ~3. 人脸检测&切割
#  ~4. 返回base64格式的图片
# 5. 前端传文件
# 6. Dockerfile部署

receive_path = r"./received/"


@app.route('/api/v1/avatar', methods=["POST"])
def hello():
    with tracer.start_as_current_span(
        "/api/v1/avatar",
        context=extract(request.headers),
        kind=trace.SpanKind.SERVER,
    ):
        # receive file
        data = request.get_data().decode('utf-8')
        data = json.loads(data)
        image_b64 = data.get("img")
        if image_b64 is None or len(image_b64) < 1:
            return jsonify({"msg": "need img in request body"}), 400

        try:
            image_decode = base64.b64decode(image_b64)
            nparr = np.fromstring(image_decode, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            result = check(image)
        except Exception as e:
            return jsonify({"msg": "exception:" + str(traceback.format_exc())}), 500

        if type(result) == dict and result.get("msg") is not None:
            return jsonify(result), 400

        return result, 200


if __name__ == '__main__':
    app.run(host="0.0.0.0", port=17001, debug=True)
