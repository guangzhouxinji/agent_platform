package cn.xinji.agentplatform.adjudication.casework;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Independent expiring keys for new human-derived payloads only; never model or old case keys. */
public final class MaterialDecisionVault {
    private record Key(String runId,String expiresAt,String value,String bindingSha) { }
    private final Path root; private final Clock clock; private final ObjectMapper json=new ObjectMapper();
    public MaterialDecisionVault(Path root,Clock clock) {
        try { Files.createDirectories(root); if(Files.isSymbolicLink(root))throw new IllegalStateException("MATERIAL_DECISION_KEY_PATH_UNSAFE");
            this.root=root.toRealPath();this.clock=clock;restrict(this.root,true);purgeExpired();
        }catch(Exception e){throw new IllegalStateException("MATERIAL_DECISION_KEY_STORE_UNAVAILABLE");}
    }
    public String seal(String runId,Instant expires,String binding,String payload) {
        if(!expires.isAfter(clock.instant()) || expires.isAfter(clock.instant().plus(Duration.ofDays(30))))throw new IllegalArgumentException("MATERIAL_DECISION_RETENTION_INVALID");
        Path path=file(runId);Path pending=root.resolve(runId+".pending");boolean ownsPending=false,ownsKey=false;
        try {
            byte[] key=new byte[32],nonce=new byte[12];var rng=new SecureRandom();rng.nextBytes(key);rng.nextBytes(nonce);
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException();
            Files.createFile(pending);ownsPending=true;restrict(pending,false);
            Files.writeString(pending,json.writeValueAsString(new Key(runId,expires.toString(),Base64.getEncoder().encodeToString(key),cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256(binding))),StandardCharsets.UTF_8,StandardOpenOption.WRITE);Files.move(pending,path);ownsPending=false;ownsKey=true;
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(runId,expires,binding));byte[] encrypted=cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));Arrays.fill(key,(byte)0);
            return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(encrypted);
        }catch(Exception e){if(ownsKey){try{Files.delete(path);}catch(Exception cleanup){throw new IllegalStateException("MATERIAL_DECISION_KEY_CLEANUP_FAILED",cleanup);}}throw new IllegalStateException("MATERIAL_DECISION_PAYLOAD_ENCRYPTION_FAILED");}finally{if(ownsPending){try{Files.deleteIfExists(pending);}catch(Exception cleanup){throw new IllegalStateException("MATERIAL_DECISION_KEY_CLEANUP_FAILED",cleanup);}}}
    }
    public String open(String runId,Instant expires,String binding,String envelope) {
        if(!expires.isAfter(clock.instant()))throw new IllegalStateException("MATERIAL_DECISION_RECORD_EXPIRED");
        try {
            Path f=file(runId);if(Files.isSymbolicLink(f))throw new IllegalStateException();
            var k=json.readValue(Files.readString(f),Key.class);
            if(!runId.equals(k.runId()) || !expires.toString().equals(k.expiresAt()) || !cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256(binding).equals(k.bindingSha()))throw new IllegalStateException();
            String[] parts=envelope.split("\\.",-1);if(parts.length!=2)throw new IllegalStateException();
            byte[] key=Base64.getDecoder().decode(k.value());var c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            c.updateAAD(aad(runId,expires,binding));byte[] plain=c.doFinal(Base64.getDecoder().decode(parts[1]));Arrays.fill(key,(byte)0);
            return new String(plain,StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("MATERIAL_DECISION_PAYLOAD_INTEGRITY_OR_KEY_FAILED");}
    }
    public void discardUncommitted(String id) { try { Files.deleteIfExists(file(id)); } catch(Exception e) { throw new IllegalStateException("MATERIAL_DECISION_KEY_CLEANUP_FAILED"); } }
    public void purgeExpired() {
        try(var files=Files.list(root)) {
            for(var f:files.filter(x->x.getFileName().toString().matches("[a-f0-9-]{36}\\.(key|pending)")).toList()) {
                if(Files.isSymbolicLink(f))throw new IllegalStateException("MATERIAL_DECISION_KEY_PATH_UNSAFE");
                var k=json.readValue(Files.readString(f),Key.class);
                if(!file(k.runId()).equals(f) && !root.resolve(k.runId()+".pending").equals(f))throw new IllegalStateException("MATERIAL_DECISION_KEY_PATH_UNSAFE");
                if(!Instant.parse(k.expiresAt()).isAfter(clock.instant()))Files.delete(f);
            }
        }catch(Exception e){throw new IllegalStateException("MATERIAL_DECISION_KEY_PURGE_FAILED");}
    }
    private Path file(String id){if(id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("MATERIAL_DECISION_RUN_ID_INVALID");var f=root.resolve(id+".key").normalize();if(!f.getParent().equals(root))throw new IllegalArgumentException("MATERIAL_DECISION_KEY_PATH_UNSAFE");return f;}
    private static byte[] aad(String id,Instant expires,String binding){return (id+"|"+expires+"|"+binding).getBytes(StandardCharsets.UTF_8);}
    private static void restrict(Path p,boolean dir)throws Exception {
        var posix=Files.getFileAttributeView(p,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(dir?"rwx------":"rw-------"));return;}
        var acl=Files.getFileAttributeView(p,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IllegalStateException("MATERIAL_DECISION_KEY_PERMISSIONS_UNAVAILABLE");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
