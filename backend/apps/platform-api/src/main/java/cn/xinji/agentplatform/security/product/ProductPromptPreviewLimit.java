package cn.xinji.agentplatform.security.product;

/** Fixed resource budget failure, with no template or supplied value in the message. */
final class ProductPromptPreviewLimit extends RuntimeException {
    ProductPromptPreviewLimit(){super("Prompt preview resource limit exceeded");}
}
