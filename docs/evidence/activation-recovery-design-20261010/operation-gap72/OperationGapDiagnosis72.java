import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** Existing allowed phases confirmed; original defect hypothesis was disproved, not a new fix. */
public final class OperationGapDiagnosis72 {
  public static void main(String[] args)throws Exception {
    Path file=Path.of("docs/evidence/activation-recovery-design-20261010/entry-admission68/fixture-contract68.json");
    JsonNode contract=ConsultationActivationRecoveryEntryTest.JSON.readTree(Files.readAllBytes(file));
    for(var operation: new ConsultationActivationRecoveryJdbcStep.Operation[]{ConsultationActivationRecoveryJdbcStep.Operation.V_ADD_NEW,ConsultationActivationRecoveryJdbcStep.Operation.U_REVERSE_COMPLETE}) {
      var f=new ConsultationActivationRecoveryEntryTest.Fixture(contract);
      ((ObjectNode)f.contract.path("tables").path("ID2_SUBJECT_EVENT").path("checks")).remove("CK_ID2_SUBJECT_EVENT_OPERATION");
      new ConsultationActivationRecoveryFixtureGuard(file).assertCurrent(f.connection(),new ConsultationActivationRecoveryJdbcStep.Scope(ConsultationActivationRecoveryJdbcStep.TARGET_ID,ConsultationActivationRecoveryJdbcStep.Namespace.EMPTY,ConsultationActivationRecoveryEntryTest.RUN),operation);
      System.out.println("CONFIRMED existing "+operation+" missing operation CHECK phase admitted by current metadata guard; no source fix required");
    }
    System.out.println("EXISTING_PHASES_CONFIRMED=2; REJECTED_DEFECT_HYPOTHESIS=true; NEW_PRODUCT_FIXES=0; METADATA_COMPONENT_ONLY=true; DM8_CONNECTIONS=0");
  }
}
