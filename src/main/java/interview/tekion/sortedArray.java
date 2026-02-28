package interview.tekion;


class sortedArray {
    public double findMedianSortedArrays(int[] nums1, int[] nums2) {
        int m = nums1.length,n =nums2.length;
        if(m>n){
            int[] temp = nums1;
            nums1= nums2;
            nums2 =temp;
        }
        int x = nums1.length;
        int y = nums2.length;
        int start = 0;
        int end = x;
        while(start<=end){
            int partionX = (start + end)/2;
            int partionY = (x+y+1)/2 - partionX;
            int xLeftMax = (partionX == 0)? Integer.MIN_VALUE : nums1[partionX-1];
            int yLeftMax = (partionY == 0)? Integer.MIN_VALUE : nums2[partionY-1];
            int xRightMin = (partionX == x)? Integer.MAX_VALUE : nums1[partionX];
            int yRightMin = (partionY == y)? Integer.MAX_VALUE : nums2[partionY];

            if(xLeftMax<=yRightMin && yLeftMax<=xRightMin){
                if((x+y)%2==0)
                    return (double) (Math.max(xLeftMax,yLeftMax) + Math.min(xRightMin,yRightMin))/2;
                else
                    return Math.max(xLeftMax,yLeftMax);
            } else if (xLeftMax>yRightMin)
                end = partionX-1;
            else
                start = partionX +1;
        }
        return 0;
    }
}
