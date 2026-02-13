package lld.trainbooking;

/**
 * Represents a passenger in the booking
 */
public class Passenger {
    private final String name;
    private final int age;
    private final Gender gender;
    private final String identityProof;

    public Passenger(String name, int age, Gender gender, String identityProof) {
        this.name = name;
        this.age = age;
        this.gender = gender;
        this.identityProof = identityProof;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public Gender getGender() {
        return gender;
    }

    public String getIdentityProof() {
        return identityProof;
    }

    @Override
    public String toString() {
        return name + " (" + age + "/" + gender + ")";
    }

    public enum Gender {
        MALE, FEMALE, OTHER
    }
}
