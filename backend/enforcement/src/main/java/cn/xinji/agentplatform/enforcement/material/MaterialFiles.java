package cn.xinji.agentplatform.enforcement.material;

import java.util.List;

public interface MaterialFiles {
    record Staged(String key, String sha256, int length) { }
    Staged stage(byte[] bytes);
    byte[] read(String key);
    List<String> stagedKeys();
    void discard(String key);
}
