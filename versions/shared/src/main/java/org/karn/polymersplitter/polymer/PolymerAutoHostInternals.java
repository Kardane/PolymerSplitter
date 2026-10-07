package org.karn.polymersplitter.polymer;

import eu.pb4.polymer.autohost.impl.AutoHost;

final class PolymerAutoHostInternals {
    private PolymerAutoHostInternals() {
    }

    static AutoHostSettings settings() {
        if (AutoHost.config == null) {
            return new AutoHostSettings(false, false, null);
        }

        return new AutoHostSettings(
                true,
                AutoHost.config.enabled,
                AutoHost.config.type
        );
    }

    static void clearHostedRegistrations() {
        AutoHost.FILES.keySet().removeIf(
                path -> path.startsWith(
                        SplitDelivery.HOST_NAMESPACE + "/" + SplitDelivery.HOST_PREFIX
                )
        );
    }
}
