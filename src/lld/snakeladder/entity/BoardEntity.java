package lld.snakeladder.entity;

public abstract class BoardEntity {
    private final int start;
    private final int end;
    public BoardEntity(int start, int end){
        this.start = start;
        this.end = end;
    }

    public int getEnd() {
        return end;
    }

    public int getStart() {
        return start;
    }
}
