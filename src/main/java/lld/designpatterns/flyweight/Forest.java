package lld.designpatterns.flyweight;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Forest: holds many Tree instances (extrinsic state only per tree); TreeType is shared (flyweight).
 */
public final class Forest {

    private final TreeFactory factory;
    private final List<Tree> trees = new ArrayList<>();

    public Forest(TreeFactory factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    public void plantTree(String species, String textureId, String meshId, double x, double y, double scale) {
        TreeType type = factory.getTreeType(species, textureId, meshId);
        trees.add(new Tree(type, x, y, scale));
    }

    public void renderAll() {
        for (Tree tree : trees) {
            tree.render();
        }
    }

    public int getTreeCount() {
        return trees.size();
    }
}
