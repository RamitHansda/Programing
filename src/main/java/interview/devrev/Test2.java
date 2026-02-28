package interview.devrev;

import java.util.Arrays;

public class Test2 {
    public static void main(String[] args) {
        for (int i=1;i<=10;i++){

         System.out.println(i+" " + calculateMinPerSum(i));
        }

        System.out.println(calculateMinPerSum(60));
        // 13 = 9 + 4= (2) , 1+1+1 = 3 = (3)
        /*
        *  1- 1
        * 2- 1+1
        * 3- 1+1+1
        * 4- 1
        * 5= (5 not persqr) Min((4-1, 1-1)=2, (3,3)+(2,2) = 5)
        * 6-3
        * dp[n+1]
        * dp[0= 0;
        * dp[1]= 1;
        *
        *
        * dp[5] = Min(Dp[4]+dp[1], dp[3]+dp[2]);
        * */



    }


    public static int calculateMinPerSum(int n){
        int [] dp = new int[n+1];
        Arrays.fill(dp, Integer.MAX_VALUE);
//        dp[0]= 0;
        for(int i=1;i<=n;i++){
            if(Math.floor(Math.sqrt(i)) == Math.ceil(Math.sqrt(i)))
                dp[i]= 1;
            else{
                int start = 1;
                int end = i-1;
                while(start<=end){
                    dp[i] = Math.min(dp[start] + dp[end], dp[i]);
                    start++;
                    end--;
                }
            }

        }

        return dp[n];

    }
}
