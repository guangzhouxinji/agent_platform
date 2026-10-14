package cn.xinji.agentplatform.consultation.trademarklibrary.product.application;

import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import java.util.function.Function;

public interface ProductTrademarkReadBoundaryPort {
    /** Authenticates and locks current facts before the callback, and revalidates before releasing its output. */
    <T> T read(Request request, Function<AuthorizedScope, T> callback);
    record Request(String credential, String taskId, Operation operation, RecordRef ref, String requestId) {
        public Request {
            require(taskId != null && !taskId.isBlank() && requestId != null && !requestId.isBlank()
                    && operation != null, Code.INVALID_REQUEST);
            require(operation == Operation.QUERY ? ref == null : ref != null, Code.INVALID_REQUEST);
        }
        @Override public String toString() { return "TrademarkReadRequest[operation=" + operation + "]"; }
        private static void require(boolean valid, Code code) {
            cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.require(valid, code);
        }
    }
}
