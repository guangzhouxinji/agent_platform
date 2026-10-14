package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.ProductTrademarkLibrarySourcePort;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.ProductTrademarkLibraryService;
import java.time.Clock;
import java.util.*;

/** SQL-backed product reads can only execute inside the owning authenticated transaction. */
final class ProductDm8TrademarkLibrarySource implements ProductTrademarkLibrarySourcePort {
    private final ProductTrademarkReadTransaction boundary;
    private final ProductTrademarkProjectionQuery query;
    private final Clock clock;
    ProductDm8TrademarkLibrarySource(ProductTrademarkReadTransaction boundary,ProductTrademarkProjectionQuery query,Clock clock) {
        this.boundary=boundary;this.query=query;this.clock=clock;
    }
    @Override public Page query(AuthorizedScope scope,Query request) {
        var context=boundary.context(scope);Set<Field> queryFields=queryFields(request);
        context.sources.keySet().forEach(id->context.fields(id,queryFields,"QUERY"));
        var result=query.query(context.selections(),request);
        result.items().forEach(item->context.fields(item.ref().sourceId(),displayFields(item),"DISPLAY"));
        var provenance=context.sources.keySet().stream().sorted().map(id->provenance(context,id,request.refresh())).toList();
        return new Page(result.items(),new Pagination(request.page(),result.total(),result.hasNext(),null),ProductTrademarkLibraryService.SORT,provenance);
    }
    @Override public Detail detail(AuthorizedScope scope,RecordRef ref,boolean refresh) {
        var context=boundary.context(scope);require(context.sources.containsKey(ref.sourceId()),Code.ACCESS_DENIED);
        var item=query.detail(context.selections(),ref);context.fields(ref.sourceId(),displayFields(item),"DISPLAY");
        var capabilities=new EnumMap<HistoryType,Capability>(HistoryType.class);
        for(HistoryType type:HistoryType.values())capabilities.put(type,Capability.UNKNOWN);
        return new Detail(item,capabilities,provenance(context,ref.sourceId(),refresh));
    }
    @Override public History history(AuthorizedScope scope,RecordRef ref,HistoryType type,PageRequest page,boolean refresh) { boundary.context(scope);throw new Failure(Code.CAPABILITY_UNAVAILABLE); }
    @Override public Event event(AuthorizedScope scope,RecordRef ref,HistoryType type,String id,String version,boolean refresh) { boundary.context(scope);throw new Failure(Code.CAPABILITY_UNAVAILABLE); }
    @Override public Image image(AuthorizedScope scope,RecordRef ref,boolean refresh) { boundary.context(scope);throw new Failure(Code.CAPABILITY_UNAVAILABLE); }
    static Set<Field> queryFields(Query query) {
        var fields=EnumSet.of(Field.DATES);
        if(!blank(query.registrationNumber()))fields.add(Field.NUMBER);
        if(!blank(query.name()))fields.add(Field.NAME);
        if(!blank(query.applicant()))fields.add(Field.APPLICANTS);
        if(!query.categories().isEmpty())fields.add(Field.CLASSES);
        if(!query.statuses().isEmpty())fields.add(Field.STATUS);
        return Set.copyOf(fields);
    }
    private Provenance provenance(ProductTrademarkReadTransaction.Context context,String id,boolean refresh) {
        context.bound();var loaded=context.sources.get(id);var verified=loaded.capture().verified();
        // Re-reading the local capture is not an upstream refresh or a new collection time.
        return new Provenance(new SourceVersion(id,verified.registeredSourceVersion()),verified.nature().code(),verified.manifestRef(),
                "CURRENT_AUTHORIZED_CAPTURE",null,clock.instant(),refresh?"BYPASSED":"MISS",null);
    }
    static Set<Field> displayFields(Observation item) {
        var fields=EnumSet.noneOf(Field.class);
        if(item.registrationNumber()!=null)fields.add(Field.NUMBER);if(item.name()!=null)fields.add(Field.NAME);
        if(!item.applicants().isEmpty())fields.add(Field.APPLICANTS);if(item.address()!=null)fields.add(Field.ADDRESS);
        if(!item.classes().isEmpty())fields.add(Field.CLASSES);if(item.registered()!=null||item.validUntil()!=null)fields.add(Field.DATES);
        if(item.sourceStatus()!=null||item.displayStatus()!=null)fields.add(Field.STATUS);
        if(item.sourceRef()!=null)fields.add(Field.SOURCE_REFERENCE);if(item.imageRef()!=null)fields.add(Field.IMAGE);
        return Set.copyOf(fields);
    }
    private static boolean blank(String text) { return text==null||text.isBlank(); }
}
