package interview.concentric.ai;

public class Interview2 {
    public static void main(String[] args) {
        int [] nums = new int[]{6,7,8,0,1,2,3,4,5};
//        System.out.println(findTarget(nums, 6));
//        System.out.println(findTarget(nums, 7));
        System.out.println(findMin(nums));
        for(int i=0;i<nums.length;i++){
            System.out.println(targetFind(nums, nums[i]));
        }
        System.out.println("test");

    }

    private static int targetFind(int[] nums, int target){
        int minIndex = findMin(nums);
        int leftTarget = findTarget(nums, 0, minIndex-1,target);
        if (leftTarget != -1)
            return leftTarget;
        return findTarget(nums, minIndex, nums.length-1,target);
    }

    public static int findTarget(int[] nums, int target){
        int left =0, right = nums.length-1;
        int mid;
        while(left<right) {
            mid = left + (right-left)/2;
            if(nums[mid] == target)
                return mid;
            if(nums[mid]<nums[right]){
                if(nums[mid]<=target && nums[right]>=target)
                    left = mid+1;
                else
                    right = mid;

            }else{
                if(nums[mid]>=target && nums[left]<=target)
                    right= mid;
                else
                    left = mid+1;
            }
        }
        return left;
    }

    public static int findMin(int[] nums){
        int left = 0, right = nums.length-1;
        while (left<right){
            int mid = left + (right-left)/2;
            if(nums[mid]<nums[right])
                right = mid-1;
            else left = mid+1;
        }
        return left;
    }

    private static int findTarget(int[] nums, int startIndex, int endIndex, int target){
        int start= startIndex;
        int end = endIndex;
        while (start<=end){
            int mid = start + (end-start)/2;
            if(nums[mid] == target)
                return mid;
            if(nums[mid]<target)
                start = mid+1;
            else
                end = mid-1;
        }
        return -1;
    }
}


/**
 *
 * There is an integer array nums sorted in ascending order (with distinct values).
 *
 * Prior to being passed to your function, nums is possibly left rotated at an unknown index k (1 <= k < nums.length) such that the resulting array is [nums[k], nums[k+1], ..., nums[n-1], nums[0], nums[1], ..., nums[k-1]] (0-indexed).
 * For example, [0,1,2,4,5,6,7] might be left rotated by 3 indices and become [6,7,8,0,1,2,3,4,5].
 *
 * [6 7 0]
 *
 * Given the array nums after the possible rotation and an integer target, return the index of target if it is in nums, or -1 if it is not in nums.
 */