package org.karn.polymersplitter.polymer;

import net.minecraft.resources.ResourceLocation;
import org.karn.polymersplitter.common.pack.SplitPack;

final class PackIdentifier {
    private PackIdentifier() {
    }

    static ResourceLocation create(SplitPack pack) {
        return ResourceLocation.fromNamespaceAndPath(
                SplitDelivery.HOST_NAMESPACE,
                SplitDelivery.HOST_PREFIX + pack.namespace() + "/" + pack.sha1()
        );
    }
}
