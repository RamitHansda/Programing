package dp;

public class RatInMaz {
    public static void main(String[] args) {
        int maze[][] = {{0, 0, 0, 0},
                {0, -1, 0, 0},
                {-1, 0, 0, 0},
                {0, 0, 0, 0}};
        System.out.println (numberOfWays(maze));
    }

    public static int numberOfWays(int[][] matrix){
        int R = matrix.length;
        int C = matrix[0].length;

        if (matrix[0][0]==-1)
            return 0;
        for(int i=0;i< R;i++){
            if(matrix[i][0] == 0)
                matrix[i][0] = 1;
            else break;
        }
        for(int i=1;i< C;i++){
            if(matrix[0][i] == 0)
                matrix[0][i] = 1;
            else break;
        }
        for(int i=1;i< R;i++)
            for(int j=1;j< C;j++){
                if(matrix[i][j] == -1)
                    continue;
                if(matrix[i-1][j] > 0)
                    matrix[i][j] += matrix[i-1][j];
                if(matrix[i][j-1] > 0)
                    matrix[i][j] += matrix[i][j-1];
            }


        return (matrix[R - 1][C - 1] > 0) ?
                matrix[R - 1][C - 1] : 0;
    }
}
