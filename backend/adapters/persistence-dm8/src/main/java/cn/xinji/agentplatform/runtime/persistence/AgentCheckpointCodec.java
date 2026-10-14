package cn.xinji.agentplatform.runtime.persistence;

import cn.xinji.agentplatform.runtime.AgentContract.Checkpoint;
import cn.xinji.agentplatform.runtime.AgentPorts.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Authenticated encrypted revisions; no default key, and ciphertext cannot be transplanted across runs. */
public final class AgentCheckpointCodec {
    public record Revision(Checkpoint checkpoint, AgentEvent event) { }
    public record Encoded(String ciphertext, String nonce, String sha256) { }
    private final ObjectMapper json;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public AgentCheckpointCodec(ObjectMapper json, String encodedKey) {
        this.json = json.copy();
        byte[] raw;
        try { raw = Base64.getDecoder().decode(encodedKey); }
        catch (RuntimeException error) { throw new IllegalArgumentException("AGENT_CHECKPOINT_KEY_REQUIRED"); }
        if (raw.length != 32) throw new IllegalArgumentException("AGENT_CHECKPOINT_KEY_REQUIRED");
        key = new SecretKeySpec(raw, "AES"); Arrays.fill(raw, (byte) 0);
    }
    public Encoded encode(Checkpoint c, AgentEvent event) {
        if (!c.runId().equals(event.runId()) || c.version() != event.version() || c.phase() != event.phase()
                || !Objects.equals(c.outcome()==null?null:c.outcome().reason(),event.stopReason())
                || (c.pending()!=null && !c.pending().id().equals(event.invocationId())))
            throw new IllegalArgumentException("CHECKPOINT_EVENT_MISMATCH");
        try {
            byte[] raw = json.writeValueAsBytes(new Revision(c, event));
            if (raw.length > 262144) throw new IllegalArgumentException("CHECKPOINT_SIZE_LIMIT");
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(aad(c));
            return new Encoded(Base64.getEncoder().encodeToString(cipher.doFinal(raw)),
                    Base64.getEncoder().encodeToString(nonce), sha(raw));
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("CHECKPOINT_ENCODING_FAILED"); }
    }
    public Revision decode(String runId, String agentId, String taskId, long version, Encoded encoded) {
        try {
            if (encoded.ciphertext().length() > 350000) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(encoded.nonce())));
            cipher.updateAAD(aad(runId, agentId, taskId, version));
            byte[] raw = cipher.doFinal(Base64.getDecoder().decode(encoded.ciphertext()));
            if (!sha(raw).equals(encoded.sha256())) throw new IllegalArgumentException();
            Revision revision = json.readValue(raw, Revision.class); var c = revision.checkpoint();
            if (!runId.equals(c.runId()) || !agentId.equals(c.definition().agentId()) || !taskId.equals(c.task().id())
                    || version != c.version() || !runId.equals(revision.event().runId())
                    || version != revision.event().version() || c.phase() != revision.event().phase()
                    || !Objects.equals(c.outcome()==null?null:c.outcome().reason(),revision.event().stopReason())) throw new IllegalArgumentException();
            return revision;
        } catch (Exception error) { throw new IllegalStateException("CHECKPOINT_INTEGRITY_FAILED"); }
    }
    private byte[] aad(Checkpoint c) { return aad(c.runId(), c.definition().agentId(), c.task().id(), c.version()); }
    private byte[] aad(String run, String agent, String task, long version) {
        try { return json.writeValueAsBytes(List.of("AGENT_CHECKPOINT/v1", run, agent, task, version)); }
        catch (Exception error) { throw new IllegalStateException("CHECKPOINT_ENCODING_FAILED"); }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
