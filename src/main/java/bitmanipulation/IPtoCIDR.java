package bitmanipulation;

import java.util.ArrayList;
import java.util.List;

public class IPtoCIDR {
    public static void main(String[] args) {
        System.out.println(ipToCIDR("255.0.0.7", 12));
        System.out.println(ipToCIDR("255.0.0.7", 15));
    }



    public static List<String> ipToCIDR(String ip, int n) {
        List<String> result = new ArrayList<>();
        long start = ipToLong(ip);

        while (n>0){
            long lowestBit = start & -start;
            long maxBlock = 1;
            while ((maxBlock << 1) <= n) {
                maxBlock <<= 1;
            }

            long block = Math.min(lowestBit, maxBlock);
            int prefix = 32 - (int)(Math.log(block)/ Math.log(2));
            result.add(longToIP(start)+"/"+prefix);
            start += block;
            n -= block;
        }
        return result;
    }

    private static long ipToLong(String ip){
        String[] parts= ip.split("\\.");
        long res = 0;
        for (String part: parts){
            res = (res * 256) + Integer.parseInt(part);
        }
        return res;
    }

    private static String longToIP(long x){
        return  ((x >> 24) & 255) + "."+
                ((x >> 16) & 255) + "."+
                ((x >> 8) & 255) + "."+
                (x & 255);
    }
}
