package org.karn.polymersplitter.common.pack;

import java.util.regex.Pattern;

public final class ResourceNamespaces {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]+");

    private ResourceNamespaces() {
    }

    public static boolean isValid(String namespace) {
        return namespace != null && VALID.matcher(namespace).matches();
    }
}
