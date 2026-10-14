package cn.xinji.agentplatform.security.product;

/** A fresh SAVE command no longer identifies the current immutable draft head. */
final class ProductPromptSaveBaseConflict extends IllegalStateException {
    ProductPromptSaveBaseConflict(){super("Prompt save base differs from the current draft head");}
}
