package cn.xinji.agentplatform.security.product;

/** An existing requester key is bound to a different command; not an unknown storage outcome. */
final class ProductHumanRequestConflict extends IllegalStateException {
    ProductHumanRequestConflict() { super("Human request key payload conflicts"); }
}
