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
                
                return parseChangelogInternal(content.toString());
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
        return parseChangelogInternal(rawContent);
    }

    /**
     * Parse offline changelog (used by WhatsNewActivity)
     * @param rawContent Raw changelog content from offline file
     * @return Formatted HTML string for display
     */
    public static String parseChangelogOffline(String rawContent) {
        return parseChangelogInternal(rawContent);
    }

    /**
     * Internal method to parse changelog content
     * @param rawContent Raw changelog content
     * @return Formatted HTML string for display
     */
    private static String parseChangelogInternal(String rawContent) {
        StringBuilder html = new StringBuilder();
        List<String> lines = new ArrayList<>();
        
        // First, remove all HTML comment blocks
        String contentWithoutComments = removeHtmlComments(rawContent);
        
        // Split content into lines (keep empty lines for divider processing)
        String[] linesArray = contentWithoutComments.split("\n");
        
        // Add lines to our list
        for (String line : linesArray) {
            lines.add(line);
        }
        
        // Elegant animations with fade-in
        html.append("<style>")
            .append("@keyframes flow {")
            .append("  0%, 100% { background-position: 0% 0%; }")
            .append("  50% { background-position: 0% 100%; }")
            .append("}")
            .append("@keyframes glow {")
            .append("  0%, 100% { filter: drop-shadow(0 0 1.5px rgba(170, 31, 31, 0.5)); }")
            .append("  50% { filter: drop-shadow(0 0 2.5px rgba(170, 31, 31, 0.7)); }")
            .append("}")
            .append("@keyframes fadeSlideIn {")
            .append("  0% { opacity: 0; transform: translateY(-15px); }")
            .append("  100% { opacity: 1; transform: translateY(0); }")
            .append("}")
            .append("/* Responsive Table Styles */")
            .append(".table-container {")
            .append("  margin: 12px 0;")
            .append("  padding: 0 4px 0 18px;")
            .append("  animation: fadeSlideIn 0.6s ease-out forwards;")
            .append("  opacity: 0;")
            .append("  overflow-x: auto;")
            .append("  -webkit-overflow-scrolling: touch;")
            .append("}")
            .append("table {")
            .append("  width: 100%;")
            .append("  border-collapse: collapse;")
            .append("  background: transparent;")
            .append("  font-size: 13px;")
            .append("  min-width: 280px;")
            .append("  border-radius: 8px;")
            .append("  overflow: hidden;")
            .append("}")
            .append("thead {")
            .append("  background: linear-gradient(135deg, #1a1a1a 0%, #252525 100%);")
            .append("  position: sticky;")
            .append("  top: 0;")
            .append("  z-index: 10;")
            .append("}")
            .append("thead tr:first-child th:first-child {")
            .append("  border-radius: 8px 0 0 0;")
            .append("}")
            .append("thead tr:first-child th:last-child {")
            .append("  border-radius: 0 8px 0 0;")
            .append("}")
            .append("tbody tr:last-child td:first-child {")
            .append("  border-radius: 0 0 0 8px;")
            .append("}")
            .append("tbody tr:last-child td:last-child {")
            .append("  border-radius: 0 0 8px 0;")
            .append("}")
            .append("th {")
            .append("  padding: 10px 8px;")
            .append("  text-align: left;")
            .append("  color: #E43C3C;")
            .append("  font-weight: 600;")
            .append("  border-bottom: 2px solid #E43C3C;")
            .append("  white-space: nowrap;")
            .append("}")
            .append("td {")
            .append("  padding: 8px;")
            .append("  border-bottom: 1px solid #333333;")
            .append("  color: #E0E0E0;")
            .append("  word-break: break-word;")
            .append("}")
            .append("tbody tr {")
            .append("  transition: all 0.2s ease;")
            .append("}")
            .append("tbody tr:hover {")
            .append("  background: rgba(228, 60, 60, 0.08);")
            .append("}")
            .append("tbody tr:hover td:last-child {")
            .append("  background: rgba(228, 60, 60, 0.15);")
            .append("}")
            .append("tbody td:last-child {")
            .append("  background: rgba(228, 60, 60, 0.08);")
            .append("  color: #4CAF50;")
            .append("  font-weight: 500;")
            .append("}")
            .append("tbody tr:last-child td {")
            .append("  border-bottom: none;")
            .append("}")
            .append(".tree {")
            .append("  background: linear-gradient(180deg, #AA1F1F, #661111, #AA1F1F);")
            .append("  background-size: 100% 200%;")
            .append("  animation: flow 4s ease-in-out infinite, glow 3s ease-in-out infinite;")
            .append("}")
            .append(".fade-in {")
            .append("  animation: fadeSlideIn 0.6s ease-out forwards;")
            .append("  opacity: 0;")
            .append("}")
            .append("</style>");
        
        boolean inTreeSection = false;
        int animationDelay = 0; // Staggered fade-in effect
        
        // Process lines
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            
            // Skip <br> tags
            if (line.equalsIgnoreCase("<br>") || line.equalsIgnoreCase("<br/>") || line.equalsIgnoreCase("<br />")) {
                continue;
            }
            // Check if this is a markdown table
            else if (isMarkdownTableStart(line, i, lines)) {
                String tableHtml = parseMarkdownTable(i, lines);
                html.append("<div class='table-container' style='animation-delay:").append(animationDelay).append("ms;'>")
                    .append(tableHtml)
                    .append("</div>");
                
                // Skip the lines we've already processed
                int tableRowCount = countTableRows(i, lines);
                i += tableRowCount;
                animationDelay += 80;
            }
            // Divider line (---)
            else if (line.equals("---")) {
                if (inTreeSection) {
                    html.append("</div>");
                    inTreeSection = false;
                }
                html.append("<div style='height:8px;'></div>")
                    .append("<hr class='fade-in' style='border:none;border-top:1px solid #444444;margin:0 12px;animation-delay:").append(animationDelay).append("ms;'>")
                    .append("<div style='height:8px;'></div>");
                animationDelay += 50;
            }
            // Image syntax
            else if (line.matches("!\\[.*\\]\\(.*\\)")) {
                String imgHtml = parseImageMarkdown(line);
                html.append("<div class='fade-in' style='animation-delay:").append(animationDelay).append("ms;'>").append(imgHtml).append("</div>");
                animationDelay += 80;
            }
            // H1 headers (# Title)
            else if (line.startsWith("# ") && !line.startsWith("## ")) {
                String headerText = processInlineFormatting(line.substring(2));
                
                html.append("<div class='fade-in' style='position:relative; padding-left:0; margin:10px 0 4px 0;animation-delay:").append(animationDelay).append("ms;'>")
                    // Main vertical spine
                    .append("<div class='tree' style='position:absolute; left:2px; top:0; bottom:0; width:4px; border-radius:6px;'></div>")
                    .append("<div style='margin-left:18px; padding:4px 0;'><font color='").append(HEADER_COLOR).append("'>")
                    .append("<b style='font-size:16px;'>").append(headerText).append("</b>")
                    .append("</font></div>");
                
                inTreeSection = true;
                animationDelay += 80;
            }
            // H2 headers (## Section) - Branch extends from spine
            else if (line.startsWith("## ")) {
                String headerText = processInlineFormatting(line.substring(3));
                
                if (!inTreeSection) {
                    html.append("<div class='fade-in' style='position:relative; padding-left:0;animation-delay:").append(animationDelay).append("ms;'>")
                        .append("<div class='tree' style='position:absolute; left:2px; top:0; bottom:0; width:4px; border-radius:6px;'></div>");
                    inTreeSection = true;
                    animationDelay += 80;
                }
                
                // Simple straight branch from spine
                html.append("<div class='fade-in' style='position:relative; margin:0; padding:6px 0;animation-delay:").append(animationDelay).append("ms;'>")
                    .append("<div class='tree' style='position:absolute; left:6px; top:50%; width:18px; height:3px; border-radius:0 4px 4px 0; transform:translateY(-1px);'></div>")
                    .append("<div style='margin-left:32px;'><font color='").append(HEADER_COLOR).append("'>")
                    .append("<b style='font-size:14px;'>").append(headerText).append("</b>")
                    .append("</font></div></div>");
                animationDelay += 60;
            }
            // H3 headers (### Subsection) - Longer branch
            else if (line.startsWith("### ")) {
                String headerText = processInlineFormatting(line.substring(4));
                
                if (!inTreeSection) {
                    html.append("<div class='fade-in' style='position:relative; padding-left:0;animation-delay:").append(animationDelay).append("ms;'>")
                        .append("<div class='tree' style='position:absolute; left:2px; top:0; bottom:0; width:4px; border-radius:6px;'></div>");
                    inTreeSection = true;
                    animationDelay += 80;
                }
                
                // Longer straight branch from spine
                html.append("<div class='fade-in' style='position:relative; margin:0; padding:6px 0;animation-delay:").append(animationDelay).append("ms;'>")
                    .append("<div class='tree' style='position:absolute; left:6px; top:50%; width:24px; height:3px; border-radius:0 4px 4px 0; transform:translateY(-1px);'></div>")
                    .append("<div style='margin-left:38px;'><font color='").append(HEADER_COLOR).append("'>")
                    .append("<b style='font-size:13px;'>").append(headerText).append("</b>")
                    .append("</font></div></div>");
                animationDelay += 60;
            }
            // Bullet points
            else if (line.startsWith("- ")) {
                String bulletText = line.substring(2);
                bulletText = processInlineFormatting(bulletText);
                html.append("<div class='fade-in' style='margin:2px 0; padding-left:40px;animation-delay:").append(animationDelay).append("ms;'><font color='#A0A0A0'>")
                    .append("• ").append(bulletText)
                    .append("</font></div>");
                animationDelay += 40;
            }
            // Regular text
            else if (!line.isEmpty()) {
                String formattedLine = processInlineFormatting(line);
                html.append("<div class='fade-in' style='margin:2px 0; padding-left:18px;animation-delay:").append(animationDelay).append("ms;'>").append(formattedLine).append("</div>");
                animationDelay += 40;
            }
            // Empty lines
            else {
                html.append("<div style='height:4px;'></div>");
            }
        }
        
        // Close tree container if still open
        if (inTreeSection) {
            html.append("</div>");
        }
        
        return html.toString();
    }

    /**
     * Process inline formatting in text:
     * - `code` → renders as code block with monospace font and background
     * - **bold** → renders as bold white text
     * - [text](url) → renders as clickable link with external link icon
     * @param text Text containing inline formatting
     * @return HTML with processed inline formatting
     */
    private static String processInlineFormatting(String text) {
        // Process links first: [text](url) → <a href="url">text ↗️</a>
        // Pattern: [text](url)
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^\\)]+)\\)", 
            "<a href='$2' style='color:#4CAF50;text-decoration:none;font-weight:bold;'>$1&nbsp;<font style='font-size:11px;'>&#x2197;</font></a>");
        
        // Process backticks (code blocks)
        // Pattern: `code` → <code style="...">code</code>
        text = text.replaceAll("`([^`]+)`", 
            "<code style='background-color:#333333;color:#00FF00;padding:2px 4px;border-radius:3px;font-family:monospace;font-size:11px;'>$1</code>");
        
        // Process bold (**bold**) 
        // Pattern: **bold** → <b style="color:white;">bold</b>
        text = text.replaceAll("\\*\\*([^\\*]+)\\*\\*", 
            "<b style='color:#FFFFFF;'>$1</b>");
        
        return text;
    }

    /**
     * Parse markdown image syntax: ![alt](path)
     * Renders with rounded corners
     * Images are automatically loaded from changelog/ folder
     * @param imageLine The line containing image markdown
     * @return HTML img tag with rounded corners
     */
    private static String parseImageMarkdown(String imageLine) {
        // Extract alt text and image path from ![alt](path)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[(.*)\\]\\((.*)\\)");
        java.util.regex.Matcher matcher = pattern.matcher(imageLine);
        
        if (matcher.find()) {
            String alt = matcher.group(1);
            String path = matcher.group(2);
            
            android.util.Log.d("ChangelogParser", "Found image: " + alt + ", path: " + path);
            
            // Automatically prepend changelog/ folder if not already an absolute path
            if (!path.startsWith("file:///") && !path.startsWith("/")) {
                path = "file:///android_asset/changelog/" + path;
            }
            
            // Return HTML img tag with rounded corners
            return "<div style='margin:8px 0; padding-left:18px;'>"
                + "<img src='" + path + "' style='width:100%; max-width:400px; border-radius:10px;' alt='" + alt + "'>"
                + "</div>";
        }
        
        return "";
    }

    /**
     * Removes HTML comment blocks (<!-- -->) from the content
     * @param content Content with potential HTML comments
     * @return Content with comments removed
     */
    private static String removeHtmlComments(String content) {
        // Remove HTML comment blocks using regex
        // Pattern: <!-- anything (including newlines) -->
        return content.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * Check if a line starts a markdown table
     * Markdown tables have format: | col1 | col2 | ... |
     * Followed by a separator line: | --- | --- | ... |
     */
    private static boolean isMarkdownTableStart(String line, int currentIndex, List<String> lines) {
        if (!line.startsWith("|") || !line.endsWith("|")) {
            return false;
        }
        
        // Check if next line is a separator
        if (currentIndex + 1 < lines.size()) {
            String nextLine = lines.get(currentIndex + 1).trim();
            return isTableSeparator(nextLine);
        }
        
        return false;
    }

    /**
     * Check if a line is a markdown table separator
     * Format: | --- | --- | ... |
     */
    private static boolean isTableSeparator(String line) {
        if (!line.startsWith("|") || !line.endsWith("|")) {
            return false;
        }
        
        // Split by | and check if all parts (except first and last which are empty) contain only dashes and hyphens
        String[] parts = line.split("\\|");
        for (int i = 1; i < parts.length - 1; i++) {
            String part = parts[i].trim();
            if (!part.matches("-+")) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Count total rows in table (header + separator + data rows)
     */
    private static int countTableRows(int startIndex, List<String> lines) {
        int count = 0;
        
        // Count header (1) + separator (1)
        count = 2;
        
        // Count data rows
        for (int i = startIndex + 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("|") && line.endsWith("|")) {
                count++;
            } else {
                break;
            }
        }
        
        // Return total - 1 because we'll skip to the last row index
        return count - 1;
    }

    /**
     * Parse markdown table and return HTML table
     * Handles: | Header1 | Header2 | ... |
     *          | --- | --- | ... |
     *          | Data1 | Data2 | ... |
     */
    private static String parseMarkdownTable(int startIndex, List<String> lines) {
        StringBuilder tableHtml = new StringBuilder();
        
        String headerLine = lines.get(startIndex).trim();
        String[] headers = parseTableRow(headerLine);
        
        tableHtml.append("<table>")
                 .append("<thead><tr>");
        
        // Add header cells
        for (String header : headers) {
            tableHtml.append("<th>").append(processInlineFormatting(header)).append("</th>");
        }
        
        tableHtml.append("</tr></thead>")
                 .append("<tbody>");
        
        // Process data rows
        for (int i = startIndex + 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            
            if (!line.startsWith("|") || !line.endsWith("|")) {
                break;
            }
            
            String[] cells = parseTableRow(line);
            tableHtml.append("<tr>");
            
            for (String cell : cells) {
                tableHtml.append("<td>").append(processInlineFormatting(cell)).append("</td>");
            }
            
            tableHtml.append("</tr>");
        }
        
        tableHtml.append("</tbody>")
                 .append("</table>");
        
        return tableHtml.toString();
    }

    /**
     * Parse a markdown table row and extract cell contents
     * Format: | cell1 | cell2 | cell3 |
     * Returns array of trimmed cell contents
     */
    private static String[] parseTableRow(String row) {
        // Remove leading and trailing pipes
        String content = row.replaceAll("^\\|+|\\|+$", "");
        
        // Split by pipe and trim each cell
        String[] cells = content.split("\\|");
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        
        return cells;
    }
}
