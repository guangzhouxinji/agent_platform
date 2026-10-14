package cn.xinji.agentplatform.adjudication.casework;
import cn.xinji.agentplatform.security.product.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CaseIdentityAdministrationControllerTest {
    ProductMaintenanceProof proof;ProductSubjects subjects;ProductSigningKeys keys;CaseIdentityAdministrationController controller;MockHttpServletRequest request;
    @BeforeEach void setup(){proof=mock(ProductMaintenanceProof.class);subjects=mock(ProductSubjects.class);keys=mock(ProductSigningKeys.class);controller=new CaseIdentityAdministrationController(proof,subjects,keys,"project-adjudication");request=new MockHttpServletRequest();request.setSecure(true);}
    @Test void missingMaintenanceProofCannotRegister(){when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenThrow(new SecurityException());assertThrows(SecurityException.class,()->controller.register(new CaseIdentityAdministrationController.Register("operator"),request));verifyNoInteractions(subjects);}
    @Test void otherOrganizationCannotRegisterOrRotate(){when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("maintainer");when(proof.requireOrganization(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("other-org");assertThrows(SecurityException.class,()->controller.register(new CaseIdentityAdministrationController.Register("operator"),request));when(proof.require(request,ProductMaintenanceProof.Role.KEYS)).thenReturn("maintainer");when(proof.requireOrganization(request,ProductMaintenanceProof.Role.KEYS)).thenReturn("other-org");assertThrows(SecurityException.class,()->controller.rotate(request));verifyNoInteractions(subjects,keys);}
    @Test void registrationUsesCurrentProvedActor(){when(proof.require(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("proved#certificate");when(proof.requireOrganization(request,ProductMaintenanceProof.Role.SUBJECTS)).thenReturn("project-adjudication");controller.register(new CaseIdentityAdministrationController.Register("operator"),request);verify(subjects).register("operator","proved#certificate");verifyNoInteractions(keys);}
    @Test void activationCannotUseInsecureTransport(){request.setSecure(false);assertThrows(SecurityException.class,()->controller.activate("subject",new CaseIdentityAdministrationController.Activate("once","password"),request));verifyNoInteractions(subjects);}
    @Test void activationDelegatesOneTimeLifecycleWithoutGrantOrSessionWrites(){controller.activate("subject",new CaseIdentityAdministrationController.Activate("once","password"),request);verify(subjects).activate("subject","once","password");verifyNoInteractions(proof,keys);}
    @Test void keyRotationUsesOnlyCurrentKeysRole(){when(proof.require(request,ProductMaintenanceProof.Role.KEYS)).thenReturn("proved#certificate");when(proof.requireOrganization(request,ProductMaintenanceProof.Role.KEYS)).thenReturn("project-adjudication");controller.rotate(request);verify(keys).rotate("proved#certificate");verifyNoInteractions(subjects);}
}
