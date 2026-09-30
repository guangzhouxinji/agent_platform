package cn.xinji.agentplatform.security.product;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;

final class ProductVectorCodec {
    private ProductVectorCodec() { }

    static byte[] encode(float[] vector, int dimension) {
        if (vector == null || vector.length != dimension)
            throw new IllegalArgumentException("Exact vector dimension required");
        ByteBuffer buffer = ByteBuffer.allocate(dimension * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Finite vector required");
            buffer.putFloat(value);
            norm += value * value;
        }
        if (Math.abs(Math.sqrt(norm) - 1.0) > 0.0001)
            throw new IllegalArgumentException("L2-normalized vector required");
        return buffer.array();
    }

    static float[] decode(byte[] bytes, int dimension) {
        if (bytes == null || bytes.length != dimension * Float.BYTES)
            throw new IllegalStateException("Persisted vector dimension changed");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[dimension];
        for (int index = 0; index < dimension; index++) result[index] = buffer.getFloat();
        return result;
    }

    static String sha(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
