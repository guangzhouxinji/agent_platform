package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Receipt;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded non-body rows. Callers retain their own exact transaction and identity contracts. */
final class ProductPromptBodyClearRows {
    final ProductPromptStore main;
    final ProductPromptAuthorityStore authority;
    final ProductPromptBodyClearCodec codec;
    final Clock clock;
    ProductPromptBodyClearRows(ProductPromptStore main,ProductPromptAuthorityStore authority,
            ProductPromptBodyClearCodec codec,Clock clock){this.main=main;this.authority=authority;this.codec=codec;this.clock=clock;}

    Receipt mainReceipt(String prompt,String revision){
        main.jdbc.requireTransaction();uuid(prompt);uuid(revision);
        String where=" WHERE prompt_id=? AND revision_id=?";
        guard(main.jdbc.db,"conp_prompt_cleanup_receipt",where,prompt,revision);
        var rows=main.jdbc.db.query("SELECT "+ProductPromptBodyClearCodec.COLUMNS+" FROM conp_prompt_cleanup_receipt"+where,
                (rs,n)->codec.readReceipt(rs),prompt,revision);
        if(rows.size()>1)throw unavailable();rows.forEach(value->current(value.receiptUntil()));return rows.isEmpty()?null:rows.getFirst();
    }
    List<Receipt> mainGeneration(String prompt,long generation){
        main.jdbc.requireTransaction();uuid(prompt);
        String where=" WHERE prompt_id=? AND generation=?";
        guard(main.jdbc.db,"conp_prompt_cleanup_receipt",where,prompt,generation);
        var values=main.jdbc.db.query("SELECT "+ProductPromptBodyClearCodec.COLUMNS+" FROM conp_prompt_cleanup_receipt"+where,
                (rs,n)->codec.readReceipt(rs),prompt,generation);
        values.forEach(value->current(value.receiptUntil()));return List.copyOf(values);
    }
    Request authorityRequest(String prompt,String revision,boolean locking){
        authority.jdbc.requireTransaction();uuid(prompt);uuid(revision);
        String where=" WHERE prompt_id=? AND revision_id=?";
        guard(authority.jdbc.db,"idp_prompt_cleanup_request",where+(locking?" FOR UPDATE":""),prompt,revision);
        var rows=authority.jdbc.db.query("SELECT "+ProductPromptBodyClearCodec.REQUEST_COLUMNS+" FROM idp_prompt_cleanup_request"+where,
                (rs,n)->codec.readRequest(rs),prompt,revision);
        if(rows.size()>1)throw unavailable();rows.forEach(value->current(value.receiptUntil()));return rows.isEmpty()?null:rows.getFirst();
    }
    void guard(JdbcTemplate db,String table,String where,Object...args){
        var limits=db.query("SELECT receipt_until,LENGTH(manifest_json) FROM "+table+where,
                (rs,n)->new Limit(instant(rs,1),rs.getObject(2)==null?null:rs.getLong(2)),args);
        for(var limit:limits)if(limit.until()==null||!clock.instant().isBefore(limit.until())
                ||limit.length()==null||limit.length()<1||limit.length()>16384)throw unavailable();
    }
    void insert(JdbcTemplate db,String table,Map<String,Object> columns){
        var names=List.copyOf(columns.keySet());
        String markers=String.join(",",java.util.Collections.nCopies(names.size(),"?"));
        if(db.update("INSERT INTO "+table+"("+String.join(",",names)+") VALUES("+markers+")",
                names.stream().map(columns::get).map(value->value instanceof Instant instant?ts(instant):value).toArray())!=1)throw unavailable();
    }
    private record Limit(Instant until,Long length) { }
    private void current(Instant until){if(!clock.instant().isBefore(until))throw unavailable();}
}
