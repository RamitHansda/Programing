package arrays;

public class WaterTrappingWithArray {
    public static void main(String[] args) {
        int[] arr = { 0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1 };
        System.out.println("total water: "+totalWater(arr));
    }

    public static int totalWater(int[] building){
        int [] maxSoFarFromLeft = new int[building.length];
        int [] maxSoFarFromRight = new int[building.length];
        maxSoFarFromLeft[0] = building[0];
        maxSoFarFromRight[building.length-1] = building[building.length-1];
        for(int i=1;i<building.length;i++){
            maxSoFarFromLeft[i] = Math.max(maxSoFarFromLeft[i - 1], building[i]);
        }

        for(int i=building.length-2;i>=0;i--){
            maxSoFarFromRight[i] = Math.max(maxSoFarFromRight[i + 1], building[i]);
        }
        int totalWater=0;
        for(int i=0;i<building.length;i++){
            totalWater += Math.min(maxSoFarFromRight[i],maxSoFarFromLeft[i]) - building[i];
        }
        return totalWater;
    }
}
