package cn.xinji.agentplatform.consultation.trademarklibrary.product.application;

import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;

/** Implementations filter and sort the complete authorized scope before paging, within one read snapshot. */
public interface ProductTrademarkLibrarySourcePort {
    Page query(AuthorizedScope scope, Query query);
    Detail detail(AuthorizedScope scope, RecordRef ref, boolean refresh);
    History history(AuthorizedScope scope, RecordRef ref, HistoryType type, PageRequest page, boolean refresh);
    Event event(AuthorizedScope scope, RecordRef ref, HistoryType type, String eventId, String eventVersion, boolean refresh);
    Image image(AuthorizedScope scope, RecordRef ref, boolean refresh);
}
