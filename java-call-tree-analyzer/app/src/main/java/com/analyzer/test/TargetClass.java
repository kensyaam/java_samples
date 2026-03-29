package com.analyzer.test;

public class TargetClass {
    private HelperClass helper = new HelperClass();

    public void mainProcess(int count) {
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                helper.help();
            }
        } else {
            switch (count) {
                case 0:
                    System.out.println("Zero");
                    break;
                default:
                    System.out.println("Negative");
            }
        }

        int x = 0;
        while (x < 5) {
            x++;
        }
    }
}
