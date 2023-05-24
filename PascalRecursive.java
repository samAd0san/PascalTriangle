package com.PascalTriangle;

public class PascalRecursive {
    public static int getPascal(int row , int col) {
        if (col == 0 || col == row) {
            return 1;
        }else{
            int left = getPascal(row - 1 ,col - 1); // left element
            int right = getPascal(row - 1, col); // right element
            return left + right; // on adding the left and the right element we will get the next desired element
        }
    }

    public static void main(String[] args) {
        int noOfRows = 4;
        for (int i = 0; i < noOfRows; i++) {

            for (int spaces = 0; spaces < noOfRows - i; spaces++) {
                System.out.print(" ");
            }

            for (int j = 0; j <= i; j++) {
                System.out.print(getPascal(i,j) + " ");
            }
            System.out.println();
        }
    }
}



