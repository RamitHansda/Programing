package lld.designpatterns.flyweight;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for tree types: ensures one flyweight per species (shared intrinsic state).
 */
public final class TreeFactory {

    private final Map<String, TreeType> types = new ConcurrentHashMap<>();

    public TreeType getTreeType(String species, String textureId, String meshId) {
        return types.computeIfAbsent(species, s -> new TreeType(species, textureId, meshId));
    }

    public TreeType getTreeType(String species) {
        TreeType t = types.get(species);
        if (t == null) {
            throw new IllegalArgumentException("Unknown species: " + species);
        }
        return t;
    }

    public int getTypeCount() {
        return types.size();
    }
}
