package monotinicstack;


import java.util.ArrayDeque;
import java.util.Deque;

class SmallestSubsequence {
    public String smallestSubsequence(String s) {
        int[] freq = new int[26];
        boolean[] used = new boolean[26];

        for (char c : s.toCharArray()) {
            freq[c - 'a']++;
        }

        Deque<Character> stack = new ArrayDeque<>();

        for (char c : s.toCharArray()) {
            int idx = c - 'a';
            freq[idx]--;

            if (used[idx]) continue;

            while (!stack.isEmpty()
                    && stack.peekLast() > c
                    && freq[stack.peekLast() - 'a'] > 0) {
                used[stack.pollLast() - 'a'] = false;
            }

            stack.addLast(c);
            used[idx] = true;
        }

        StringBuilder sb = new StringBuilder();
        for (char c : stack) sb.append(c);
        return sb.toString();
    }
}
