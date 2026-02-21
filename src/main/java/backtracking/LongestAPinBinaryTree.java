package backtracking;



public class LongestAPinBinaryTree {

    static class Node {
        int data;
        Node left, right;
        Node(int d) { data = d; }
    }

    static class Pair {
        int diff, len;
        Pair(int diff, int len) {
            this.diff = diff;
            this.len = len;
        }
    }

    static int ans = 1;

    public Pair[] longestAPPath(Node root) {
        if (root == null)
            return new Pair[]{new Pair(Integer.MAX_VALUE, 0), new Pair(Integer.MAX_VALUE, 0)};

        if (root.left == null && root.right == null)
            return new Pair[]{new Pair(Integer.MAX_VALUE, 1), new Pair(Integer.MAX_VALUE, 1)};

        Pair[] l = {new Pair(Integer.MAX_VALUE, 0), new Pair(Integer.MAX_VALUE, 0)};
        Pair[] r = {new Pair(Integer.MAX_VALUE, 0), new Pair(Integer.MAX_VALUE, 0)};

        int leftDiff = Integer.MAX_VALUE;
        int rightDiff = Integer.MAX_VALUE;

        if (root.left != null) {
            l = longestAPPath(root.left);
            leftDiff = root.data - root.left.data;
        }
        if (root.right != null) {
            r = longestAPPath(root.right);
            rightDiff = root.data - root.right.data;
        }

        int maxLen1 = 1, maxLen2 = 1;

        if (root.left != null) {
            for (Pair p : l) {
                if (p.diff == leftDiff)
                    maxLen1 = Math.max(maxLen1, p.len + 1);
            }
        }

        if (root.right != null) {
            for (Pair p : r) {
                if (p.diff == rightDiff)
                    maxLen2 = Math.max(maxLen2, p.len + 1);
            }
        }

        // If symmetric AP passes through root
        if (leftDiff == -rightDiff)
            ans = Math.max(ans, maxLen1 + maxLen2 + 1);
        else
            ans = Math.max(ans, Math.max(maxLen1 + 1, maxLen2 + 1));

        return new Pair[]{new Pair(leftDiff, maxLen1), new Pair(rightDiff, maxLen2)};
    }

    public static void main(String[] args) {
        Node root = new Node(1);
        root.left = new Node(8);
        root.right = new Node(6);
        root.left.left = new Node(6);
        root.left.right = new Node(10);
        root.right.left = new Node(3);
        root.right.right = new Node(9);
        root.left.left.right = new Node(4);
        root.left.right.right = new Node(12);
        root.right.right.right
                = new Node(12);
        root.left.left.right.right
                = new Node(2);
        root.right.right.right.left
                = new Node(15);
        root.right.right.right.right
                = new Node(11);

        LongestAPinBinaryTree tree = new LongestAPinBinaryTree();
        tree.longestAPPath(root);

        System.out.println("Longest AP path is " + ans);
    }
}

