package com.fadcam.utils;

import android.text.Html;
import android.text.Spanned;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Parser for the GitHub CHANGELOG.md file
 * Fetches and formats the changelog content for display in the app
 */
public class ChangelogParser {
    private static final String TAG = "ChangelogParser";
    private static final String CHANGELOG_URL = "https://raw.githubusercontent.com/anonfaded/FadCam/refs/heads/master/CHANGELOG.md";
    private static final String HEADER_COLOR = "#E43C3C"; // Red color for headers

    /**
     * Fetches the changelog from GitHub and returns it as a CompletableFuture<String>
     * @return CompletableFuture containing the formatted changelog HTML
     */
    public static CompletableFuture<String> fetchChangelog() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(CHANGELOG_URL);
                BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
                StringBuilder content = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();
                
                return parseChangelog(content.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error fetching changelog", e);
                return "";
            }
        });
    }
    
    /**
     * Parses the raw changelog content and returns formatted HTML
     * @param rawContent Raw changelog content from GitHub
     * @return Formatted HTML string for display
     */
    private static String parseChangelog(String rawContent) {
        StringBuilder html = new StringBuilder();
        List<String> lines = new ArrayList<>();
        
        // First, remove all HTML comment blocks
        String contentWithoutComments = removeHtmlComments(rawContent);
        
        // Split content into lines
        String[] linesArray = contentWithoutComments.split("\n");
        
        // Add non-empty lines to our list
        for (String line : linesArray) {
            if (!line.trim().isEmpty()) {
                lines.add(line.trim());
            }
        }
        
        // Process lines
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            
            // Section headers (# New Features, # Fixes)
            if (line.startsWith("# ")) {
                if (i > 0) {
                    html.append("<br>");
                }
                // Add colored formatting to headers
                html.append("<font color='").append(HEADER_COLOR).append("'><b>")
                    .append(line.substring(2))
                    .append("</b></font><br>");
            }
            // Bullet points
            else if (line.startsWith("- ")) {
                html.append("• ").append(line.substring(2)).append("<br>");
            }
            // Regular text (shouldn't happen with our format but just in case)
            else if (!line.trim().isEmpty()) {
                html.append(line).append("<br>");
            }
        }
        
        return html.toString();
    }
    
    /**
     * Removes HTML comments from the content
     * @param content Raw content with potential HTML comments
     * @return Content with HTML comments removed
     */
    private static String removeHtmlComments(String content) {
        // Pattern to match HTML comments, including multi-line ones
        Pattern commentPattern = Pattern.compile("<!--[\\s\\S]*?-->", Pattern.DOTALL);
        return commentPattern.matcher(content).replaceAll("");
    }
} 