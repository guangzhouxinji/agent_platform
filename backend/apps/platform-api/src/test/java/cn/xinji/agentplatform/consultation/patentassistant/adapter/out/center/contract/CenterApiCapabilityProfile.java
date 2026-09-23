package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.center.contract;

import java.util.List;

record CenterApiCapabilityProfile(
        ContractKind contractKind,
        Capability endpointAndMethod,
        Capability authentication,
        Capability requestMapping,
        Capability responseMapping,
        Capability paginationAndLimits,
        Capability stableIdentityAndVersion,
        Capability errorAndRateLimit,
        Capability dataUseAuthorization) {

    static CenterApiCapabilityProfile unknown() {
        return new CenterApiCapabilityProfile(ContractKind.UNKNOWN, Capability.UNKNOWN, Capability.UNKNOWN,
                Capability.UNKNOWN, Capability.UNKNOWN, Capability.UNKNOWN, Capability.UNKNOWN,
                Capability.UNKNOWN, Capability.UNKNOWN);
    }

    static CenterApiCapabilityProfile exampleOnly() {
        return new CenterApiCapabilityProfile(ContractKind.EXAMPLE_NOT_CENTER_CONTRACT,
                Capability.CONFIRMED, Capability.CONFIRMED, Capability.CONFIRMED, Capability.CONFIRMED,
                Capability.CONFIRMED, Capability.CONFIRMED, Capability.CONFIRMED, Capability.CONFIRMED);
    }

    boolean readyForRealAdapter() {
        return contractKind == ContractKind.REAL_CENTER_CONTRACT
                && capabilities().stream().allMatch(value -> value == Capability.CONFIRMED);
    }

    List<Capability> capabilities() {
        return List.of(endpointAndMethod, authentication, requestMapping, responseMapping, paginationAndLimits,
                stableIdentityAndVersion, errorAndRateLimit, dataUseAuthorization);
    }

    enum ContractKind { UNKNOWN, EXAMPLE_NOT_CENTER_CONTRACT, REAL_CENTER_CONTRACT }
    enum Capability { UNKNOWN, CONFIRMED }
}
