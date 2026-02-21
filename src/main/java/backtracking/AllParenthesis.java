package backtracking;

import java.util.ArrayList;
import java.util.List;

public class AllParenthesis {

    public static void main(String[] args) {
        System.out.println(generateParenthesis(3));
    }
        public static List<String> generateParenthesis(int n) {
            List<String> result = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            generateAllParenthesis(n,sb,result);
            return result;
        }
        private static void generateAllParenthesis(int n,StringBuilder sb,List<String> result){
            if(valid(sb,n) && 2*n == sb.length()){
                result.add(new String(sb.toString()));
                return;
            }else if(valid(sb,n) == false)
            {
                return;
            }

            sb.append('(');
            generateAllParenthesis(n,sb,result);
            sb.deleteCharAt(sb.length()-1);
            sb.append(')');
            generateAllParenthesis(n,sb,result);
            sb.deleteCharAt(sb.length()-1);

        }

        private static Boolean valid(StringBuilder sb, int n){
            int res =0;
            for (int i = 0; i < sb.length(); i++) {
                if(sb.charAt(i) == '(' && ++res>n)
                    return false;
                else if(sb.charAt(i) == ')' && --res<0)
                    return false;
            }
            if(sb.length()==n*2 & res!=0)
                return false;
            else
                return true;
        }


}
