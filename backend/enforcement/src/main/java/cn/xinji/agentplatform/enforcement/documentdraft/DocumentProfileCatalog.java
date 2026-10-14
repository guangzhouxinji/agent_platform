package cn.xinji.agentplatform.enforcement.documentdraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Profile imports retain all exact versions; callers cannot replace a historical version silently. */
public final class DocumentProfileCatalog {
    private final List<DocumentTemplateProfile> profiles;
    public DocumentProfileCatalog(Path file,ObjectMapper json) { this(read(file),json); }
    public static DocumentProfileCatalog builtin(ObjectMapper json) {
        try(var input=DocumentProfileCatalog.class.getResourceAsStream("/enforcement/document-template-baseline-v1/profiles.json")) {
            if(input==null)throw new IllegalStateException("Built-in profile resource absent");
            return new DocumentProfileCatalog(input.readAllBytes(),json);
        }catch(Exception failure){throw new IllegalStateException("Built-in profile unreadable",failure);}
    }
    private static byte[] read(Path file) {
        try{return Files.readAllBytes(file);}catch(Exception failure){throw new IllegalStateException("Profile file unreadable",failure);}
    }
    public DocumentProfileCatalog(byte[] bytes,ObjectMapper json) {
        try {
            var root=json.readTree(bytes);
            if(!"enf-document-template-profile-v1".equals(root.path("schemaVersion").asText()))throw new IllegalArgumentException("Profile schema version unsupported");
            var values=new ArrayList<DocumentTemplateProfile>();var coordinates=new HashSet<String>();
            for(var node:root.path("profiles")) {
                var profile=json.treeToValue(node,DocumentTemplateProfile.class);
                if(!coordinates.add(profile.id()+"@"+profile.revision()))throw new IllegalArgumentException("Duplicate profile version");
                values.add(profile);
            }
            if(values.isEmpty())throw new IllegalArgumentException("No profiles configured");
            profiles=List.copyOf(values);
        }catch(IllegalArgumentException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Profile catalog unreadable",failure);}
    }
    public List<DocumentTemplateProfile> all(){return profiles;}
    public DocumentTemplateProfile exact(String id,long revision) {
        return profiles.stream().filter(p->p.id().equals(id)&&p.revision()==revision).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Exact profile version unavailable"));
    }
}
