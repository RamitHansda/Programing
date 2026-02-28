package interview.databricks;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

public class IPAccessControl {

    /**
     * Determines if an IP is allowed based on the given rule list
     * @param ip The IP address to check
     * @param rules List of rules, each rule is a list with [action, ip/cidr] format
     * @return true if the IP is allowed, false if denied
     */
    public static boolean isIPAllowed(String ip, List<List<String>> rules) {
        try {
            // Convert IP string to bytes for easier comparison
            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            
            // Default to allowed if no rules match
            boolean allowed = true;
            boolean matchFound = false;
            
            // Check each rule
            for (List<String> rule : rules) {
                String action = rule.get(0);
                String ipPattern = rule.get(1);
                
                if (isIPInPattern(ipBytes, ipPattern)) {
                    // Rule matched
                    matchFound = true;
                    allowed = "ALLOWED".equalsIgnoreCase(action);
                    // Use the last matching rule
                }
            }
            
            return matchFound ? allowed : true; // Default to allowed if no match
            
        } catch (UnknownHostException e) {
            System.err.println("Invalid IP address: " + ip);
            return false;
        }
    }
    
    /**
     * Checks if an IP address matches a pattern (exact IP or CIDR range)
     * @param ipBytes The IP address as byte array
     * @param pattern The pattern (exact IP or CIDR notation)
     * @return true if the IP matches the pattern
     */
    private static boolean isIPInPattern(byte[] ipBytes, String pattern) throws UnknownHostException {
        // Check if it's a CIDR pattern
        if (pattern.contains("/")) {
            return isIPInCIDR(ipBytes, pattern);
        } else {
            // Exact IP match
            byte[] patternBytes = InetAddress.getByName(pattern).getAddress();
            return Arrays.equals(ipBytes, patternBytes);
        }
    }
    
    /**
     * Checks if an IP address is within a CIDR range
     * @param ipBytes The IP address as byte array
     * @param cidr The CIDR notation (e.g., "192.168.1.0/24")
     * @return true if the IP is in the CIDR range
     */
    private static boolean isIPInCIDR(byte[] ipBytes, String cidr) throws UnknownHostException {
        String[] parts = cidr.split("/");
        String ipAddress = parts[0];
        int prefix;
        
        if (parts.length < 2) {
            // If no prefix specified, assume exact match
            prefix = 32;
        } else {
            prefix = Integer.parseInt(parts[1]);
        }
        
        byte[] subnetBytes = InetAddress.getByName(ipAddress).getAddress();
        
        // Create subnet mask from the prefix
        byte[] mask = createMask(prefix);
        
        // Check if the IP is in the subnet
        // Note: We only need to check that (ip & mask) equals (subnet & mask)
        // This handles non-aligned subnet addresses correctly
        for (int i = 0; i < ipBytes.length; i++) {
            if ((ipBytes[i] & mask[i]) != (subnetBytes[i] & mask[i])) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Creates a subnet mask based on the prefix length
     * @param prefix The CIDR prefix (0-32)
     * @return A byte array representing the subnet mask
     */
    private static byte[] createMask(int prefix) {
        byte[] mask = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (prefix >= 8) {
                mask[i] = (byte) 0xFF; // 11111111
                prefix -= 8;
            } else if (prefix > 0) {
                mask[i] = (byte) (0xFF << (8 - prefix));
                prefix = 0;
            } else {
                mask[i] = 0;
            }
        }
        return mask;
    }
    
    /**
     * Utility method to print IP range for a CIDR
     */
    private static void printCIDRRange(String cidr) {
        try {
            String[] parts = cidr.split("/");
            String ipAddress = parts[0];
            int prefix = Integer.parseInt(parts[1]);
            
            byte[] ipBytes = InetAddress.getByName(ipAddress).getAddress();
            byte[] mask = createMask(prefix);
            
            // Calculate network address (ip & mask)
            byte[] networkBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                networkBytes[i] = (byte) (ipBytes[i] & mask[i]);
            }
            
            // Calculate broadcast address (network | ~mask)
            byte[] broadcastBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                broadcastBytes[i] = (byte) (networkBytes[i] | ~mask[i]);
            }
            
            String networkAddr = InetAddress.getByAddress(networkBytes).getHostAddress();
            String broadcastAddr = InetAddress.getByAddress(broadcastBytes).getHostAddress();
            
            System.out.println("CIDR " + cidr + " covers range: " + networkAddr + " to " + broadcastAddr);
        } catch (Exception e) {
            System.err.println("Error processing CIDR: " + cidr + " - " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        // Example rule list with both CIDR and exact IP patterns
        List<List<String>> rules = Arrays.asList(
            Arrays.asList("ALLOWED", "198.23.200.0/24"),
            Arrays.asList("DENIED", "198.34.200.10/31"),
            Arrays.asList("ALLOWED", "198.23.220.15"),     // Exact IP address
            Arrays.asList("ALLOWED", "198.23.100.23/24")   // Non-aligned CIDR
        );
        
        // Print the actual ranges for better understanding
        System.out.println("CIDR Ranges for reference:");
        for (List<String> rule : rules) {
            String pattern = rule.get(1);
            if (pattern.contains("/")) {
                printCIDRRange(pattern);
            }
        }
        System.out.println();
        
        // Test cases
        String[] testIPs = {
            "198.23.200.10",  // Should be allowed (matches first rule)
            "198.23.201.10",  // Should be allowed (doesn't match any rule)
            "198.34.200.10",  // Should be denied (matches second rule)
            "198.34.200.11",  // Should be denied (matches second rule - /31 includes .10 and .11)
            "198.23.220.15",  // Should be allowed (exact match with third rule)
            "198.23.100.1",   // Should be allowed (matches fourth rule's subnet)
            "198.23.100.23",  // Should be allowed (matches fourth rule's subnet)
            "198.23.100.255", // Should be allowed (matches fourth rule's subnet)
            "198.23.101.1"    // Should be allowed (no matching rule)
        };
        
        for (String ip : testIPs) {
            boolean allowed = isIPAllowed(ip, rules);
            System.out.println(ip + " is " + (allowed ? "ALLOWED" : "DENIED"));
        }
    }
}