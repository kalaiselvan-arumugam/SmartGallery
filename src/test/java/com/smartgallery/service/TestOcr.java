package com.smartgallery.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

public class TestOcr {

    @Test
    public void testRapidOcr() {
        try {
            Class<?> engineClass = Class.forName("com.benjaminwan.ocrlibrary.OcrEngine");
            System.out.println("====== CONSTRUCTORS IN OcrEngine ======");
            for (Constructor<?> c : engineClass.getDeclaredConstructors()) {
                System.out.println(c.toString());
            }

            System.out.println("====== METHODS IN OcrEngine ======");
            for (Method m : engineClass.getDeclaredMethods()) {
                System.out.println(m.toString());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
