package com.noteweave.capability;

public interface CapabilityCatalogPort {

    CapabilityDescriptor requireCapability(String capabilityKey);

    record CapabilityDescriptor(
            String capabilityKey,
            String actionKey
    ) {
    }
}
