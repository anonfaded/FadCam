/**
 * Formatter - Utility functions for formatting data
 */
class Formatter {
    /**
     * Format bytes to human-readable string
     * @param {number} bytes - Number of bytes
     * @param {number} decimals - Decimal places (default: 2)
     * @returns {string} Formatted string (e.g., "1.5 GB")
     */
    static formatBytes(bytes, decimals = 2) {
        if (bytes === 0) return '0 B';
        if (!bytes || bytes < 0) return 'N/A';
        
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        
        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }
    
    /**
     * Format seconds to time string
     * @param {number} seconds - Total seconds
     * @returns {string} Formatted time (e.g., "2h 34m 12s")
     */
    static formatTime(seconds) {
        if (!seconds || seconds < 0) return '00:00';
        
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        const s = Math.floor(seconds % 60);
        
        if (h > 0) {
            return `${h}h ${m}m ${s}s`;
        } else if (m > 0) {
            return `${m}m ${s}s`;
        } else {
            return `${s}s`;
        }
    }
    
    /**
     * Format uptime to readable string
     * @param {number} seconds - Total seconds
     * @returns {string} Formatted uptime (e.g., "2:34:12")
     */
    static formatUptime(seconds) {
        if (!seconds || seconds < 0) return '00:00:00';
        
        const h = Math.floor(seconds / 3600).toString().padStart(2, '0');
        const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0');
        const s = Math.floor(seconds % 60).toString().padStart(2, '0');
        
        return `${h}:${m}:${s}`;
    }
    
    /**
     * Format percentage
     * @param {number} value - Percentage value
     * @param {number} decimals - Decimal places (default: 0)
     * @returns {string} Formatted percentage (e.g., "95%")
     */
    static formatPercent(value, decimals = 0) {
        if (value == null || value < 0) return 'N/A';
        return value.toFixed(decimals) + '%';
    }
    
    /**
     * Format number with commas
     * @param {number} num - Number to format
     * @returns {string} Formatted number (e.g., "1,234")
     */
    static formatNumber(num) {
        if (num == null) return 'N/A';
        return num.toLocaleString();
    }
    
    /**
     * Get status emoji
     * @param {string} state - Status state
     * @returns {string} Emoji icon
     */
    static getStatusEmoji(state) {
        const emojis = {
            'ready': '⚡',
            'initializing': '⏳',
            'buffering': '⏳',
            'disabled': '❌',
            'not_recording': '⏸️',
            'offline': '❌'
        };
        return emojis[state] || '❓';
    }
    
    /**
     * Get status text
     * @param {string} state - Status state
     * @returns {string} Human-readable status
     */
    static getStatusText(state) {
        const texts = {
            'ready': 'READY',
            'initializing': 'INITIALIZING',
            'buffering': 'BUFFERING',
            'disabled': 'DISABLED',
            'not_recording': 'NOT RECORDING',
            'offline': 'OFFLINE'
        };
        return texts[state] || 'UNKNOWN';
    }
}
