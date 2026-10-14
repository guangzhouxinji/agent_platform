package cn.xinji.agentplatform.security.product;

/** An authenticated target already has another key, or its READY control lost the CAS. */
final class ProductPromptBodyClearConflict extends IllegalStateException {
    ProductPromptBodyClearConflict(){super("Prompt body clear conflict");}
}
