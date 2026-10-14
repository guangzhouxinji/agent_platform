package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current upstream coordinates are queried outside the restored business DB; no business implementation is copied. */
public final class DocumentRuleDependencyHeads {
    private final JdbcTemplate current;
    private final Path currentCatalog;
    private final ObjectMapper json;
    public DocumentRuleDependencyHeads(JdbcTemplate current,Path currentCatalog,ObjectMapper json){this.current=current;this.currentCatalog=currentCatalog;this.json=json;}
    public void template(String organization,String id,long revision){
        try{
            var rows=current.queryForList("SELECT revision,organization_id,state FROM enf9_document_template_revision WHERE template_id=? AND revision=(SELECT MAX(revision) FROM enf9_document_template_revision WHERE template_id=?)",id,id);
            if(rows.size()!=1)throw new IllegalStateException("Current template head unavailable");
            var row=rows.getFirst();if(!organization.equals(row.get("ORGANIZATION_ID")))throw new SecurityException("Current template organization mismatch");
            if(((Number)row.get("REVISION")).longValue()!=revision||!"PUBLISHED".equals(row.get("STATE")))throw new DependencyChanged("TEMPLATE_DRIFT","Current template has changed");
        }catch(org.springframework.dao.DataAccessException failure){throw new IllegalStateException("Current template head unavailable",failure);}
    }
    public void schema(String organization,Schema expected,Path fallback){
        Path file=currentCatalog==null?fallback:currentCatalog;
        try{
            var catalog=json.readValue(Files.readAllBytes(file),FileDocumentRuleInputs.Catalog.class);
            var exact=catalog.schemas().stream().filter(s->organization.equals(s.organization())&&expected.id().equals(s.schema().id())&&expected.version().equals(s.schema().version())).toList();
            if(exact.size()!=1||!expected.equals(exact.getFirst().schema()))throw new DependencyChanged("SCHEMA_DRIFT","Current schema has changed");
        }catch(DependencyChanged failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Current schema head unavailable",failure);}
    }
}
