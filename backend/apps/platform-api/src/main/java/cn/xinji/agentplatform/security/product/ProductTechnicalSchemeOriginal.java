package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exact persisted user-message UTF8, explicitly distinct from patent-original JSON. */
final class ProductTechnicalSchemeOriginal implements OriginalClaimText {
    private final String text, ref, sha;
    private final byte[] bytes;
    private final int[] boundaries;
    ProductTechnicalSchemeOriginal(String taskId, String messageId, String text, String expectedSha, int maximumBytes) {
        if (taskId==null || messageId==null || !taskId.matches("[0-9a-fA-F-]{36}")
                || !messageId.matches("[0-9a-fA-F-]{36}") || text==null || text.isBlank() || maximumBytes<1)
            throw new IllegalArgumentException("TECHNICAL_ORIGINAL_INVALID");
        this.bytes=text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length>maximumBytes) throw new IllegalArgumentException("TECHNICAL_ORIGINAL_BUDGET_EXCEEDED");
        this.sha=ProductCapturedArchive.digest(bytes);
        if (!sha.equals(expectedSha)) throw new IllegalArgumentException("TECHNICAL_ORIGINAL_SHA_MISMATCH");
        this.text=text; this.ref="TASK_MESSAGE:"+taskId+":"+messageId;
        this.boundaries=new int[text.length()+1]; Arrays.fill(boundaries,-1);
        int offset=0;
        for (int cursor=0; cursor<text.length();) {
            boundaries[cursor]=offset; char first=text.charAt(cursor); int length=1;
            if (Character.isHighSurrogate(first)) {
                if (cursor+1>=text.length() || !Character.isLowSurrogate(text.charAt(cursor+1)))
                    throw new IllegalArgumentException("TECHNICAL_ORIGINAL_UNICODE_INVALID");
                length=2;
            } else if (Character.isLowSurrogate(first)) throw new IllegalArgumentException("TECHNICAL_ORIGINAL_UNICODE_INVALID");
            offset+=text.substring(cursor,cursor+length).getBytes(StandardCharsets.UTF_8).length; cursor+=length;
        }
        boundaries[text.length()]=offset;
    }
    @Override public String text() { return text; }
    @Override public Evidence evidence(int start, int end) {
        if (start<0 || end<=start || end>text.length() || boundaries[start]<0 || boundaries[end]<0)
            throw new IllegalArgumentException("TECHNICAL_ORIGINAL_SPAN_INVALID");
        byte[] span=Arrays.copyOfRange(bytes,boundaries[start],boundaries[end]);
        return new Evidence("USER_MESSAGE_SHA256:"+sha,ref,sha,"/raw_text",boundaries[start],boundaries[end],
                ProductCapturedArchive.digest(span),text.substring(start,end));
    }
    boolean matches(Evidence evidence) {
        if (evidence==null) return false;
        int start=-1,end=-1;
        for (int i=0;i<boundaries.length;i++) {
            if (boundaries[i]>=0 && boundaries[i]==evidence.byteStart()) start=i;
            if (boundaries[i]>=0 && boundaries[i]==evidence.byteEnd()) end=i;
        }
        return start>=0 && end>start && evidence(start,end).equals(evidence);
    }
}
