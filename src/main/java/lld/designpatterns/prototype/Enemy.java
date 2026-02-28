package lld.designpatterns.prototype;

import java.util.Objects;

/**
 * Enemy entity: heavy intrinsic state (sprite, AI config) is shared via prototype; position/health are extrinsic.
 */
public final class Enemy implements GameEntity {

    private final String entityType;
    private final byte[] spriteData;      // heavy, shared via clone
    private final String aiConfigId;      // shared
    private Position position;
    private int health;

    public Enemy(String entityType, byte[] spriteData, String aiConfigId, Position position, int health) {
        this.entityType = Objects.requireNonNull(entityType);
        this.spriteData = spriteData == null ? new byte[0] : spriteData.clone();
        this.aiConfigId = aiConfigId;
        this.position = Objects.requireNonNull(position);
        this.health = health;
    }

    private Enemy(Enemy source) {
        this.entityType = source.entityType;
        this.spriteData = source.spriteData; // shared reference OK if immutable or read-only
        this.aiConfigId = source.aiConfigId;
        this.position = source.position;
        this.health = source.health;
    }

    @Override
    public GameEntity clone() {
        return new Enemy(this);
    }

    @Override
    public String getEntityType() {
        return entityType;
    }

    @Override
    public Position getPosition() {
        return position;
    }

    @Override
    public void setPosition(Position position) {
        this.position = Objects.requireNonNull(position);
    }

    public int getHealth() {
        return health;
    }

    public void setHealth(int health) {
        this.health = health;
    }
}
