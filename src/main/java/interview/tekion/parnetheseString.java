package interview.tekion;

import java.util.ArrayList;
import java.util.List;

public class parnetheseString {

    static List<String> result = new ArrayList<>();
    public static void main(String[] args) {
        int n = 4;
        StringBuilder sb = new StringBuilder();
        backTrack(n, 0, 0, result, sb);
        System.out.println(result);
    }

    static void backTrack(int n, int open, int close, List<String> result, StringBuilder sb) {
        // If we have used all n pairs of parentheses
        if (sb.length() == n * 2) {
            result.add(sb.toString());
            return;
        }

        // Add an opening parenthesis if we haven't used all n
        if (open < n) {
            sb.append('(');
            backTrack(n, open + 1, close, result, sb);
            sb.deleteCharAt(sb.length() - 1); // Backtrack
        }

        // Add a closing parenthesis if it's valid (more open than close so far)
        if (close < open) {
            sb.append(')');
            backTrack(n, open, close + 1, result, sb);
            sb.deleteCharAt(sb.length() - 1); // Backtrack
        }
    }
}
