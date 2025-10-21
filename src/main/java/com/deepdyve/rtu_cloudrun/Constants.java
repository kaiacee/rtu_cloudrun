package com.deepdyve.rtu_cloudrun;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Constants {
    public static boolean QA = false;

    public static String mqUrl;
    public static String mqStreamName;
    public static String mqSubjectOut;
    public static String CONFIG = "config.file";

    static {
        String s = System.getProperty(CONFIG);
        if (s == null) {
            s = "cloudrun.properties";  // normal
        } else if (s.startsWith("qa")) {
            QA = true;
            System.out.println("\t** QA Settings Chosen **");
        }
        //System.out.println("CONFIG FILE=" + s);
        Properties defaults = new Properties();
        InputStream fis = null;
        try {
            fis = Constants.class.getClassLoader().getResourceAsStream(s);
            defaults.load(fis);
                /* TESTING
            System.out.println("PROPERTYNAMES");
            Enumeration e = defaults.propertyNames();
            while (e.hasMoreElements())
            {
                String key = e.nextElement().toString();
                System.out.println(key + ": " + defaults.getProperty(key));
            }
            System.out.println("DONE\n");
            */
        } catch (IOException e) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
        // message queue
        if (System.getProperty("MQURL") == null) {
            mqUrl = defaults.getProperty("mqUrl");
        } else {
            mqUrl = System.getProperty("MQURL");
            System.out.println("Using " + mqUrl);
        }
        mqStreamName = defaults.getProperty("mqStreamName");
        mqSubjectOut = defaults.getProperty("mqSubjectOut");
    }
}
