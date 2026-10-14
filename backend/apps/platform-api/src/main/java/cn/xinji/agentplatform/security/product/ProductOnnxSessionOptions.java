package cn.xinji.agentplatform.security.product;

import ai.onnxruntime.OrtSession;

/** Final CPU execution profile for the small, sequential query/pair inference workload. */
final class ProductOnnxSessionOptions {
    static final String VERSION="ONNX_CPU_BOUNDED/v1";
    private ProductOnnxSessionOptions() { }
    static OrtSession.SessionOptions current() {
        var options=new OrtSession.SessionOptions();
        try {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.addConfigEntry("session.intra_op.allow_spinning","0");
            return options;
        } catch(Exception failure) {
            options.close();
            throw new IllegalStateException("Bounded ONNX execution profile unavailable",failure);
        }
    }
    /** Only for checksum-verified historical reconstruction of the originally deployed defaults. */
    static OrtSession.SessionOptions historicalAutomatic() { return new OrtSession.SessionOptions(); }
}
