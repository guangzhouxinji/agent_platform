package cn.xinji.agentplatform.security.product;

/** A registration key or workspace cannot designate a second installation. */
final class ProductPromptRegisterConflict extends IllegalStateException {
    ProductPromptRegisterConflict(){super("Prompt workspace registration conflict");}
}
