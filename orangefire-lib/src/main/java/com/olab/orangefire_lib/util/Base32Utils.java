package com.olab.orangefire_lib.util;

import com.olab.orangefire_lib.core.GeoHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Base32Utils {

    /* number of bits per base 32 character */
    public static final int BITS_PER_BASE32_CHAR = 5;

    private static final String BASE32_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Base32Utils() {}

    public static char valueToBase32Char(int value) {
        if (value < 0 || value >= BASE32_CHARS.length()) {
            throw new IllegalArgumentException("Not a valid base32 value: " + value);
        }
        return BASE32_CHARS.charAt(value);
    }

    public static int base32CharToValue(char base32Char) {
        int value = BASE32_CHARS.indexOf(base32Char);
        if (value == -1) {
            throw new IllegalArgumentException("Not a valid base32 char: " + base32Char);
        } else {
            return value;
        }
    }

    public static boolean isValidBase32String(String string) {
        return string.matches("^[" + BASE32_CHARS + "]*$");
    }

    public static String nextGeohash ( String startString, int flipPosition){
        StringBuilder tempString = new StringBuilder(startString);
        if(nextCharacter(tempString, flipPosition ) ){
            if( flipPosition-1 > -1){
                tempString = nextGeohash( tempString, flipPosition-1);
            }
        }
        return tempString.toString();
    }

    public static StringBuilder nextGeohash ( StringBuilder stringBuilder, int flipPosition){
        if(nextCharacter(stringBuilder, flipPosition ) ){
            if( flipPosition-1 > -1){
                stringBuilder = nextGeohash( stringBuilder, flipPosition-1);
            }
        }
        return stringBuilder;
    }

    public static Set<String> GenerateHashesFromTo(String startString, String endString){
        List<String> toProcess = new ArrayList<String>();
        List<String> doneProcessing = new ArrayList<String>();

        String tempHash = startString;
        while( !tempHash.equals( endString ) ){
            doneProcessing.add( tempHash );
            tempHash = Base32Utils.nextGeohash(tempHash, tempHash.length()-1);
        }
        if( tempHash != startString){
            doneProcessing.add( endString);
        }
        while( doneProcessing.get(0).length() < GeoHash.DEFAULT_PRECISION){
            toProcess = doneProcessing;
            doneProcessing = new ArrayList<>();
            for ( String s: toProcess) {
                for (int c = 0; c < 31; c++){
                    doneProcessing.add( s+ valueToBase32Char(c) );
                }
            }
        }

        return new HashSet<>(doneProcessing);
    }

    static Boolean nextCharacter( StringBuilder s, int pos){
        int i = base32CharToValue(s.charAt(pos));
        i++;
        if( i >= BASE32_CHARS.length()){
            i=0;
            s.setCharAt( pos, BASE32_CHARS.charAt(i) );
            return true;
        }
        s.setCharAt( pos, BASE32_CHARS.charAt(i) );
        return false;
    }
}
