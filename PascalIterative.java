package com.PascalTriangle;

public class PascalIterative {
    public static void main(String[] args) {
        int n = 5;
        for (int i = 0; i < n; i++) {

            for (int spaces = 1; spaces < n - i; spaces++) {
                System.out.print(" "); // it will give 5 spaces in one line itself that's why we should not write 'ln'
            }

            int number = 1;
            for (int j = 0; j <= i; j++) {
                System.out.print(number + " ");
                number = number * (i - j) / (j + 1);
            }
            System.out.println();
        }
    }
}
