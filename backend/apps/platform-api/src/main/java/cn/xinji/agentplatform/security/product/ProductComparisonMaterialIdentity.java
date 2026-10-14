package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;

/** Reconstructed originals have value identity; each read must retain every material and authority field. */
final class ProductComparisonMaterialIdentity {
    private ProductComparisonMaterialIdentity() { }

    static boolean same(ProductComparisonPreparationLoader.Loaded a, ProductComparisonPreparationLoader.Loaded b) {
        if (a == b) return true;
        if (a == null || b == null || !a.command().equals(b.command()) || !a.task().equals(b.task())
                || !a.documents().equals(b.documents()) || !a.snapshotJson().equals(b.snapshotJson())
                || !a.prepareSha().equals(b.prepareSha()) || !a.materialSha().equals(b.materialSha())
                || !a.expiresAt().equals(b.expiresAt()) || !a.retention().equals(b.retention())
                || a.targetReady() != b.targetReady() || !sameOriginal(a.target(), b.target())
                || !sameDocument(a.targetPatent(), b.targetPatent()) || a.candidates().size() != b.candidates().size()) return false;
        for (int i = 0; i < a.candidates().size(); i++) {
            if (!sameDocument(a.candidates().get(i), b.candidates().get(i))) return false;
        }
        return true;
    }

    private static boolean sameDocument(ProductComparisonDocuments.Loaded a, ProductComparisonDocuments.Loaded b) {
        if (a == b) return true;
        return a != null && b != null && a.reference().equals(b.reference()) && a.material().equals(b.material())
                && a.authority().equals(b.authority()) && sameOriginal(a.original(), b.original());
    }

    private static boolean sameOriginal(OriginalClaimText a, OriginalClaimText b) {
        if (a == b) return true;
        return a != null && b != null && a.text().equals(b.text())
                && a.evidence(0, a.text().length()).equals(b.evidence(0, b.text().length()));
    }
}
