package lld.snakeladder.entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Board {
    private final int size;
    private final Map<Integer, Integer> snakeAndLadder;
    public Board(int size, List<BoardEntity> entityList){
        this.size = size;
        this.snakeAndLadder = new HashMap<>();
        for (BoardEntity entity: entityList){
            snakeAndLadder.put(entity.getStart(), entity.getEnd());
        }
    }

    public int getSize(){
        return size;
    }

    public int getFinalPosition(int position){
        return snakeAndLadder.getOrDefault(position, position);
    }



}
