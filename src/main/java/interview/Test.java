package interview;

import java.util.ArrayList;
import java.util.List;

public class Test {
/*
* Given a positive integer, return its corresponding column title as appears in an Excel sheet.
For Example:
    1 -> A
    2 -> B
    3 -> C
    ...
    26 -> Z
    27 -> AA
    28 -> AB
    ...
Input: 28 | Output: "AB"
Input: 701 | Output: "ZY"
Input: 12345678 | Output: "ZZJUT"
*
*
* 28/26 = 1 and rem= 28%26 = 2

* 701/26=26, 676
*
* rem=25;
* num = 26
*
*
Helper for code:
["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"]
*
* */
    public static void main(String[] args) {
        List<String> mapOfChar = new ArrayList<>(List.of( "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"));
        int n =12345678;
        System.out.println(convertAZ(n, mapOfChar));
    }

    private static String convertAZ(int num, List<String> mapOfChar){
        StringBuilder str= new StringBuilder();


        while(num>0){
            num--;
            int rem=num%26;
            str.append((char)(rem + 'A'));
            num= num/26;
        }
        return str.reverse().toString();

    }


}
