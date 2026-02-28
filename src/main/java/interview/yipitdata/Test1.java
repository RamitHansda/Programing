package interview.yipitdata;


//
 /*
 * Given a binary tree, find the maximum path sum. The path is defined as:
 * starting from any node, along the parent-child connections,
 * which may or may not start from the root. Each node in the path can only be used once.
 *
 *         6 (6-5+17) =18
 *      -10 (-5)     5, (17)
 *     3  5    8  12
 *
 *
 * */
public class Test1 {
    static class Node{
        int val;
        Node left;
        Node right;

        Node(int val, Node left, Node right){
            this.val = val;
            this.left = left;
            this.right = right;
        }
    }

    static int maxSum = 0;
    static  int maxSumPath(Node root) {
        if (root == null)
            return 0;

        int leftMaxSum = maxSumPath(root.left);
        int rightMaxSum = maxSumPath(root.right);
        int sumWithRoot = Math.max(root.val + leftMaxSum, root.val + rightMaxSum);
        maxSum = Math.max(maxSum, root.val + leftMaxSum + rightMaxSum);
        return sumWithRoot;
    }

    public static void main(String[] args) {

    }
}
