package interview;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

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
        System.out.println(covertAZ(n, mapOfChar));
    }

    private static String covertAZ(int num, List<String> mapOfChar){
        StringBuilder str= new StringBuilder();


        while(num>26){
            int rem=num%26;
          if(rem>0){
            str.append(mapOfChar.get(rem-1));
          } else{
              str.append(mapOfChar.get(25));
          }
           num = num-1/26 ;
        }
        return str.reverse().toString();

    }

    TreeSet<Character> charSet = new TreeSet<>();



}
