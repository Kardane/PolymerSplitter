package org.karn.polymersplitter.polymer;

import org.karn.polymersplitter.common.hosting.HostingStatus;

final class HostingPolicy {
    private HostingPolicy() {
    }

    static HostingStatus evaluate(AutoHostSettings settings) {
        if (!settings.initialized()) {
            return new HostingStatus(
                    HostingStatus.Kind.UNKNOWN,
                    "<uninitialized>",
                    "Polymer AutoHost configuration is not initialized"
            );
        }

        String providerType = settings.providerType() == null
                ? "<unset>"
                : settings.providerType().trim();

        if (!settings.enabled()) {
            return new HostingStatus(
                    HostingStatus.Kind.DISABLED,
                    providerType,
                    "Polymer AutoHost is disabled"
            );
        }

        return switch (providerType) {
            case "polymer:automatic",
                 "polymer:auto",
                 "polymer:netty",
                 "polymer:same_port",
                 "polymer:http_server",
                 "polymer:standalone" -> new HostingStatus(
                    HostingStatus.Kind.LOCAL,
                    providerType,
                    "Polymer AutoHost can serve registered split files"
            );
            case "polymer:external" -> new HostingStatus(
                    HostingStatus.Kind.EXTERNAL,
                    providerType,
                    "Polymer AutoHost external provider only constructs URLs; PolymerSplitter does not upload split ZIPs"
            );
            case "polymer:empty" -> new HostingStatus(
                    HostingStatus.Kind.EMPTY,
                    providerType,
                    "Polymer AutoHost empty provider does not host resource packs"
            );
            default -> new HostingStatus(
                    HostingStatus.Kind.UNKNOWN,
                    providerType,
                    "AutoHost provider is not explicitly supported by PolymerSplitter"
            );
        };
    }
}
