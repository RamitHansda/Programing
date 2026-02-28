package lld.designpatterns.prototype;

import java.util.Objects;

/**
 * Prototype: game entity with shared (intrinsic) and per-instance (extrinsic) state.
 * Cloning avoids re-reading from disk or recomputing heavy data.
 */
public interface GameEntity {

    /**
     * Returns a deep copy of this entity. Override-specific fields (e.g. position) can be
     * set on the clone after.
     */
    GameEntity clone();

    String getEntityType();
    Position getPosition();
    void setPosition(Position position);

    record Position(double x, double y, double z) {}
}
