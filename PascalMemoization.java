package com.PascalTriangle;

import java.util.HashMap;
import java.util.Map;

public class PascalMemoization {
    public static Map<String,Integer> cache = new HashMap<>();
    public static int getPascal(int row , int col) {

        String key = row + "," + col;

        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        if (col == 0 || col == row) {
            return 1;
        }else{
            int left = getPascal(row - 1 ,col - 1); // left element
            int right = getPascal(row - 1, col); // right element
            int result = left + right; // on adding the left and the right element we will get the next desired element

            cache.put(key,result);
            return result;
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
