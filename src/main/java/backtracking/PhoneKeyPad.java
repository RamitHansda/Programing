package backtracking;

import java.util.ArrayList;
import java.util.List;

public class PhoneKeyPad {
    public static void main(String[] args) {
        getPermutation(3,2);
    }
    public static String getPermutation(int A, int B) {
        List<String> result = new ArrayList<>();
        StringBuilder sb =  new StringBuilder();
        for(int i=1;i<=A;i++){
            sb.append(i);
        }
        //result.add(new String(sb));
        generateAll(result,sb,0);
        System.out.println(result);
        return result.get(B-1);
    }

    public static void generateAll(List<String> result, StringBuilder sb, int start){
        if(start==sb.length()-1){
            result.add(new String(sb));
            return;
        }
        for(int i=start;i<sb.length();i++){
            swap(sb,i,start);
            generateAll(result, sb, start+1);
            swap(sb,i,start);
        }

    }

    private static void swap(StringBuilder sb, int i , int j){
        Character ch = sb.charAt(i);
        sb.setCharAt(i, sb.charAt(j));
        sb.setCharAt(j, ch);
        //return new StringBuilder(sb);
    }
}
