# PascalTriangle
We are implementing pascal triangle with the 1.Iterative 2. Recursive 3.Memoization approach 

MEMOIZATION - The cache map is a data structure that is used to store previously calculated values. It acts as a cache to avoid redundant calculations.

At the beginning of the generateTriangle() function, we create a unique key by concatenating the row and col values as a string (key = row + "," + col). This key represents a specific position in Pascal's triangle.

We check if the cache map already contains a value corresponding to the key. If it does, it means the value has been calculated before, so we directly return that value from the memo map. This saves us from recalculating the value.

If the value is not found in the memo map, we proceed with the calculations. We handle the base cases where col is 0 or equal to row separately. In these cases, the value is 1, as those are the edge elements of Pascal's triangle.

For non-edge elements, we recursively call the generateTriangle() function for the positions in the previous row: to the left (row - 1, col - 1) and directly above (row - 1, col). We store the results in the variables left and right, respectively.

We calculate the result by adding left and right, which represents the value at the current position (row, col) in Pascal's triangle.

We store the calculated result in the cache map with the corresponding key, so that we can reuse it if needed in future calculations.

Finally, we return the calculated result.

By utilizing memoization, the code avoids redundant calculations by storing and reusing previously calculated values. This helps improve the efficiency and speed of generating Pascal's triangle.
