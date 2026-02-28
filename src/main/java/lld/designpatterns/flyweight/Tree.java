package lld.designpatterns.flyweight;

import java.util.Objects;

/**
 * Tree instance: holds only extrinsic state (position, scale). Shares TreeType (flyweight).
 */
public final class Tree {

    private final TreeType type;
    private final double x;
    private final double y;
    private final double scale;

    public Tree(TreeType type, double x, double y, double scale) {
        this.type = Objects.requireNonNull(type);
        this.x = x;
        this.y = y;
        this.scale = scale;
    }

    public void render() {
        type.render(x, y, scale);
    }

    public TreeType getType() { return type; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getScale() { return scale; }
}
