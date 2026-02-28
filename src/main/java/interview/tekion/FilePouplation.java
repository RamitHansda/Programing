package interview.tekion;


import java.util.LinkedList;
import java.util.Queue;

public class FilePouplation {
    public static void main(String[] args) {
        int[][] matrix = new int[][]{{ 1 , 0 , 0},{0  , 1 , 0},{0 , 0 , 0}};
        System.out.println(numberOfSteps(matrix));
    }


//    [
//
//            1 , 0 , 0
//
//            0  , 1 , 0
//
//            0 , 0 , 0
//
//            ]
    static class Pair{
        int i;
        int j;
        Pair(int i, int j){
            this.i = i;
            this.j= j;
        }
    }
    private static int numberOfSteps(int[][] matrix){
        int steps=0;
        int row = matrix.length;
        int col = matrix[0].length;
        boolean[][] visited= new boolean[row][col];
        Queue<Pair> queue = new LinkedList<>();
        for(int i =0;i<row;i++){
            for(int j=0;j<col;j++){
                if(matrix[i][j]==1){
                    queue.offer(new Pair(i, j));
                    visited[i][j]=true;
                }
            }
        }

        int dirs[][] = new int[][]{{1,0},{-1,0}, {0,1},{0,-1}};

        while (!queue.isEmpty()){
            int size = queue.size();
            steps++;
            for (int i=0;i<size;i++){
                Pair element = queue.poll();
                int rowIndex = element.i;
                int colIndex  = element.j;

                for (int [] dir : dirs){
                    if(rowIndex+dir[0]>=0 && rowIndex+dir[0]<row && colIndex+dir[1]>=0 && colIndex+dir[1]<col && !visited[rowIndex+dir[0]][colIndex+dir[1]]){
                        queue.offer(new Pair(rowIndex+dir[0], colIndex+dir[1]));
                        visited[rowIndex+dir[0]][colIndex+dir[1]]= true;
                    }
                }

            }
        }
        return steps-1;
    }
}
