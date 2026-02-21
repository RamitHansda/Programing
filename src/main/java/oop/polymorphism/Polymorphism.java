package oop.polymorphism;

class Sum {
    public int addition(int a, int b) {
        return a + b;
    }

    public int addition(int a, int b, int c) {
        return a + b + c;
    }
}


class ComplexNumber {
    float real;
    float imaginary;
    // Constructor
    public ComplexNumber(float real, float imaginary) {
        this.real = real;
        this.imaginary = imaginary;
    }

    @Override
    public String toString() {
        return "( {"+real+"} + {"+imaginary+"} i )";
    }

    public ComplexNumber add(ComplexNumber other) {
        return new ComplexNumber(this.real + other.real, this.imaginary + other.imaginary);
    }
}


public class Polymorphism {
    public static void main(String[] args) {
        Sum sum =  new Sum();
        System.out.println("testing static Polymorphism with method over loading example one  "+ sum.addition(12, 24));
        System.out.println("testing static Polymorphism with method over loading example two  "+ sum.addition(12, 24, 24));
        ComplexNumber c1 = new ComplexNumber(2.0F, 3.0F);
        ComplexNumber c2 = new ComplexNumber(1.5F, 4.5F);

        ComplexNumber result = c1.add(c2);
        System.out.println("Sum: " + result);  // Output: Sum: 3.5 + 7.5i
    }
}
