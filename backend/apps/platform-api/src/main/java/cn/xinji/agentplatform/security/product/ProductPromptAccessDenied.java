package cn.xinji.agentplatform.security.product;

/** Business denial only; storage, binding and transaction failures remain unavailable. */
final class ProductPromptAccessDenied extends IllegalStateException {
    ProductPromptAccessDenied() { super("Prompt workspace access denied"); }
}
