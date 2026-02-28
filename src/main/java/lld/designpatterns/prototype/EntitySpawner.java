package lld.designpatterns.prototype;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Spawns many entities from a prototype (e.g. 100 zombies). Cheap: clone + override position.
 */
public final class EntitySpawner {

    private final GameEntity prototype;

    public EntitySpawner(GameEntity prototype) {
        this.prototype = Objects.requireNonNull(prototype);
    }

    /**
     * Spawns n copies of the prototype, assigning positions from the given list (or default if list is short).
     */
    public List<GameEntity> spawn(int n, List<GameEntity.Position> positions) {
        List<GameEntity> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            GameEntity clone = prototype.clone();
            GameEntity.Position pos = positions != null && i < positions.size()
                    ? positions.get(i)
                    : new GameEntity.Position(i * 1.0, 0.0, 0.0);
            clone.setPosition(pos);
            result.add(clone);
        }
        return result;
    }
}
