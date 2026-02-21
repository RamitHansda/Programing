package lld.parking_lot;


public abstract class ParkingSpot {
    private int id;
    private boolean isFree;
    private Vehicle vehicle;

    public abstract boolean assignVehicle(Vehicle vehicle);
    public boolean removeVehicle(){
        return true;
    }
}

class Motorcycle extends ParkingSpot {
    public boolean assignVehicle(Vehicle vehicle) {
        // definition
        return false;
    }
}
