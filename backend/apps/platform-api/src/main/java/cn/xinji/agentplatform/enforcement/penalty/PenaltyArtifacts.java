package cn.xinji.agentplatform.enforcement.penalty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable product revision originals; a missing/tampered original fails closed, never falls back to DB alone. */
public final class PenaltyArtifacts {
    public record Saved(String key,String sha256) {}
    private final Path root;
    public PenaltyArtifacts(Path root) {
        if(root==null||!root.isAbsolute())throw new IllegalArgumentException("Absolute product file root required");
        this.root=root.normalize();
        try {Files.createDirectories(this.root);if(Files.isSymbolicLink(this.root))throw new IllegalStateException("Symlink file root refused");}
        catch(java.io.IOException e){throw new IllegalStateException("Penalty file root unavailable",e);}
    }
    public Saved write(byte[] content) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Artifact requires command transaction");
        String key=UUID.randomUUID()+".json";Path target=path(key);
        try {Files.write(target,content,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
        catch(java.io.IOException e){throw new IllegalStateException("Penalty original write failed",e);}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED)try {Files.deleteIfExists(target);}
                catch(java.io.IOException e){throw new IllegalStateException("Rolled back original cleanup failed",e);}
            }
        });
        return new Saved(key,sha(content));
    }
    public byte[] read(String key,String sha,byte[] databaseContent) {
        try {
            Path target=path(key);
            if(Files.isSymbolicLink(target))throw new IllegalStateException("Symlink original refused");
            byte[] content=Files.readAllBytes(target);
            if(!sha(content).equals(sha)||!Arrays.equals(content,databaseContent))throw new IllegalStateException("Penalty original SHA mismatch");
            return content;
        }catch(java.io.IOException e){throw new IllegalStateException("Penalty original unavailable",e);}
    }
    private Path path(String key) {
        if(key==null||!key.matches("[a-f0-9-]{36}[.]json"))throw new IllegalArgumentException("Opaque original key required");
        Path target=root.resolve(key).normalize();if(!target.getParent().equals(root))throw new SecurityException("Original outside root");return target;
    }
    public static String sha(byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
