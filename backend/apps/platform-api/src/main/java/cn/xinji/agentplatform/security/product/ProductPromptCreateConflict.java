package cn.xinji.agentplatform.security.product;

/** An existing original key belongs to a different CREATE command. */
final class ProductPromptCreateConflict extends IllegalStateException {
    ProductPromptCreateConflict(){super("Prompt original key belongs to a different command");}
}
