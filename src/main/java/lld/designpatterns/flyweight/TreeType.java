package lld.designpatterns.flyweight;

import java.util.Objects;

/**
 * Flyweight: shared intrinsic state for a tree species (texture, mesh). One per species.
 */
public final class TreeType {

    private final String species;
    private final String textureId;
    private final String meshId;

    public TreeType(String species, String textureId, String meshId) {
        this.species = Objects.requireNonNull(species);
        this.textureId = Objects.requireNonNull(textureId);
        this.meshId = Objects.requireNonNull(meshId);
    }

    public String getSpecies() { return species; }
    public String getTextureId() { return textureId; }
    public String getMeshId() { return meshId; }

    /**
     * Render at given extrinsic state (position, scale). Heavy data comes from this flyweight.
     */
    public void render(double x, double y, double scale) {
        // In production: bind texture/mesh, set transform (x, y, scale), draw
    }
}
