package org.karn.polymersplitter.polymer;

import net.minecraft.resources.Identifier;
import org.karn.polymersplitter.common.pack.SplitPack;

final class PackIdentifier {
    private PackIdentifier() {
    }

    static Identifier create(SplitPack pack) {
        return Identifier.fromNamespaceAndPath(
                SplitDelivery.HOST_NAMESPACE,
                SplitDelivery.HOST_PREFIX + pack.namespace() + "/" + pack.sha1()
        );
    }
}
