package com.coffee.beansfinder.config;

import java.util.*;

public class Test {
    public static void main(String args[]){
        int[][] points = {{1, 2}, {3, 4}, {0, 0}, {4, 1}, {2, 3}};
        plotGraph(points);
    }

    public static void plotGraph(int[][] points){
        //find the maxX and maxY
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        Set<String> starCoord = new HashSet<String>();

        // for the point and create the list
        for(int[] point : points){
            maxY = Math.max(maxY,point[0]);
            maxX = Math.max(maxX,point[1]);
            System.out.println(point[0] +","+ point[1]);
            starCoord.add(point[0] +","+ point[1]);
        }

        System.out.println("_____");
        for(int y = maxY ; y >= 0; y--){
            for(int x = 0 ; x <= maxX ; x++){
                if (y == 0) {
                    if(starCoord.contains(y +","+ x)){
                        System.out.print('X');
                    }else{
                        System.out.print('_');
                    }
                }else{
                    if(starCoord.contains(y +","+ x)){
                        System.out.print('X');
                    }else{
                        System.out.print('-');
                    }
                }
            }


            System.out.println();
        }

    }
}
