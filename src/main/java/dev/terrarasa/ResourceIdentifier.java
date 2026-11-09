package dev.terrarasa;

import java.util.Objects;

/**
 * A loader- and version-neutral resource identifier (namespace and path).
 * Replaces direct reliance on Minecraft's version-split {@code Identifier} / {@code ResourceLocation}.
 */
public record ResourceIdentifier(String namespace, String path) implements Comparable<ResourceIdentifier> {
    public static final String DEFAULT_NAMESPACE = "minecraft";

    public ResourceIdentifier {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
    }

    public static ResourceIdentifier of(String namespace, String path) {
        return new ResourceIdentifier(namespace, path);
    }

    public static ResourceIdentifier fromNamespaceAndPath(String namespace, String path) {
        return new ResourceIdentifier(namespace, path);
    }

    public static ResourceIdentifier withDefaultNamespace(String path) {
        return new ResourceIdentifier(DEFAULT_NAMESPACE, path);
    }

    public static ResourceIdentifier parse(String value) {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        if (colon < 0) {
            return new ResourceIdentifier(DEFAULT_NAMESPACE, value);
        }
        return new ResourceIdentifier(value.substring(0, colon), value.substring(colon + 1));
    }

    public String asString() {
        return this.namespace + ":" + this.path;
    }

    @Override
    public String toString() {
        return asString();
    }

    @Override
    public int compareTo(ResourceIdentifier o) {
        int cmp = this.namespace.compareTo(o.namespace);
        return cmp != 0 ? cmp : this.path.compareTo(o.path);
    }
}
