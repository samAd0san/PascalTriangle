package com.PascalTriangle;

public class PascalIterative2 {
    public static int fact(int num) {
        if (num == 0)
            return 1;

        return num * fact(num - 1);
    }

    public static void main(String[] args) {
        int rows = 4;
        for (int i = 0; i < rows; i++) {

            for (int spaces = 1; spaces < rows - i; spaces++) {
                System.out.print(" ");
            }

            for (int j = 0; j <= i; j++) {
                int number = fact(i) / (fact(i-j) * fact(j));
                System.out.print(number + " ");
            }
            System.out.println();
        }
    }
}
