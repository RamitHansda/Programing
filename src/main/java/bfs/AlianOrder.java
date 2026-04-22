import java.util.*;

class Solution {

    public String alienOrder(String[] words) {
        // Step 1: Build graph
        Map<Character, Set<Character>> graph = new HashMap<>();
        Map<Character, Integer> indegree = new HashMap<>();

        initializeGraph(words, graph, indegree);

        if (!buildGraph(words, graph, indegree)) {
            return ""; // invalid case (prefix issue or cycle)
        }

        // Step 2: Topological Sort (Kahn’s Algorithm)
        return topologicalSort(graph, indegree);
    }

    /**
     * Initialize graph nodes and indegree for all unique characters
     */
    private void initializeGraph(String[] words,
                                 Map<Character, Set<Character>> graph,
                                 Map<Character, Integer> indegree) {

        for (String word : words) {
            for (char c : word.toCharArray()) {
                graph.putIfAbsent(c, new HashSet<>());
                indegree.putIfAbsent(c, 0);
            }
        }
    }

    /**
     * Build graph based on adjacent word comparisons
     * Returns false if invalid ordering is detected
     */
    private boolean buildGraph(String[] words,
                               Map<Character, Set<Character>> graph,
                               Map<Character, Integer> indegree) {

        for (int i = 0; i < words.length - 1; i++) {
            String w1 = words[i];
            String w2 = words[i + 1];

            // ❌ Invalid case: prefix issue
            if (w1.length() > w2.length() && w1.startsWith(w2)) {
                return false;
            }

            int minLen = Math.min(w1.length(), w2.length());

            for (int j = 0; j < minLen; j++) {
                char c1 = w1.charAt(j);
                char c2 = w2.charAt(j);

                if (c1 != c2) {
                    // Avoid duplicate edges
                    if (!graph.get(c1).contains(c2)) {
                        graph.get(c1).add(c2);
                        indegree.put(c2, indegree.get(c2) + 1);
                    }
                    break; // only first difference matters
                }
            }
        }
        return true;
    }

    /**
     * Perform BFS-based Topological Sort (Kahn’s Algorithm)
     */
    private String topologicalSort(Map<Character, Set<Character>> graph,
                                   Map<Character, Integer> indegree) {

        Queue<Character> queue = new LinkedList<>();

        // Add all nodes with indegree 0
        for (char c : indegree.keySet()) {
            if (indegree.get(c) == 0) {
                queue.offer(c);
            }
        }

        StringBuilder result = new StringBuilder();

        while (!queue.isEmpty()) {
            char current = queue.poll();
            result.append(current);

            for (char neighbor : graph.get(current)) {
                indegree.put(neighbor, indegree.get(neighbor) - 1);

                if (indegree.get(neighbor) == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        // If result doesn't include all characters → cycle exists
        return result.length() == indegree.size() ? result.toString() : "";
    }

    public static void main(String[] args) {
        Solution sol = new Solution();

        // Test 1: standard case
        String[] words1 = {"wrt", "wrf", "er", "ett", "rftt"};
        System.out.println("Test 1: " + sol.alienOrder(words1)); // expected: "wertf"

        // Test 2: invalid (longer word is prefix of shorter)
        String[] words2 = {"z", "x"};
        System.out.println("Test 2: " + sol.alienOrder(words2)); // expected: "zx"

        // Test 3: invalid prefix ordering
        String[] words3 = {"z", "x", "z"};
        System.out.println("Test 3: " + sol.alienOrder(words3)); // expected: "" (cycle)

        // Test 4: single word
        String[] words4 = {"abc"};
        System.out.println("Test 4: " + sol.alienOrder(words4)); // expected: any permutation of a,b,c
    }
}