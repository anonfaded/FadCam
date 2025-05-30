package com.guardanis.applock.utils;

import java.security.MessageDigest;

public class CryptoUtils {

    public static String encryptSha1(String text) {
        try{
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(text.getBytes("UTF-8"), 0, text.length());

            return convertToHex(md.digest());
        }
        catch(Exception e){ e.printStackTrace(); }

        return "";
    }

    private static String convertToHex(byte[] data) {
        StringBuilder buf = new StringBuilder();

        for(byte b : data){
            int half = (b >>> 4) & 0x0F;
            int twoHalves = 0;

            do{
                buf.append((0 <= half) && (half <= 9)
                        ? (char) ('0' + half)
                        : (char) ('a' + (half - 10)));

                half = b & 0x0F;
            }
            while(twoHalves++ < 1);
        }

        return buf.toString();
    }
}
