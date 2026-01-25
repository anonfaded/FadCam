package com.fadcam.streaming.util;

/**
 * Utility class for properly escaping strings to be embedded in JSON.
 * Handles special characters that can break JSON parsing.
 */
public class JsonEscaper {
    
    /**
     * Escape a string for safe inclusion in JSON values.
     * Escapes: backslash, double quotes, control characters, etc.
     * 
     * @param input The string to escape
     * @return Escaped string safe for JSON embedding
     */
    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // Escape control characters and non-ASCII characters that might cause encoding issues
                    if (ch < 0x20 || (ch >= 0x7F && ch < 0xA0)) {
                        // Control character - escape as unicode
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                    break;
            }
        }
        return sb.toString();
    }
    
    /**
     * Create a properly escaped JSON string value (with surrounding quotes).
     * 
     * @param input The string to escape
     * @return Properly escaped JSON string value with quotes
     */
    public static String escapeToJsonString(String input) {
        return "\"" + escape(input) + "\"";
    }
}
