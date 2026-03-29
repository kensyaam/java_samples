package com.analyzer.test;

public class HelperClass {
    public void help() {
        System.out.println("Helping...");
        internalWork();
    }

    private void internalWork() {
        if (true) {
            System.out.println("Working internally");
        }
    }
}
