package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationStore;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class Dm8ConsultationStorePurposeTest {
    @Test
    void defaultsMissingPurposeToTechnicalReference() throws Exception {
        Method method = Dm8ConsultationStore.class.getDeclaredMethod("normalizedPurpose", String.class);
        method.setAccessible(true);

        assertEquals("TECHNICAL_REFERENCE", method.invoke(null, new Object[] { null }));
        assertEquals("TECHNICAL_REFERENCE", method.invoke(null, ""));
        assertEquals("TECHNICAL_REFERENCE", method.invoke(null, "   "));
        assertEquals("CUSTOM", method.invoke(null, "CUSTOM"));
    }
}
